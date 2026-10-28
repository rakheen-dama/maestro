package io.b2mash.maestro.messaging.postgres;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * PostgreSQL table-based implementation of the {@link WorkflowMessaging} SPI.
 *
 * <p>Messages are persisted to PostgreSQL tables ({@code maestro_task_queue},
 * {@code maestro_signal_queue}, {@code maestro_lifecycle_event_queue}) and
 * consumed by background polling threads. {@code NOTIFY} is used for
 * immediate wake-up of pollers after a publish.
 *
 * <h2>Delivery Guarantees</h2>
 * <ul>
 *   <li>All publish methods provide <b>at-least-once</b> delivery.</li>
 *   <li>Consumers must be idempotent — duplicate messages are possible
 *       if the status write after a handler fails while attempt budget
 *       remains (see below), and a handler that throws is re-invoked with the
 *       same message.</li>
 *   <li>Message ordering is guaranteed <b>per queue</b> by
 *       {@code created_at} ordering.</li>
 * </ul>
 *
 * <h2>Handler Failure</h2>
 * <p>A handler exception is never an acknowledgement: the queue row is the
 * retry ledger. The row returns to {@code PENDING} — its {@code attempts}
 * counter already incremented by the claim — with a {@code next_attempt_at} in the future
 * (exponential backoff, see {@link PostgresRedeliveryConfig}), and once the
 * attempt budget is exhausted it is parked in {@code DEAD_LETTER} — durable and
 * inspectable via {@link #listDeadLetterSignals}/{@link #listDeadLetterTasks},
 * replayable via {@link #replaySignal}/{@link #replayTask}. A message is never
 * dropped, and a poison message never becomes a hot loop. With
 * {@code maestro.messaging.redelivery.enabled=false} none of that applies: a
 * failing row is marked {@code FAILED} after its single attempt instead —
 * the operator's explicit choice of at-most-once handler semantics.
 *
 * <h2>Attempts Are Counted at Claim Time</h2>
 * <p>Claiming a row ({@code PENDING} → {@code PROCESSING}) increments its
 * {@code attempts} in the same statement. The status write that follows the
 * handler — {@code COMPLETED} on success, {@code PENDING}/{@code DEAD_LETTER}
 * on failure with redelivery enabled, {@code FAILED} with it disabled — can
 * itself fail, and when it does the database is the thing that is down, so
 * nothing can be recorded <i>at</i> that failure: the row is simply left
 * {@code PROCESSING}. The count written at claim time is the only durable
 * marker available, and it lets one rule govern every stale
 * {@code PROCESSING} row (one older than five minutes), uniformly across all
 * three status-write sites (Issue 27):
 * <ul>
 *   <li><b>Budget left</b> ({@code attempts < maxAttempts}, redelivery
 *       enabled): the stale reclaim re-claims the row and re-invokes the
 *       handler. This is the documented, accepted double delivery under a
 *       double fault; handlers must be idempotent.</li>
 *   <li><b>No budget left</b> ({@code attempts >= maxAttempts} with
 *       redelivery enabled; {@code attempts >= 1} with it disabled — exactly
 *       one attempt): a retire sweep, run before every claim in the same
 *       transaction, moves the row straight to {@code DEAD_LETTER} — in
 *       <i>both</i> redelivery modes — with a {@code last_error} saying the
 *       outcome is unknown, and never invokes the handler again. The claim's
 *       stale-reclaim branch independently refuses such a row, so a sweep
 *       that skipped it (another node held it locked) cannot let it be
 *       re-invoked.</li>
 * </ul>
 * <p>A retired row always needs human triage — the handler may or may not
 * have run to completion — so it is parked where triage happens:
 * {@code DEAD_LETTER} is listable via {@link #listDeadLetterSignals}/{@link
 * #listDeadLetterTasks}, never purged by {@link PostgresMessageCleaner}, and
 * replayed only deliberately. With redelivery disabled this is the one case
 * that does not end {@code FAILED}: {@code FAILED} would be purged after
 * retention and swept up by the blanket {@code FAILED} rescue statement.
 * <p>Consequence, deliberately: a consumer that crashes mid-handler consumes
 * an attempt. A poison message that crashes the JVM therefore dead-letters
 * after {@code maxAttempts} crashes instead of looping forever, and with
 * redelivery disabled at-most-once handler invocation holds even when the
 * final status write is lost.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing uses pooled connections with
 * explicit commit/rollback. Polling threads are virtual threads managed
 * internally.
 *
 * @see WorkflowMessaging
 * @see PostgresNotificationListener
 */
@NullMarked
public final class PostgresWorkflowMessaging implements WorkflowMessaging, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresWorkflowMessaging.class);

    private static final String TASK_CHANNEL = "maestro_task_queue";
    private static final String SIGNAL_CHANNEL = "maestro_signal_queue";

    /**
     * Polling interval in milliseconds when no NOTIFY wake-up is received.
     */
    private static final long POLL_INTERVAL_MS = 1000;

    /** Longest {@code last_error} kept on a queue row. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final PostgresNotificationListener notificationListener;
    private final PostgresRedeliveryConfig redelivery;
    private final CopyOnWriteArrayList<Thread> pollingThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new PostgreSQL-based workflow messaging implementation with the
     * default redelivery policy.
     *
     * @param dataSource           the DataSource for database operations
     * @param objectMapper         Jackson 3 ObjectMapper for JSON serialization
     * @param notificationListener the shared LISTEN/NOTIFY listener
     */
    public PostgresWorkflowMessaging(
            DataSource dataSource,
            ObjectMapper objectMapper,
            PostgresNotificationListener notificationListener
    ) {
        this(dataSource, objectMapper, notificationListener, PostgresRedeliveryConfig.defaults());
    }

    /**
     * Creates a new PostgreSQL-based workflow messaging implementation.
     *
     * @param dataSource           the DataSource for database operations
     * @param objectMapper         Jackson 3 ObjectMapper for JSON serialization
     * @param notificationListener the shared LISTEN/NOTIFY listener
     * @param redelivery           policy applied when a handler throws
     */
    public PostgresWorkflowMessaging(
            DataSource dataSource,
            ObjectMapper objectMapper,
            PostgresNotificationListener notificationListener,
            PostgresRedeliveryConfig redelivery
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.notificationListener = notificationListener;
        this.redelivery = redelivery;
    }

    // ── Publishing ───────────────────────────────────────────────────────

    @Override
    public void publishTask(String taskQueue, TaskMessage message) {
        var sql = """
                INSERT INTO maestro_task_queue
                    (id, task_queue, workflow_instance_id, workflow_id, workflow_type, run_id, service_name, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setString(2, taskQueue);
                stmt.setObject(3, message.workflowInstanceId());
                stmt.setString(4, message.workflowId());
                stmt.setString(5, message.workflowType());
                stmt.setObject(6, message.runId());
                stmt.setString(7, message.serviceName());
                stmt.setString(8, serializeNullable(message.input()));
                stmt.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to publish task message for workflow '" + message.workflowId()
                            + "' to queue '" + taskQueue + "'", e);
        }
        notifyChannel(TASK_CHANNEL, taskQueue);
    }

    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        var sql = """
                INSERT INTO maestro_signal_queue
                    (id, service_name, workflow_id, signal_name, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setString(2, serviceName);
                stmt.setString(3, message.workflowId());
                stmt.setString(4, message.signalName());
                stmt.setString(5, serializeNullable(message.payload()));
                stmt.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to publish signal '" + message.signalName()
                            + "' for workflow '" + message.workflowId()
                            + "' to service '" + serviceName + "'", e);
        }
        notifyChannel(SIGNAL_CHANNEL, serviceName);
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        var sql = """
                INSERT INTO maestro_lifecycle_event_queue
                    (id, workflow_instance_id, workflow_id, workflow_type, service_name,
                     task_queue, event_type, step_name, detail, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setObject(2, event.workflowInstanceId());
                stmt.setString(3, event.workflowId());
                stmt.setString(4, event.workflowType());
                stmt.setString(5, event.serviceName());
                stmt.setString(6, event.taskQueue());
                stmt.setString(7, event.eventType().name());
                stmt.setString(8, event.stepName());
                stmt.setString(9, serializeNullable(event.detail()));
                stmt.setTimestamp(10, Timestamp.from(event.timestamp()));
                stmt.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            // SPI contract: lifecycle event failures must not interrupt workflow execution
            logger.warn("Failed to publish lifecycle event {} for workflow '{}': {}",
                    event.eventType(), event.workflowId(), e.getMessage(), e);
        }
    }

    // ── Subscribing ──────────────────────────────────────────────────────

    @Override
    public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        running.set(true);

        // Register for immediate wake-up via NOTIFY
        var channelName = PostgresNotificationListener.sanitizeChannel(TASK_CHANNEL + "_" + taskQueue);
        var lock = new Object();
        notificationListener.listen(channelName, (ch, payload) -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });

        var thread = Thread.ofVirtual()
                .name("maestro-pg-task-poller-" + taskQueue)
                .start(() -> pollTasks(taskQueue, handler, lock));
        pollingThreads.add(thread);
        logger.info("Subscribed to task queue '{}' (Postgres polling)", taskQueue);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        running.set(true);

        var channelName = PostgresNotificationListener.sanitizeChannel(SIGNAL_CHANNEL + "_" + serviceName);
        var lock = new Object();
        notificationListener.listen(channelName, (ch, payload) -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });

        var thread = Thread.ofVirtual()
                .name("maestro-pg-signal-poller-" + serviceName)
                .start(() -> pollSignals(serviceName, handler, lock));
        pollingThreads.add(thread);
        logger.info("Subscribed to signals for service '{}' (Postgres polling)", serviceName);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void close() {
        running.set(false);
        for (var thread : pollingThreads) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pollingThreads.clear();
        logger.info("PostgresWorkflowMessaging closed");
    }

    // ── Polling loops ───────────────────────────────────────────────────

    private void pollTasks(String taskQueue, Consumer<TaskMessage> handler, Object lock) {
        // A PENDING row is claimable once it is due: a handler failure pushes
        // next_attempt_at into the future rather than retiring the row. The
        // claim itself counts the attempt, so a consumer that crashed (or lost
        // its final status write) mid-handler has consumed one: a stale
        // PROCESSING row is re-claimed only while budget remains (the
        // `attempts < ?` guard, so this does not depend on the sweep having
        // run), and retireStaleClaims parks it once the budget is spent (Issue 27).
        var claimSql = """
                UPDATE maestro_task_queue
                SET status = 'PROCESSING', attempts = attempts + 1, processed_at = now()
                WHERE id = (
                    SELECT id FROM maestro_task_queue
                    WHERE task_queue = ?
                      AND ((status = 'PENDING' AND next_attempt_at <= now())
                           OR (status = 'PROCESSING' AND processed_at < now() - interval '5 minutes'
                               AND attempts < ?))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, task_queue, workflow_instance_id, workflow_id, workflow_type,
                          run_id, service_name, payload, attempts
                """;

        while (running.get()) {
            try {
                var processed = false;
                try (var conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.prepareStatement(claimSql)) {
                        retireStaleClaims(conn, "maestro_task_queue", "task_queue", taskQueue);
                        stmt.setString(1, taskQueue);
                        stmt.setInt(2, attemptThreshold());
                        try (var rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                processed = true;
                                var rowId = rs.getObject("id", UUID.class);
                                var attempts = rs.getInt("attempts");
                                var message = readTaskMessage(rs);
                                conn.commit();
                                processTaskMessage(rowId, attempts, message, handler);
                            } else {
                                conn.commit();
                            }
                        }
                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    }
                }

                if (!processed) {
                    synchronized (lock) {
                        lock.wait(POLL_INTERVAL_MS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    logger.error("Error polling task queue '{}': {}", taskQueue, e.getMessage(), e);
                    sleepQuietly(POLL_INTERVAL_MS);
                }
            }
        }
    }

    private void pollSignals(String serviceName, Consumer<SignalMessage> handler, Object lock) {
        // See pollTasks: PENDING rows are claimable once due, so a failed
        // handler's row comes back after its backoff instead of being lost;
        // the claim counts the attempt and stale claims with no budget left
        // are retired before the claim.
        var claimSql = """
                UPDATE maestro_signal_queue
                SET status = 'PROCESSING', attempts = attempts + 1, processed_at = now()
                WHERE id = (
                    SELECT id FROM maestro_signal_queue
                    WHERE service_name = ?
                      AND ((status = 'PENDING' AND next_attempt_at <= now())
                           OR (status = 'PROCESSING' AND processed_at < now() - interval '5 minutes'
                               AND attempts < ?))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, service_name, workflow_id, signal_name, payload, attempts
                """;

        while (running.get()) {
            try {
                var processed = false;
                try (var conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.prepareStatement(claimSql)) {
                        retireStaleClaims(conn, "maestro_signal_queue", "service_name", serviceName);
                        stmt.setString(1, serviceName);
                        stmt.setInt(2, attemptThreshold());
                        try (var rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                processed = true;
                                var rowId = rs.getObject("id", UUID.class);
                                var attempts = rs.getInt("attempts");
                                var message = readSignalMessage(rs);
                                conn.commit();
                                processSignalMessage(rowId, attempts, message, handler);
                            } else {
                                conn.commit();
                            }
                        }
                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    }
                }

                if (!processed) {
                    synchronized (lock) {
                        lock.wait(POLL_INTERVAL_MS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    logger.error("Error polling signals for service '{}': {}", serviceName, e.getMessage(), e);
                    sleepQuietly(POLL_INTERVAL_MS);
                }
            }
        }
    }

    // ── Message processing ──────────────────────────────────────────────

    private void processTaskMessage(UUID rowId, int attempts, TaskMessage message,
                                    Consumer<TaskMessage> handler) {
        try {
            handler.accept(message);
            updateStatus("maestro_task_queue", rowId, "COMPLETED");
        } catch (Exception e) {
            recordFailure("maestro_task_queue", rowId, attempts, message.workflowId(), e);
        }
    }

    private void processSignalMessage(UUID rowId, int attempts, SignalMessage message,
                                      Consumer<SignalMessage> handler) {
        try {
            handler.accept(message);
            updateStatus("maestro_signal_queue", rowId, "COMPLETED");
        } catch (Exception e) {
            recordFailure("maestro_signal_queue", rowId, attempts, message.workflowId(), e);
        }
    }

    /**
     * Records a successful delivery. If this write fails, the row stays
     * {@code PROCESSING}; the attempt was already counted at claim time, so the
     * stale reclaim re-invokes the handler only if attempt budget remains —
     * otherwise {@link #retireStaleClaims} retires it (Issue 27).
     */
    private void updateStatus(String table, UUID rowId, String status) {
        var sql = "UPDATE " + table + " SET status = ?, processed_at = now() WHERE id = ?";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setObject(2, rowId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to update status to '{}' for row {} in {} — leaving it PROCESSING; "
                            + "the stale reclaim re-invokes it only if attempt budget remains; "
                            + "otherwise it is retired: {}",
                    status, rowId, table, e.getMessage(), e);
        }
    }

    /**
     * Retires stale {@code PROCESSING} claims that have no attempt budget left,
     * so the claim that follows in the same transaction can only re-claim rows
     * that are still allowed another attempt.
     *
     * <p>A stale claim is one whose final status write never landed — the
     * consumer crashed, or the store was unreachable when the handler
     * returned. Its attempt was counted at claim time; when that was the last
     * one ({@code attempts >= maxAttempts}, or {@code attempts >= 1} with
     * redelivery disabled), the row moves to {@code DEAD_LETTER} in either
     * mode without the handler being invoked again. The handler may or may not
     * have run to completion — hence "outcome unknown", and hence
     * {@code DEAD_LETTER}: the row needs triage, not purging. Rows locked by
     * another transaction are skipped ({@code FOR UPDATE SKIP LOCKED}) and
     * retired by a later sweep.
     *
     * @return the number of rows retired
     */
    private int retireStaleClaims(Connection conn, String table, String qualifierColumn, String qualifier)
            throws SQLException {
        // last_error = "finalisation write lost after attempt <n>; outcome unknown — ..."
        // SKIP LOCKED: a row another node holds is skipped rather than waited
        // on, so concurrent sweeps neither stall the poll loop nor deadlock;
        // the skipped row is retired by a later sweep (the claim cannot take
        // it meanwhile — its stale branch requires attempts < threshold).
        var sql = "UPDATE " + table + " SET status = 'DEAD_LETTER', "
                + "last_error = 'finalisation write lost after attempt ' || attempts || ?, "
                + "processed_at = now() "
                + "WHERE id IN (SELECT id FROM " + table + " "
                + "WHERE " + qualifierColumn + " = ? AND status = 'PROCESSING' "
                + "AND processed_at < now() - interval '5 minutes' AND attempts >= ? "
                + "FOR UPDATE SKIP LOCKED)";
        int retired;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "; outcome unknown — retired without re-invoking the handler");
            stmt.setString(2, qualifier);
            stmt.setInt(3, attemptThreshold());
            retired = stmt.executeUpdate();
        }
        if (retired > 0) {
            logger.error("Retired {} stale PROCESSING claim(s) in {} for '{}' as DEAD_LETTER — attempt "
                            + "budget spent and the final status write was lost; outcome unknown, "
                            + "the handler is not invoked again — triage via listDeadLetter*",
                    retired, table, qualifier);
        }
        return retired;
    }

    /**
     * The number of attempts after which a stale claim is retired instead of
     * re-claimed: {@code maxAttempts} with redelivery enabled, exactly one with
     * it disabled. Shared by {@link #retireStaleClaims} and the claim query's
     * stale-reclaim branch so the two can never disagree.
     */
    private int attemptThreshold() {
        return redelivery.enabled() ? redelivery.maxAttempts() : 1;
    }

    /**
     * Schedules the next attempt for a message whose handler threw, or parks it
     * once the attempt budget is spent.
     *
     * <p>{@code attempts} is the count the claim already wrote, including this
     * attempt. If this update itself fails — the store being unreachable is
     * exactly the fault we are surviving — the row is left {@code PROCESSING}:
     * after five minutes the stale reclaim re-invokes the handler if attempt
     * budget remains, and otherwise {@link #retireStaleClaims} parks it in
     * {@code DEAD_LETTER}. Either way the message is not lost.
     *
     * <p>When {@code maestro.messaging.redelivery.enabled=false}, this skips
     * straight to marking the row {@code FAILED} after its single attempt — see
     * {@link #recordSingleAttemptFailure}.
     */
    private void recordFailure(String table, UUID rowId, int attempts,
                               String workflowId, Exception failure) {
        if (!redelivery.enabled()) {
            recordSingleAttemptFailure(table, rowId, attempts, workflowId, failure);
            return;
        }

        var exhausted = attempts >= redelivery.maxAttempts();
        var backoffMillis = exhausted ? 0L : redelivery.backoffAfter(attempts).toMillis();

        if (exhausted) {
            logger.error("Message {} for workflow '{}' in {} failed on attempt {}/{} — "
                            + "parking it in DEAD_LETTER: {}",
                    rowId, workflowId, table, attempts, redelivery.maxAttempts(),
                    failure.getMessage(), failure);
        } else {
            logger.warn("Message {} for workflow '{}' in {} failed on attempt {}/{} — "
                            + "redelivering in {}ms: {}",
                    rowId, workflowId, table, attempts, redelivery.maxAttempts(),
                    backoffMillis, failure.getMessage(), failure);
        }

        var sql = "UPDATE " + table + " SET status = ?, attempts = ?, "
                + "next_attempt_at = now() + (? * interval '1 millisecond'), "
                + "last_error = ?, processed_at = now() WHERE id = ?";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, exhausted ? "DEAD_LETTER" : "PENDING");
            stmt.setInt(2, attempts);
            stmt.setLong(3, backoffMillis);
            stmt.setString(4, describe(failure));
            stmt.setObject(5, rowId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // The row stays PROCESSING; the claim already counted this attempt.
            logger.warn("Failed to record delivery failure for row {} in {} — leaving it "
                            + "PROCESSING; the stale reclaim re-invokes it only if attempt "
                            + "budget remains; otherwise it is retired: {}",
                    rowId, table, e.getMessage(), e);
        }
    }

    /**
     * The {@code maestro.messaging.redelivery.enabled=false} path: no backoff,
     * no {@code DEAD_LETTER} — the row is marked {@code FAILED} after exactly
     * one attempt, the pre-redelivery behaviour, and the operator's explicit
     * choice. A {@code FAILED} row is excluded from both the claim query's
     * {@code PENDING} branch and its stale-{@code PROCESSING} reclaim branch,
     * so it is never picked up again on its own; an operator restores it with
     * the same rescue statement documented for legacy {@code FAILED} rows in
     * {@code docs/configuration.md}. If this write itself fails, the row stays
     * {@code PROCESSING} with {@code attempts = 1} from the claim, and
     * {@link #retireStaleClaims} parks it in {@code DEAD_LETTER} (outcome
     * unknown — not {@code FAILED}) without a second invocation — "exactly one
     * attempt" holds under that double fault too.
     */
    private void recordSingleAttemptFailure(String table, UUID rowId, int attempts,
                                            String workflowId, Exception failure) {
        logger.error("Message {} for workflow '{}' in {} failed on its only attempt "
                        + "(redelivery disabled) — marking FAILED: {}",
                rowId, workflowId, table, failure.getMessage(), failure);

        var sql = "UPDATE " + table + " SET status = 'FAILED', attempts = ?, "
                + "last_error = ?, processed_at = now() WHERE id = ?";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, attempts);
            stmt.setString(2, describe(failure));
            stmt.setObject(3, rowId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // The row stays PROCESSING; the claim already counted this attempt.
            logger.warn("Failed to record delivery failure for row {} in {} — leaving it "
                            + "PROCESSING; the stale reclaim re-invokes it only if attempt "
                            + "budget remains; otherwise it is retired: {}",
                    rowId, table, e.getMessage(), e);
        }
    }

    private static String describe(Exception failure) {
        var text = failure.toString();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    // ── Dead letters ────────────────────────────────────────────────────

    /**
     * Lists signals parked in {@code DEAD_LETTER} for a service, oldest first.
     *
     * <p>The plain-SQL equivalent, for operators without an application handy:
     * <pre>{@code
     * SELECT id, workflow_id, signal_name, attempts, last_error, created_at
     *   FROM maestro_signal_queue
     *  WHERE service_name = ? AND status = 'DEAD_LETTER'
     *  ORDER BY created_at LIMIT ?;
     * }</pre>
     *
     * @param serviceName the service the signals were addressed to
     * @param limit       maximum rows to return
     * @return the parked signals, oldest first
     */
    public List<DeadLetterMessage> listDeadLetterSignals(String serviceName, int limit) {
        var sql = """
                SELECT id, workflow_id, signal_name AS name, payload, attempts, last_error, created_at
                FROM maestro_signal_queue
                WHERE service_name = ? AND status = 'DEAD_LETTER'
                ORDER BY created_at
                LIMIT ?
                """;
        return listDeadLetters(sql, serviceName, limit);
    }

    /**
     * Lists tasks parked in {@code DEAD_LETTER} on a queue, oldest first.
     *
     * @param taskQueue the queue the tasks were published to
     * @param limit     maximum rows to return
     * @return the parked tasks, oldest first
     */
    public List<DeadLetterMessage> listDeadLetterTasks(String taskQueue, int limit) {
        var sql = """
                SELECT id, workflow_id, workflow_type AS name, payload, attempts, last_error, created_at
                FROM maestro_task_queue
                WHERE task_queue = ? AND status = 'DEAD_LETTER'
                ORDER BY created_at
                LIMIT ?
                """;
        return listDeadLetters(sql, taskQueue, limit);
    }

    /**
     * Returns a parked signal to the queue for another round of delivery.
     *
     * <p>The attempt budget is reset, so replay after the underlying fault is
     * fixed behaves exactly like a fresh publish.
     *
     * @param id the queue row ID, from {@link #listDeadLetterSignals}
     * @return {@code true} if a {@code DEAD_LETTER} row was replayed,
     *         {@code false} if there was no such row
     */
    public boolean replaySignal(UUID id) {
        return replay("maestro_signal_queue", id, SIGNAL_CHANNEL);
    }

    /**
     * Returns a parked task to the queue for another round of delivery.
     *
     * @param id the queue row ID, from {@link #listDeadLetterTasks}
     * @return {@code true} if a {@code DEAD_LETTER} row was replayed,
     *         {@code false} if there was no such row
     */
    public boolean replayTask(UUID id) {
        return replay("maestro_task_queue", id, TASK_CHANNEL);
    }

    private List<DeadLetterMessage> listDeadLetters(String sql, String qualifier, int limit) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qualifier);
            stmt.setInt(2, limit);
            try (var rs = stmt.executeQuery()) {
                var messages = new ArrayList<DeadLetterMessage>();
                while (rs.next()) {
                    messages.add(new DeadLetterMessage(
                            rs.getObject("id", UUID.class),
                            rs.getString("workflow_id"),
                            rs.getString("name"),
                            deserializeNullable(rs.getString("payload")),
                            rs.getInt("attempts"),
                            rs.getString("last_error"),
                            rs.getTimestamp("created_at").toInstant()));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list dead-lettered messages", e);
        }
    }

    private boolean replay(String table, UUID id, String channelBase) {
        var qualifierColumn = TASK_CHANNEL.equals(channelBase) ? "task_queue" : "service_name";
        var sql = "UPDATE " + table + " SET status = 'PENDING', attempts = 0, "
                + "next_attempt_at = now(), last_error = NULL, processed_at = NULL "
                + "WHERE id = ? AND status = 'DEAD_LETTER' RETURNING " + qualifierColumn;
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                var qualifier = rs.getString(1);
                notifyChannel(channelBase, qualifier);
                logger.info("Replayed dead-lettered message {} from {}", id, table);
                return true;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to replay dead-lettered message " + id + " from " + table, e);
        }
    }

    // ── Deserialization helpers ──────────────────────────────────────────

    private TaskMessage readTaskMessage(ResultSet rs) throws SQLException {
        return new TaskMessage(
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_id"),
                rs.getString("workflow_type"),
                rs.getObject("run_id", UUID.class),
                rs.getString("service_name"),
                deserializeNullable(rs.getString("payload"))
        );
    }

    private SignalMessage readSignalMessage(ResultSet rs) throws SQLException {
        return new SignalMessage(
                rs.getString("workflow_id"),
                rs.getString("signal_name"),
                deserializeNullable(rs.getString("payload"))
        );
    }

    // ── Serialization helpers ───────────────────────────────────────────

    private @Nullable String serializeNullable(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize JsonNode", e);
        }
    }

    private @Nullable JsonNode deserializeNullable(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            logger.warn("Failed to deserialize JSON payload: {}", e.getMessage());
            return null;
        }
    }

    // ── NOTIFY helper ───────────────────────────────────────────────────

    private void notifyChannel(String channelBase, String qualifier) {
        var channel = PostgresNotificationListener.sanitizeChannel(channelBase + "_" + qualifier);
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("NOTIFY " + channel);
        } catch (SQLException e) {
            logger.debug("Failed to NOTIFY on channel '{}': {}", channel, e.getMessage());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
