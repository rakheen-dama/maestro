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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 *       if processing fails after status update.</li>
 *   <li>Message ordering is guaranteed <b>per queue</b> by
 *       {@code created_at} ordering.</li>
 * </ul>
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

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final PostgresNotificationListener notificationListener;
    private final CopyOnWriteArrayList<Thread> pollingThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new PostgreSQL-based workflow messaging implementation.
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
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.notificationListener = notificationListener;
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
        var claimSql = """
                UPDATE maestro_task_queue
                SET status = 'PROCESSING', processed_at = now()
                WHERE id = (
                    SELECT id FROM maestro_task_queue
                    WHERE task_queue = ?
                      AND (status = 'PENDING'
                           OR (status = 'PROCESSING' AND processed_at < now() - interval '5 minutes'))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, task_queue, workflow_instance_id, workflow_id, workflow_type,
                          run_id, service_name, payload
                """;

        while (running.get()) {
            try {
                var processed = false;
                try (var conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.prepareStatement(claimSql)) {
                        stmt.setString(1, taskQueue);
                        try (var rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                processed = true;
                                var rowId = rs.getObject("id", UUID.class);
                                var message = readTaskMessage(rs);
                                conn.commit();
                                processTaskMessage(rowId, message, handler);
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
        var claimSql = """
                UPDATE maestro_signal_queue
                SET status = 'PROCESSING', processed_at = now()
                WHERE id = (
                    SELECT id FROM maestro_signal_queue
                    WHERE service_name = ?
                      AND (status = 'PENDING'
                           OR (status = 'PROCESSING' AND processed_at < now() - interval '5 minutes'))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, service_name, workflow_id, signal_name, payload
                """;

        while (running.get()) {
            try {
                var processed = false;
                try (var conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.prepareStatement(claimSql)) {
                        stmt.setString(1, serviceName);
                        try (var rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                processed = true;
                                var rowId = rs.getObject("id", UUID.class);
                                var message = readSignalMessage(rs);
                                conn.commit();
                                processSignalMessage(rowId, message, handler);
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

    private void processTaskMessage(UUID rowId, TaskMessage message,
                                    Consumer<TaskMessage> handler) {
        try {
            handler.accept(message);
            updateStatus("maestro_task_queue", rowId, "COMPLETED");
        } catch (Exception e) {
            logger.error("Error processing task message {} for workflow '{}': {}",
                    rowId, message.workflowId(), e.getMessage(), e);
            updateStatus("maestro_task_queue", rowId, "FAILED");
        }
    }

    private void processSignalMessage(UUID rowId, SignalMessage message,
                                      Consumer<SignalMessage> handler) {
        try {
            handler.accept(message);
            updateStatus("maestro_signal_queue", rowId, "COMPLETED");
        } catch (Exception e) {
            logger.error("Error processing signal message {} for workflow '{}': {}",
                    rowId, message.workflowId(), e.getMessage(), e);
            updateStatus("maestro_signal_queue", rowId, "FAILED");
        }
    }

    private void updateStatus(String table, UUID rowId, String status) {
        var sql = "UPDATE " + table + " SET status = ?, processed_at = now() WHERE id = ?";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setObject(2, rowId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to update status to '{}' for row {} in {}: {}",
                    status, rowId, table, e.getMessage(), e);
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
