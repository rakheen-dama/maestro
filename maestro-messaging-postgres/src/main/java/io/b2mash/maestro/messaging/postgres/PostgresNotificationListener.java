package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Manages a dedicated PostgreSQL connection for {@code LISTEN/NOTIFY}
 * notifications.
 *
 * <p>Uses a single long-lived JDBC connection unwrapped to
 * {@link PGConnection} to receive asynchronous notifications. A background
 * virtual thread polls for notifications and dispatches them to registered
 * listeners.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Channel registrations use a
 * {@link ConcurrentHashMap} and the polling loop runs on its own virtual
 * thread. The dedicated connection is only accessed from the polling thread.
 *
 * @see PostgresSignalNotifier
 * @see PostgresWorkflowMessaging
 */
@NullMarked
public final class PostgresNotificationListener implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresNotificationListener.class);

    /**
     * Timeout in milliseconds for polling notifications on the dedicated
     * connection. A moderate value balances responsiveness with resource usage.
     */
    private static final int POLL_TIMEOUT_MS = 500;

    /**
     * How long {@link #listen} waits for the polling thread to actually issue
     * its {@code LISTEN}. Must comfortably exceed {@link #POLL_TIMEOUT_MS},
     * which bounds how long the polling thread can be busy before it drains
     * the command queue.
     */
    private static final long LISTEN_APPLY_TIMEOUT_MS = 5_000;

    private final DataSource dataSource;
    private final Map<String, BiConsumer<String, String>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Connection dedicatedConnection;
    private volatile PGConnection pgConnection;
    private volatile Thread pollingThread;

    /**
     * Creates a new notification listener.
     *
     * @param dataSource the DataSource to obtain the dedicated LISTEN connection from
     */
    public PostgresNotificationListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Starts the background polling thread. Safe to call multiple times;
     * subsequent calls are no-ops if already running.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            dedicatedConnection = dataSource.getConnection();
            dedicatedConnection.setAutoCommit(true);
            pgConnection = dedicatedConnection.unwrap(PGConnection.class);
        } catch (SQLException e) {
            running.set(false);
            throw new IllegalStateException("Failed to obtain dedicated PGConnection for LISTEN/NOTIFY", e);
        }

        pollingThread = Thread.ofVirtual()
                .name("maestro-pg-notify-listener")
                .start(this::pollLoop);

        logger.info("PostgreSQL LISTEN/NOTIFY polling thread started");
    }

    /**
     * Registers a listener for a specific channel and issues a
     * {@code LISTEN} command on the dedicated connection.
     *
     * <p>This call <b>blocks until the {@code LISTEN} has actually been
     * executed</b> (or {@link #LISTEN_APPLY_TIMEOUT_MS} elapses). Postgres
     * delivers a {@code NOTIFY} only to sessions that are already listening,
     * so returning before the command reached the server would silently drop
     * every notification published in the gap — the caller has no way to
     * detect that, and callers such as {@code SignalManager} re-check their
     * source of truth immediately after subscribing precisely on the
     * assumption that the subscription is live by then.
     *
     * <p>The command is executed on the polling thread rather than here: the
     * dedicated connection is single-threaded and the poller may be blocked
     * inside {@code getNotifications}. The wait is therefore bounded by one
     * poll timeout in the normal case.
     *
     * @param channel  the Postgres NOTIFY channel name
     * @param listener callback receiving (channel, payload) pairs
     */
    public void listen(String channel, BiConsumer<String, String> listener) {
        listeners.put(channel, listener);
        var applied = new CountDownLatch(1);
        pendingCommands.add(new PendingCommand("LISTEN " + sanitizeChannel(channel), applied));
        awaitApplied(applied, channel);
        logger.debug("Listening on Postgres channel '{}'", channel);
    }

    /**
     * Unregisters a listener and issues an {@code UNLISTEN} command.
     *
     * <p>Unlike {@link #listen}, this does not wait: the callback is removed
     * synchronously, so a notification arriving before the {@code UNLISTEN}
     * lands is already ignored.
     *
     * @param channel the Postgres NOTIFY channel name
     */
    public void unlisten(String channel) {
        listeners.remove(channel);
        pendingCommands.add(new PendingCommand("UNLISTEN " + sanitizeChannel(channel), null));
        logger.debug("Unlistened from Postgres channel '{}'", channel);
    }

    /**
     * Waits for the polling thread to execute a queued command.
     *
     * <p>A timeout is not fatal — the subscription is still registered and will
     * be applied on a later cycle — but it does mean notifications may be
     * missed in the meantime, so it is logged loudly.
     */
    private void awaitApplied(CountDownLatch applied, String channel) {
        if (!running.get()) {
            logger.warn("LISTEN on channel '{}' queued while the listener is not running — "
                    + "notifications will be missed until start() is called", channel);
            return;
        }
        try {
            if (!applied.await(LISTEN_APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("LISTEN on channel '{}' was not applied within {}ms — "
                                + "notifications published before it lands will be missed",
                        channel, LISTEN_APPLY_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A {@code LISTEN}/{@code UNLISTEN} command queued for the polling thread,
     * with an optional latch released once the command has been executed.
     *
     * @param sql     the command to execute on the dedicated connection
     * @param applied released after execution, or {@code null} if nobody waits
     */
    private record PendingCommand(String sql, @Nullable CountDownLatch applied) {

        /** Releases any waiter, whether the command succeeded or failed. */
        void markApplied() {
            if (applied != null) {
                applied.countDown();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        var thread = pollingThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeConnection();
        listeners.clear();
        // Nothing will drain the queue now — free anyone waiting on a LISTEN
        PendingCommand pending;
        while ((pending = pendingCommands.poll()) != null) {
            pending.markApplied();
        }
        logger.info("PostgreSQL LISTEN/NOTIFY listener closed");
    }

    // ── Internal ────────────────────────────────────────────────────────

    private long reconnectBackoff = 1000;

    private void pollLoop() {
        while (running.get()) {
            try {
                // Null check for pgConnection — reconnect with backoff if unavailable
                if (pgConnection == null) {
                    reconnect();
                    if (pgConnection == null) {
                        Thread.sleep(Math.min(reconnectBackoff, 30_000));
                        reconnectBackoff = Math.min(reconnectBackoff * 2, 30_000);
                        continue;
                    }
                }
                reconnectBackoff = 1000; // reset on success

                // Drain pending LISTEN/UNLISTEN commands on the poll thread
                PendingCommand cmd;
                while ((cmd = pendingCommands.poll()) != null) {
                    try (var stmt = dedicatedConnection.createStatement()) {
                        stmt.execute(cmd.sql());
                    } finally {
                        // Release the waiter even if execution failed — it must
                        // not block for the full timeout on a dead connection
                        cmd.markApplied();
                    }
                }

                var notifications = pgConnection.getNotifications(POLL_TIMEOUT_MS);
                if (notifications != null) {
                    for (PGNotification notification : notifications) {
                        dispatchNotification(notification);
                    }
                }
            } catch (SQLException e) {
                if (running.get()) {
                    logger.warn("Error polling Postgres notifications, attempting reconnect: {}",
                            e.getMessage());
                    reconnect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void dispatchNotification(PGNotification notification) {
        var channel = notification.getName();
        var payload = notification.getParameter();
        var listener = listeners.get(channel);
        if (listener != null) {
            try {
                listener.accept(channel, payload != null ? payload : "");
            } catch (Exception e) {
                logger.error("Error in notification listener for channel '{}': {}",
                        channel, e.getMessage(), e);
            }
        } else {
            logger.debug("Received notification on unregistered channel '{}', ignoring", channel);
        }
    }

    private void reconnect() {
        closeConnection();
        try {
            Thread.sleep(1000);
            dedicatedConnection = dataSource.getConnection();
            dedicatedConnection.setAutoCommit(true);
            pgConnection = dedicatedConnection.unwrap(PGConnection.class);

            // Re-register all active channels
            for (var channel : listeners.keySet()) {
                executeSql("LISTEN " + sanitizeChannel(channel));
            }
            logger.info("Reconnected Postgres LISTEN/NOTIFY connection and re-registered {} channels",
                    listeners.size());
        } catch (SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("Failed to reconnect Postgres LISTEN/NOTIFY connection: {}", e.getMessage(), e);
        }
    }

    private void executeSql(String sql) {
        var conn = dedicatedConnection;
        if (conn == null) {
            logger.warn("Cannot execute '{}': dedicated connection not available", sql);
            return;
        }
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.warn("Failed to execute '{}': {}", sql, e.getMessage(), e);
        }
    }

    private void closeConnection() {
        var conn = dedicatedConnection;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                logger.debug("Error closing dedicated connection: {}", e.getMessage());
            }
            dedicatedConnection = null;
            pgConnection = null;
        }
    }

    /**
     * Sanitizes a channel name to a valid Postgres identifier.
     * Replaces non-alphanumeric/underscore characters with underscores.
     *
     * @param channel the raw channel name
     * @return a sanitized channel name safe for use in SQL LISTEN/NOTIFY
     */
    public static String sanitizeChannel(String channel) {
        return channel.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
