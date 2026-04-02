package io.b2mash.maestro.messaging.postgres;

import io.b2mash.maestro.core.spi.SignalNotifier;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL {@code LISTEN/NOTIFY} implementation of {@link SignalNotifier}.
 *
 * <p>Uses Postgres {@code NOTIFY} to publish signal notifications and
 * {@code LISTEN} (via {@link PostgresNotificationListener}) to receive them.
 * Channel names are derived from workflow IDs:
 * {@code maestro_signal_{sanitized_workflowId}}.
 *
 * <p>This is a <b>performance optimisation</b> — not a correctness requirement.
 * If the notifier is unavailable or publish fails, signals are still delivered
 * via Postgres persistence and discovered on replay/recovery.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publish uses a pooled connection per call.
 * Subscriptions are tracked in a {@link ConcurrentHashMap} and dispatched
 * from the notification listener's polling thread.
 *
 * @see SignalNotifier
 * @see PostgresNotificationListener
 */
@NullMarked
public final class PostgresSignalNotifier implements SignalNotifier, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresSignalNotifier.class);
    private static final String CHANNEL_PREFIX = "maestro_signal_";

    private final DataSource dataSource;
    private final PostgresNotificationListener notificationListener;
    private final Map<String, SignalCallback> callbacks = new ConcurrentHashMap<>();

    /**
     * Creates a new Postgres-based signal notifier.
     *
     * @param dataSource           the DataSource for publish operations
     * @param notificationListener the shared notification listener for LISTEN channels
     */
    public PostgresSignalNotifier(DataSource dataSource, PostgresNotificationListener notificationListener) {
        this.dataSource = dataSource;
        this.notificationListener = notificationListener;
    }

    @Override
    public void publish(String workflowId, String signalName) {
        var channel = channelFor(workflowId);
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("NOTIFY " + channel + ", '" + escapePayload(signalName) + "'");
        } catch (SQLException e) {
            // Best-effort — failures are tolerated per SPI contract
            logger.warn("Failed to NOTIFY signal '{}' on channel '{}': {}",
                    signalName, channel, e.getMessage());
        }
    }

    @Override
    public void subscribe(String workflowId, SignalCallback callback) {
        var channel = channelFor(workflowId);
        callbacks.put(workflowId, callback);
        notificationListener.listen(channel, (ch, payload) -> {
            var cb = callbacks.get(workflowId);
            if (cb != null) {
                cb.onSignal(workflowId, payload);
            }
        });
        logger.debug("Subscribed to signal notifications for workflow '{}'", workflowId);
    }

    @Override
    public void unsubscribe(String workflowId) {
        var channel = channelFor(workflowId);
        callbacks.remove(workflowId);
        notificationListener.unlisten(channel);
        logger.debug("Unsubscribed from signal notifications for workflow '{}'", workflowId);
    }

    @Override
    public void close() {
        callbacks.clear();
        // The notification listener is shared and closed by the auto-configuration
        logger.info("PostgresSignalNotifier closed");
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static String channelFor(String workflowId) {
        return PostgresNotificationListener.sanitizeChannel(CHANNEL_PREFIX + workflowId);
    }

    /**
     * Escapes single quotes in the NOTIFY payload to prevent SQL injection.
     */
    private static String escapePayload(String payload) {
        return payload.replace("'", "''");
    }
}
