package io.b2mash.maestro.store.postgres.notify;

import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * of this listener and only returned by {@link #close()}. Size the pool for
 * it: the application has one connection fewer than its maximum, and a Hikari
 * {@code leakDetectionThreshold} will report this connection as leaked — that
 * report is a false positive. When the session fails (Postgres restart,
 * failover, idle kill) the connection is closed and a new one borrowed with
 * exponential backoff from 1 s to 30 s, re-issuing the {@code LISTEN}; wakes
 * published in that gap are covered by the engine's
 * {@code maestro.signal.wake-recheck-interval} store re-read. {@link #start()}
 * never throws: if the first connection cannot be had the poll loop keeps
 * trying with the same backoff.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #register}/{@link #unregister} may be called from any
 * thread; registered consumers are invoked on the listener's own virtual
 * thread, so they must be quick and non-blocking. The JDBC connection is
 * touched only by that thread (and by {@link #close()}, which first stops it).
 */
public final class PostgresWakeListener implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresWakeListener.class);

    /** How long one {@code getNotifications} call blocks before the loop re-checks its state. */
    private static final int POLL_TIMEOUT_MS = 500;

    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long CLOSE_JOIN_MS = 2_000;

    private final DataSource dataSource;
    private final String channel;
    private final Map<String, Consumer<String>> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();

    private volatile @Nullable Connection connection;
    private volatile @Nullable PGConnection pgConnection;
    private volatile @Nullable Thread pollingThread;

    /**
     * @param dataSource the pool to borrow the dedicated LISTEN connection from
     * @param channel    the channel to LISTEN on — {@link WakeChannel#channelFor}
     */
    public PostgresWakeListener(DataSource dataSource, String channel) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /** @return the channel this listener LISTENs on */
    public String channel() {
        return channel;
    }

    /**
     * Borrows the connection, issues the {@code LISTEN} and starts the polling
     * thread. Idempotent. Never throws: a failure to connect is logged at
     * {@code WARN} and the polling thread keeps retrying with backoff.
     */
    public void start() {
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
        pollingThread = Thread.ofVirtual().name("maestro-pg-wake-listener").start(this::pollLoop);
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
     * Stops the polling thread, {@code UNLISTEN}s (best-effort) and returns the
     * connection to the pool. Joins the thread for at most two seconds.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        var thread = pollingThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(CLOSE_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        var conn = connection;
        if (conn != null) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("UNLISTEN *");
            } catch (SQLException e) {
                logger.debug("UNLISTEN * on close failed (ignored): {}", e.getMessage());
            }
        }
        closeConnection();
        consumers.clear();
        logger.info("Postgres wake listener on channel '{}' closed", channel);
    }

    // ── Internal ────────────────────────────────────────────────────────

    private void pollLoop() {
        var backoff = INITIAL_BACKOFF_MS;
        while (running.get()) {
            try {
                var pg = pgConnection;
                if (pg == null) {
                    Thread.sleep(backoff);
                    if (!running.get()) {
                        break;
                    }
                    if (!connect()) {
                        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                        continue;
                    }
                    reconnects.incrementAndGet();
                    logger.info("Re-established the Postgres LISTEN session on channel '{}'", channel);
                    pg = pgConnection;
                    if (pg == null) {
                        continue;
                    }
                }
                backoff = INITIAL_BACKOFF_MS;
                var notifications = pg.getNotifications(POLL_TIMEOUT_MS);
                if (notifications != null) {
                    for (PGNotification notification : notifications) {
                        dispatch(notification);
                    }
                }
            } catch (SQLException e) {
                if (running.get()) {
                    logger.warn("Postgres LISTEN session on channel '{}' failed ({}); reconnecting",
                            channel, e.getMessage());
                    closeConnection();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                // A driver or dispatch bug must never end the poll thread: the listener
                // would then look connected while delivering nothing until restart.
                if (running.get()) {
                    logger.warn("Postgres wake poll loop on channel '{}' hit an unexpected error ({}); "
                            + "continuing", channel, e.toString());
                }
            }
        }
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
}
