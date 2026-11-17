package io.b2mash.maestro.store.postgres.notify;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Holds this node's one {@code LISTEN} session on the service's wake channel
 * and dispatches each notification to the workflow it names.
 *
 * <p>Exactly one {@code LISTEN} is issued, at {@link #start()}, on the channel
 * {@link WakeChannel#channelFor} derives from the service name. Registering
 * interest in a workflow ({@link #register}) is therefore a map put and
 * nothing else — no round trip, no command queue, no waiting for the session
 * to catch up — so a caller that re-reads its source of truth right after
 * registering (as {@code SignalManager} does after {@code subscribe}) is
 * guaranteed that any wake published from then on is either caught by the
 * re-read or delivered here.
 *
 * <h2>Connection</h2>
 * <p>One connection is borrowed from the {@link DataSource} for the lifetime
 * of this listener and returned only when the listener stops. Size the pool
 * for it: the application has one connection fewer than its maximum, and a Hikari
 * {@code leakDetectionThreshold} will report this connection as leaked — that
 * report is a false positive. When the session fails (Postgres restart,
 * failover, idle kill) the connection is closed and a new one borrowed with
 * exponential backoff from 1 s to 30 s, re-issuing the {@code LISTEN}; wakes
 * published in that gap are covered by the engine's
 * {@code maestro.signal.wake-recheck-interval} store re-read. {@link #start()}
 * never throws: if the first connection cannot be had the poll loop keeps
 * trying with the same backoff.
 *
 * <p>At {@link #close()} the poll thread is stopped without an interrupt (an
 * interrupt of a virtual thread blocked in a socket read closes the socket
 * under the pool, Issue 38). It leaves its loop within one poll, puts a
 * one-second network timeout on the connection, issues {@code UNLISTEN *} and
 * closes it, which hands it back to the pool listening on nothing. A session
 * that has failed with an {@code SQLException} is closed without
 * {@code UNLISTEN}.
 *
 * <h2>Failures</h2>
 * <p>A consumer that throws — a {@code RuntimeException} or an {@code Error}
 * — is logged at {@code ERROR} and contained per notification: the rest of
 * the batch is delivered at once. A {@code RuntimeException} or an
 * {@code Error} from one poll does not end the poll thread, and a fault that
 * repeats on every poll cannot spin it. A {@code RuntimeException} is logged
 * at {@code WARN} and the session is dropped: released as at close (the
 * one-second network timeout, {@code UNLISTEN *} — whose failure is ignored —
 * the previous timeout restored, then closed, with the interrupt flag clear),
 * because the connection itself may be healthy, then reconnected after the
 * back-off, which starts at one second and doubles up to 30 s for as long as
 * each new session fails before a poll returns. A {@code close()} during that
 * release still returns within its join: the release is bounded by the
 * network timeout and the loop ends right after it. An {@code Error}
 * is logged at {@code ERROR} and the loop polls the same session again one
 * second later. A
 * notification batch that a poll-side failure cuts short loses its remaining
 * wakes to the engine's {@code maestro.signal.wake-recheck-interval} re-read.
 * Only a {@code MaestroControlFlowError} ends the thread; the connection is
 * still released and the end is logged at {@code ERROR}.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #register}/{@link #unregister} may be called from any
 * thread; registered consumers are invoked on the listener's own virtual
 * thread, so they must be quick and non-blocking. The JDBC connection is
 * touched only by the poll thread once {@link #start()} returns; {@link #close()}
 * never touches it, so no connection is ever used by two threads at once.
 */
public final class PostgresWakeListener implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresWakeListener.class);

    /** How long one {@code getNotifications} call blocks before the loop re-checks its state. */
    private static final int POLL_TIMEOUT_MS = 500;

    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    /**
     * The network timeout put on the connection before its {@code UNLISTEN *},
     * so a half-open TCP connection cannot hold the poll thread past it.
     */
    private static final int UNLISTEN_TIMEOUT_MS = 1_000;

    /**
     * How long {@link #close()} waits for the poll thread. It must cover one
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
    /** Counted down by {@link #close()}: ends a back-off wait without an interrupt. */
    private final CountDownLatch stop = new CountDownLatch(1);
    /**
     * Serialises the state transitions of {@link #start()} and {@link #close()},
     * so a start can never slip in after a close has begun. Never held across
     * the join in {@code close()}.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    private volatile @Nullable Connection connection;
    private volatile @Nullable PGConnection pgConnection;
    private volatile @Nullable Thread pollingThread;

    /**
     * @param dataSource the pool to borrow the dedicated LISTEN connection from
     * @param channel    the channel to LISTEN on — {@link WakeChannel#channelFor}
     */
    public PostgresWakeListener(DataSource dataSource, String channel) {
        this(dataSource, channel, CLOSE_JOIN);
    }

    /**
     * Test seam: {@code closeJoin} replaces the two-second bound on how long
     * {@link #close()} waits for the poll thread.
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
     * Borrows the connection, issues the {@code LISTEN} and starts the polling
     * thread. Idempotent. Never throws: a failure to connect is logged at
     * {@code WARN} and the polling thread keeps retrying with backoff.
     *
     * <p>{@link #close()} is terminal: a listener that was closed cannot be
     * started again, and a {@code start()} after it does nothing. Create a new
     * listener instead (Spring does, one per context).
     */
    public void start() {
        lifecycle.lock();
        try {
            if (stop.getCount() == 0) {
                logger.debug("start() ignored: the wake listener on channel '{}' was closed", channel);
                return;
            }
            if (!running.compareAndSet(false, true)) {
                return;
            }
            if (connect()) {
                logger.info("Listening for signal wakes on Postgres channel '{}'", channel);
            } else {
                logger.warn("Could not open the Postgres LISTEN session on channel '{}' — retrying in the "
                        + "background; until it connects, cross-node wakes fall back to "
                        + "maestro.signal.wake-recheck-interval", channel);
            }
            pollingThread = Thread.ofVirtual()
                    .name("maestro-pg-wake-listener")
                    .uncaughtExceptionHandler((th, e) -> {
                        if (running.get()) {
                            logger.error("Postgres wake listener on channel '{}' stopped: {} escaped it — until "
                                    + "the node restarts, cross-node wakes fall back to "
                                    + "maestro.signal.wake-recheck-interval", channel, e.toString(), e);
                        } else {
                            logger.debug("Postgres wake listener on channel '{}' exited while stopping: {}",
                                    channel, e.toString());
                        }
                    })
                    .start(this::pollLoop);
        } finally {
            lifecycle.unlock();
        }
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
     * Stops the poll thread without interrupting it, and waits for it for at
     * most two seconds. The poll thread leaves within one poll (500 ms),
     * issues {@code UNLISTEN *} and returns its connection to the pool. If it
     * has not left in time, this method returns anyway and the thread releases
     * the connection when its current database call returns. Idempotent, and
     * terminal even for a listener that was never started. A {@code close()}
     * concurrent with a {@code start()} waits for that start's connect attempt.
     */
    @Override
    public void close() {
        Thread thread;
        lifecycle.lock();
        try {
            stop.countDown();
            if (!running.compareAndSet(true, false)) {
                return;
            }
            thread = pollingThread;
        } finally {
            lifecycle.unlock();
        }
        var stopped = true;
        if (thread != null) {
            try {
                stopped = thread.join(closeJoin);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = false;
            }
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

    // ── Internal ────────────────────────────────────────────────────────

    private void pollLoop() {
        try {
            var backoff = INITIAL_BACKOFF_MS;
            while (running.get()) {
                try {
                    var pg = pgConnection;
                    if (pg == null) {
                        if (awaitStop(backoff)) {
                            break;
                        }
                        if (!running.get()) {
                            break;
                        }
                        var connected = connect();
                        // Doubled on every reconnect attempt and reset only by a poll that
                        // returned, so a session that connects but fails again at once backs
                        // off too, not only a refused connection.
                        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                        if (!connected) {
                            continue;
                        }
                        reconnects.incrementAndGet();
                        logger.info("Re-established the Postgres LISTEN session on channel '{}'", channel);
                        pg = pgConnection;
                        if (pg == null) {
                            continue;
                        }
                    }
                    var notifications = pg.getNotifications(POLL_TIMEOUT_MS);
                    backoff = INITIAL_BACKOFF_MS;
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            dispatch(notification);
                        }
                    }
                } catch (SQLException e) {
                    if (running.get()) {
                        logger.warn("Postgres LISTEN session on channel '{}' failed ({}); reconnecting",
                                channel, e.getMessage());
                    }
                    // A failed session: closed without UNLISTEN, which would only fail again.
                    closeConnection();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    // A driver or dispatch bug must never end the poll thread: the listener
                    // would then look connected while delivering nothing until restart.
                    // Nor may it spin, or retry the same suspect session every second for
                    // ever: the session is dropped and the reconnect path's back-off, 1 s
                    // doubling to 30 s while new sessions keep failing, comes before the
                    // next poll. Unlike an SQLException, a RuntimeException says nothing
                    // about the connection itself, which may be healthy: it is released as
                    // at close (UNLISTEN * bounded by the network timeout, any failure of
                    // it ignored, then closed), so the pool never gets it back listening.
                    if (running.get()) {
                        logger.warn("Postgres wake poll loop on channel '{}' hit an unexpected error ({}); "
                                + "dropping the session and reconnecting", channel, e.toString());
                    }
                    releaseWithInterruptCleared();
                } catch (Error e) {
                    // The engine's control-flow rule: rethrown, it ends the loop (the
                    // finally still releases the connection). No consumer throws one in
                    // production; the rule is kept so that one never could be swallowed.
                    if (e instanceof MaestroControlFlowError controlFlow) {
                        throw controlFlow;
                    }
                    // Any other Error — a driver's LinkageError, an OutOfMemoryError —
                    // is contained like a RuntimeException. A consumer's Error never gets
                    // here (dispatch contains it per notification); wakes left in a batch
                    // that a driver or decode Error cut short are covered by
                    // maestro.signal.wake-recheck-interval, as any lost NOTIFY is.
                    if (running.get()) {
                        logger.error("Postgres wake poll loop on channel '{}' hit {}; continuing in {} ms",
                                channel, e.toString(), INITIAL_BACKOFF_MS, e);
                    }
                    if (pauseAfterFailure()) {
                        break;
                    }
                }
            }
        } finally {
            releaseWithInterruptCleared();
        }
    }

    /**
     * {@link #releaseConnection()} with the poll thread's interrupt flag
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
     * The wait after an unexpected failure, so a fault that repeats on every
     * poll cannot spin the thread: {@link #INITIAL_BACKOFF_MS}, cut short by
     * {@link #close()}.
     *
     * @return {@code true} if the loop must end — closed during the wait, or
     *         interrupted (the flag is re-asserted)
     */
    private boolean pauseAfterFailure() {
        try {
            return awaitStop(INITIAL_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * Waits for {@link #close()} for at most {@code millis}.
     *
     * @return {@code true} if the listener was closed during the wait
     */
    private boolean awaitStop(long millis) throws InterruptedException {
        return stop.await(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Borrows a connection and issues the {@code LISTEN}. Any failure — a
     * refused connection, a driver that is not pgjdbc, a null from a stub pool
     * — is logged and leaves the listener disconnected.
     *
     * @return {@code true} if the session is listening
     */
    private synchronized boolean connect() {
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
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.debug("Opening the Postgres LISTEN session on channel '{}' failed: {}", channel, e.toString());
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException | RuntimeException ignored) {
                    // nothing more to release
                }
            }
            return false;
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
     * Runs on the poll thread as it ends, and when a poll threw a
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
