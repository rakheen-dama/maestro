package io.b2mash.maestro.store.postgres.notify;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.loop.Backoff;
import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Holds this node's one {@code LISTEN} session on the service's wake channel
 * and dispatches each notification to the workflow it names.
 *
 * <p>Exactly one {@code LISTEN} is issued per session, on the channel
 * {@link WakeChannel#channelFor} derives from the service name. Registering
 * interest in a workflow ({@link #register}) is therefore a map put and
 * nothing else — no round trip, no command queue, no waiting for the session
 * to catch up — so a caller that re-reads its source of truth right after
 * registering (as {@code SignalManager} does after {@code subscribe}) is
 * guaranteed that any wake published from then on is either caught by the
 * re-read or delivered here.
 *
 * <h2>Supervision</h2>
 * <p>The poll loop is the supervised loop {@value #LOOP_NAME} (role
 * optimisation; P2 task 6, Issue 65): one iteration is one poll of up to
 * 500 ms and the dispatch of what it returned, followed at once by the next.
 * {@link #start(WorkflowExecutor)} runs it under the executor's
 * {@code LoopSupervisor}, so it is one of {@link WorkflowExecutor#loops()} —
 * the {@code maestro} health contributor shows {@code wakeListenerRunning} and
 * {@code wakeListenerConnected}, and names it in {@code degraded} once it has
 * failed twice in a row over at least the executor's wake recheck interval —
 * its {@code Error}s count on {@code maestro.loop.errors{loop=wake-listener}},
 * and the executor's shutdown stops it. A lost session costs latency, never
 * correctness: the recheck is the fallback, so it never reads {@code DOWN}
 * unless the loop is dead. {@link #start()} runs it under a supervisor of its
 * own, with no observer.
 *
 * <h2>Connection</h2>
 * <p>One connection is borrowed from the {@link DataSource} for the lifetime
 * of this listener and returned only when the listener stops. Size the pool
 * for it: the application has one connection fewer than its maximum, and a Hikari
 * {@code leakDetectionThreshold} will report this connection as leaked — that
 * report is a false positive. When the session fails (Postgres restart,
 * failover, idle kill) the connection is closed and a new one borrowed after
 * the supervisor's back-off — one second, doubling to 30 s for as long as each
 * new session fails before a poll returns — re-issuing the {@code LISTEN};
 * wakes published in that gap are covered by the engine's
 * {@code maestro.signal.wake-recheck-interval} store re-read. {@code start}
 * never throws for a connection: if the first one cannot be had, the loop
 * tries again one second later, with the same back-off.
 *
 * <p>At {@link #close()} the loop is stopped without an interrupt (an
 * interrupt of a virtual thread blocked in a socket read closes the socket
 * under the pool, Issue 38). It leaves within one poll and, as it ends
 * ({@link #onExit()}), puts a one-second network timeout on the connection,
 * issues {@code UNLISTEN *} and closes it, which hands it back to the pool
 * listening on nothing. A session that has failed with an
 * {@code SQLException} is closed without {@code UNLISTEN}.
 *
 * <h2>Failures</h2>
 * <p>A consumer that throws — a {@code RuntimeException} or an {@code Error}
 * — is logged at {@code ERROR} and contained per notification: the rest of
 * the batch is delivered at once. Anything a poll throws is contained by the
 * supervisor, counted in the loop's failure streak and followed by its
 * back-off, so a fault that repeats on every poll can neither spin the thread
 * nor flood the log: the supervisor logs a streak once at {@code WARN} (an
 * optimisation loop: a routine failover must not page), then a {@code WARN}
 * reminder at most every five minutes. An {@code SQLException} drops the session
 * (closed); a {@code RuntimeException} drops it too, released as at close (the
 * one-second network timeout, {@code UNLISTEN *} — whose failure is ignored —
 * the previous timeout restored, then closed, with the interrupt flag clear),
 * because the connection itself may be healthy; an {@code Error} keeps the
 * session, which it says nothing about. A {@code close()} during that release
 * still returns within its join: the release is bounded by the network timeout
 * and the loop ends right after it. A notification batch that a poll-side
 * failure cuts short loses its remaining wakes to the engine's
 * {@code maestro.signal.wake-recheck-interval} re-read. Only a
 * {@code MaestroControlFlowError} ends the loop; the connection is still
 * released and the supervisor logs the end at {@code ERROR}.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #register}/{@link #unregister} may be called from any
 * thread; registered consumers are invoked on the listener's own virtual
 * thread, so they must be quick and non-blocking. The JDBC connection is
 * touched only by the loop's thread once {@code start} returns;
 * {@link #close()} never touches it, so no connection is ever used by two
 * threads at once.
 */
public final class PostgresWakeListener implements AutoCloseable, LoopBody {

    /** The loop's name: the {@code loop} tag of its meters and its name in health. */
    public static final String LOOP_NAME = "wake-listener";

    /** The note the loop keeps: whether a {@code LISTEN} session is open. */
    public static final String CONNECTED = "connected";

    private static final Logger logger = LoggerFactory.getLogger(PostgresWakeListener.class);

    /** How long one {@code getNotifications} call blocks before the loop re-checks its state. */
    private static final int POLL_TIMEOUT_MS = 500;

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /**
     * How long the listener may fail before it reads failing when no executor
     * says otherwise ({@link #start()}): the default
     * {@code maestro.signal.wake-recheck-interval}.
     */
    private static final Duration DEFAULT_FAILING_AFTER = Duration.ofSeconds(30);

    /**
     * The network timeout put on the connection before its {@code UNLISTEN *},
     * so a half-open TCP connection cannot hold the loop's thread past it.
     */
    private static final int UNLISTEN_TIMEOUT_MS = 1_000;

    /**
     * How long {@link #close()} waits for the loop's thread. It must cover one
     * poll, the {@code UNLISTEN *} and some slack:
     * {@code CLOSE_JOIN >= POLL_TIMEOUT_MS + UNLISTEN_TIMEOUT_MS + slack}
     * (0.5 s + 1 s + 0.5 s).
     */
    private static final Duration CLOSE_JOIN = Duration.ofSeconds(2);

    private final DataSource dataSource;
    private final String channel;
    private final Duration closeJoin;
    private final Map<String, Consumer<String>> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    /**
     * Serialises the state transitions of {@code start} and {@link #close()},
     * so a start can never slip in after a close has begun. Never held across
     * the join in {@code close()}.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    private volatile boolean closed;
    private volatile @Nullable Connection connection;
    private volatile @Nullable PGConnection pgConnection;
    private volatile @Nullable LoopSupervisor supervisor;
    /** The spec the loop was started with; read by tests. */
    private volatile @Nullable LoopSpec startedSpec;
    /** The last {@link #CONNECTED} note; the loop's thread only. */
    private @Nullable Boolean notedConnected;

    /**
     * @param dataSource the pool to borrow the dedicated LISTEN connection from
     * @param channel    the channel to LISTEN on — {@link WakeChannel#channelFor}
     */
    public PostgresWakeListener(DataSource dataSource, String channel) {
        this(dataSource, channel, CLOSE_JOIN);
    }

    /**
     * Test seam: {@code closeJoin} replaces the two-second bound on how long
     * {@link #close()} waits for the loop's thread.
     */
    PostgresWakeListener(DataSource dataSource, String channel, Duration closeJoin) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.closeJoin = Objects.requireNonNull(closeJoin, "closeJoin");
    }

    /** @return the channel this listener LISTENs on */
    public String channel() {
        return channel;
    }

    /**
     * Borrows the connection, issues the {@code LISTEN} and starts the poll
     * loop under {@code executor}'s supervisor: in its health, its meters and
     * its shutdown; failing once it has failed twice in a row over at least
     * {@link WorkflowExecutor#wakeRecheckInterval()}. Idempotent. A failure to
     * connect is logged at {@code WARN} and the loop keeps retrying with
     * back-off.
     *
     * <p>{@link #close()} is terminal: a listener that was closed cannot be
     * started again, and a start after it does nothing. Create a new listener
     * instead (Spring does, one per context).
     *
     * @param executor the executor whose supervisor runs the loop
     * @throws IllegalStateException if the executor is shutting down; the
     *                               connection is returned first
     */
    public void start(WorkflowExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        startWith(spec -> executor.supervise(spec, this), executor.wakeRecheckInterval());
    }

    /**
     * As {@link #start(WorkflowExecutor)}, under a supervisor of the
     * listener's own, with no observer: failing after 30 s. Never throws.
     */
    public void start() {
        startWith(spec -> LoopSupervisor.start(spec, this, EngineObserver.NOOP), DEFAULT_FAILING_AFTER);
    }

    private void startWith(Function<LoopSpec, LoopSupervisor> starter, Duration failingAfter) {
        lifecycle.lock();
        try {
            if (closed) {
                logger.debug("start() ignored: the wake listener on channel '{}' was closed", channel);
                return;
            }
            if (!running.compareAndSet(false, true)) {
                return;
            }
            var connected = connect();
            if (connected) {
                logger.info("Listening for signal wakes on Postgres channel '{}'", channel);
            } else {
                logger.warn("Could not open the Postgres LISTEN session on channel '{}' — retrying in the "
                        + "background; until it connects, cross-node wakes fall back to "
                        + "maestro.signal.wake-recheck-interval", channel);
            }
            // A failed first connect waits the first back-off before the loop tries again.
            var spec = LoopSpec.of(LOOP_NAME, "maestro-pg-wake-listener", LoopRole.OPTIMISATION,
                            Duration.ofMillis(POLL_TIMEOUT_MS))
                    .withInitialDelay(connected ? Duration.ZERO : INITIAL_BACKOFF)
                    .withBackoff(Backoff.exponential(INITIAL_BACKOFF, MAX_BACKOFF))
                    .withFailing(FailingRule.streakFor(2, failingAfter));
            startedSpec = spec;
            try {
                supervisor = starter.apply(spec);
            } catch (RuntimeException | Error e) {
                running.set(false);
                releaseWithInterruptCleared();
                throw e;
            }
        } finally {
            lifecycle.unlock();
        }
    }

    /** @return the spec the loop was started with, or {@code null} before a start; for tests */
    @Nullable LoopSpec startedSpec() {
        return startedSpec;
    }

    /**
     * Routes future wakes for {@code workflowId} to {@code onSignal}. Replaces
     * any earlier consumer for the same ID. In-memory only — no I/O.
     *
     * @param workflowId the workflow to watch
     * @param onSignal   receives the signal name of each wake, on the listener thread
     */
    public void register(String workflowId, Consumer<String> onSignal) {
        consumers.put(workflowId, onSignal);
    }

    /**
     * Stops routing wakes for {@code workflowId}. No-op if none is registered.
     * In-memory only — no I/O.
     *
     * @param workflowId the workflow to stop watching
     */
    public void unregister(String workflowId) {
        consumers.remove(workflowId);
    }

    /** @return how many notifications reached a registered consumer */
    public long dispatched() {
        return dispatched.get();
    }

    /** @return how many times the LISTEN session was re-established after a failure */
    public long reconnects() {
        return reconnects.get();
    }

    /** @return whether a LISTEN session is currently open */
    public boolean isConnected() {
        return pgConnection != null;
    }

    /**
     * Stops the loop without interrupting it, and waits for it for at most
     * two seconds. The loop leaves within one poll (500 ms), issues
     * {@code UNLISTEN *} and returns its connection to the pool. If it has not
     * left in time, this method returns anyway and the loop releases the
     * connection when its current database call returns. Idempotent, and
     * terminal even for a listener that was never started. A {@code close()}
     * concurrent with a start waits for that start's connect attempt.
     */
    @Override
    public void close() {
        LoopSupervisor loop;
        lifecycle.lock();
        try {
            closed = true;
            if (!running.compareAndSet(true, false)) {
                return;
            }
            loop = supervisor;
        } finally {
            lifecycle.unlock();
        }
        var stopped = true;
        if (loop != null) {
            loop.requestStop();
            stopped = loop.awaitStopped(closeJoin);
        }
        if (stopped) {
            logger.info("Postgres wake listener on channel '{}' closed", channel);
        } else {
            logger.info("Postgres wake listener on channel '{}' did not stop within {} ms (its poll thread is "
                    + "still in a database call); it releases its connection when that call returns",
                    channel, closeJoin.toMillis());
        }
        consumers.clear();
    }

    // ── The loop ────────────────────────────────────────────────────────

    /**
     * One poll: reconnects if the session was dropped, waits up to 500 ms for
     * notifications and dispatches them.
     *
     * @param ctx the loop's context
     * @return poll again at once
     * @throws SQLException a connect or poll that failed; the session is closed
     */
    @Override
    public Next iterate(LoopContext ctx) throws SQLException {
        try {
            var pg = pgConnection;
            if (pg == null) {
                pg = open();
                reconnects.incrementAndGet();
                logger.info("Re-established the Postgres LISTEN session on channel '{}'", channel);
            }
            noteConnected(ctx, true);
            var notifications = pg.getNotifications(POLL_TIMEOUT_MS);
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    dispatch(notification);
                }
            }
            return Next.immediately();
        } catch (SQLException e) {
            // A failed session: closed without UNLISTEN, which would only fail again.
            closeConnection();
            throw e;
        } catch (RuntimeException e) {
            // A driver or dispatch bug says nothing about the connection itself,
            // which may be healthy: it is released as at close (UNLISTEN * bounded
            // by the network timeout, any failure of it ignored, then closed), so
            // the pool never gets it back listening. The back-off follows.
            releaseWithInterruptCleared();
            throw e;
        } finally {
            // An Error keeps the session; anything that dropped it shows here.
            noteConnected(ctx, pgConnection != null);
        }
    }

    /** Runs on the loop's thread as it ends: returns the session to the pool listening on nothing. */
    @Override
    public void onExit() {
        releaseWithInterruptCleared();
    }

    private void noteConnected(LoopContext ctx, boolean connected) {
        if (!Boolean.valueOf(connected).equals(notedConnected)) {
            notedConnected = connected;
            ctx.note(CONNECTED, connected);
        }
    }

    /**
     * {@link #releaseConnection()} with the loop thread's interrupt flag
     * cleared, re-asserted afterwards. Never release with the flag set: an
     * interrupted virtual thread entering a socket read (the
     * {@code UNLISTEN *}) closes the socket under the pool (Issue 38).
     */
    private void releaseWithInterruptCleared() {
        var interrupted = Thread.interrupted();
        try {
            releaseConnection();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Borrows a connection and issues the {@code LISTEN}, logging a failure —
     * a refused connection, a driver that is not pgjdbc, a null from a stub
     * pool — and leaving the listener disconnected.
     *
     * @return {@code true} if the session is listening
     */
    private boolean connect() {
        try {
            open();
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.debug("Opening the Postgres LISTEN session on channel '{}' failed: {}", channel, e.toString());
            return false;
        }
    }

    /**
     * Borrows a connection and issues the {@code LISTEN}. On a failure the
     * borrowed connection is closed and the failure thrown.
     *
     * @return the session's {@code PGConnection}
     * @throws SQLException if the connection cannot be had or the {@code LISTEN} fails
     */
    private synchronized PGConnection open() throws SQLException {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            if (conn == null) {
                throw new SQLException("DataSource returned no connection");
            }
            if (!running.get()) {
                // close() ran while we were borrowing: hand the connection straight back.
                throw new SQLException("listener closed during connect");
            }
            conn.setAutoCommit(true);
            var pg = conn.unwrap(PGConnection.class);
            try (var stmt = conn.createStatement()) {
                stmt.execute("LISTEN " + channel);
            }
            connection = conn;
            pgConnection = pg;
            return pg;
        } catch (SQLException | RuntimeException e) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException | RuntimeException ignored) {
                    // nothing more to release
                }
            }
            throw e;
        }
    }

    private void dispatch(PGNotification notification) {
        if (!channel.equals(notification.getName())) {
            logger.debug("Ignoring notification on unexpected channel '{}'", notification.getName());
            return;
        }
        var raw = notification.getParameter();
        WakeChannel.WakePayload payload;
        try {
            payload = WakeChannel.decode(raw != null ? raw : "");
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring malformed wake payload on channel '{}': {}", channel, e.getMessage());
            return;
        }
        var consumer = consumers.get(payload.workflowId());
        if (consumer == null) {
            logger.debug("No subscriber on this node for workflow '{}' (signal '{}'); ignoring wake",
                    payload.workflowId(), payload.signalName());
            return;
        }
        dispatched.incrementAndGet();
        try {
            consumer.accept(payload.signalName());
        } catch (RuntimeException e) {
            logger.error("Wake consumer for workflow '{}' threw: {}", payload.workflowId(), e.getMessage(), e);
        } catch (Error e) {
            // Contained per notification, so one consumer's Error neither drops the
            // rest of this batch nor pauses every other wake for the loop's 1 s.
            // The control-flow rule still holds: rethrown, it ends the loop.
            if (e instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            logger.error("Wake consumer for workflow '{}' threw: {}", payload.workflowId(), e.toString(), e);
        }
    }

    private synchronized void closeConnection() {
        var conn = connection;
        connection = null;
        pgConnection = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException | RuntimeException e) {
                logger.debug("Closing the LISTEN connection failed (ignored): {}", e.getMessage());
            }
        }
    }

    /**
     * Runs on the loop's thread as it ends, and when a poll threw a
     * {@code RuntimeException}: hands a session that may be healthy back to
     * the pool listening on nothing. The network timeout bounds the
     * {@code UNLISTEN *} on a half-open connection; it is put back to its
     * earlier value before the connection is returned, so a pool that does not
     * reset it (Hikari does) never hands out a connection with a one-second
     * read timeout.
     */
    private synchronized void releaseConnection() {
        var conn = connection;
        connection = null;
        pgConnection = null;
        if (conn == null) {
            return;
        }
        Integer previousNetworkTimeout = null;
        try {
            var previous = conn.getNetworkTimeout();
            conn.setNetworkTimeout(Runnable::run, UNLISTEN_TIMEOUT_MS);
            previousNetworkTimeout = previous;
        } catch (SQLException | RuntimeException e) {
            logger.debug("Setting the network timeout before UNLISTEN failed (ignored): {}", e.getMessage());
        }
        try (var stmt = conn.createStatement()) {
            stmt.execute("UNLISTEN *");
        } catch (SQLException | RuntimeException e) {
            logger.debug("UNLISTEN * on close failed (ignored): {}", e.getMessage());
        }
        if (previousNetworkTimeout != null) {
            try {
                conn.setNetworkTimeout(Runnable::run, previousNetworkTimeout);
            } catch (SQLException | RuntimeException e) {
                logger.debug("Restoring the network timeout after UNLISTEN failed (ignored): {}", e.getMessage());
            }
        }
        try {
            conn.close();
        } catch (SQLException | RuntimeException e) {
            logger.debug("Closing the LISTEN connection failed (ignored): {}", e.getMessage());
        }
    }
}
