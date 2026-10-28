package io.b2mash.maestro.messaging.postgres;

import io.b2mash.maestro.core.spi.SignalNotifier;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>This class is thread-safe. Publish borrows a pooled connection per call,
 * on a virtual thread of its own.
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

    /** Whether the last {@code NOTIFY} failed — rate-limits outage logging to one WARN. */
    private final AtomicBoolean notifyFailing = new AtomicBoolean();

    /** Virtual threads that carry each {@code NOTIFY} off the caller's thread (Issue 32). */
    private static final ThreadFactory NOTIFY_THREADS =
            Thread.ofVirtual().name("maestro-pg-notify-", 0).factory();

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

    /**
     * {@inheritDoc}
     *
     * <p>Hands the {@code NOTIFY} to a fresh virtual thread and returns without
     * waiting for it (Issue 32): borrowing a pooled connection can park for the
     * pool's connection timeout when the pool is exhausted or Postgres is
     * unreachable, and the SPI requires {@code publish} not to block its
     * caller. The first failure after a success logs one {@code WARN}; further
     * failures log at {@code DEBUG} until a {@code NOTIFY} succeeds again,
     * which logs one {@code INFO}. A missed notification is covered by the
     * receiving await's {@code maestro.signal.wake-recheck-interval} store
     * re-read. Never throws.
     */
    @Override
    public void publish(String workflowId, String signalName) {
        var channel = channelFor(workflowId);
        try {
            NOTIFY_THREADS.newThread(() -> sendNotify(channel, signalName)).start();
        } catch (RuntimeException e) {
            onNotifyFailed(signalName, channel, e);
        }
    }

    private void sendNotify(String channel, String signalName) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("NOTIFY " + channel + ", '" + escapePayload(signalName) + "'");
        } catch (SQLException | RuntimeException e) {
            // Best-effort — failures are tolerated per SPI contract
            onNotifyFailed(signalName, channel, e);
            return;
        }
        if (notifyFailing.compareAndSet(true, false)) {
            logger.info("Signal notifications over Postgres NOTIFY are being delivered again");
        }
    }

    private void onNotifyFailed(String signalName, String channel, Exception e) {
        if (notifyFailing.compareAndSet(false, true)) {
            logger.warn("Failed to NOTIFY signal '{}' on channel '{}' ({}) — cross-node wakes fall "
                            + "back to the maestro.signal.wake-recheck-interval store re-read; further "
                            + "NOTIFY failures are logged at DEBUG until one succeeds",
                    signalName, channel, e.toString());
        } else {
            logger.debug("Failed to NOTIFY signal '{}' on channel '{}': {}",
                    signalName, channel, e.toString());
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
