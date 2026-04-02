package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final DataSource dataSource;
    private final Map<String, BiConsumer<String, String>> listeners = new ConcurrentHashMap<>();
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
     * @param channel  the Postgres NOTIFY channel name
     * @param listener callback receiving (channel, payload) pairs
     */
    public void listen(String channel, BiConsumer<String, String> listener) {
        listeners.put(channel, listener);
        executeSql("LISTEN " + sanitizeChannel(channel));
        logger.debug("Listening on Postgres channel '{}'", channel);
    }

    /**
     * Unregisters a listener and issues an {@code UNLISTEN} command.
     *
     * @param channel the Postgres NOTIFY channel name
     */
    public void unlisten(String channel) {
        listeners.remove(channel);
        executeSql("UNLISTEN " + sanitizeChannel(channel));
        logger.debug("Unlistened from Postgres channel '{}'", channel);
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
        logger.info("PostgreSQL LISTEN/NOTIFY listener closed");
    }

    // ── Internal ────────────────────────────────────────────────────────

    private void pollLoop() {
        while (running.get()) {
            try {
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
        return channel.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
