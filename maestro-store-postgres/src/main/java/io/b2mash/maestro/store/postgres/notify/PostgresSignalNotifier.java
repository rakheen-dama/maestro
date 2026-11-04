package io.b2mash.maestro.store.postgres.notify;

import io.b2mash.maestro.core.spi.SignalNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * PostgreSQL {@code LISTEN/NOTIFY} implementation of {@link SignalNotifier}:
 * the cross-node signal wake for a Postgres + Kafka deployment.
 *
 * <p>Every node of a service shares one channel
 * ({@link WakeChannel#channelFor}); a publish is
 * {@code SELECT pg_notify(channel, payload)} with the workflow ID and signal
 * name encoded by {@link WakeChannel#encode}, and a subscription is an
 * in-memory registration on this node's {@link PostgresWakeListener}. Because
 * the channel never carries the workflow ID, IDs of any length and any
 * spelling are delivered exactly — the defects of the earlier
 * channel-per-workflow design (silent loss above ~48 characters, and
 * {@code Order-1} waking {@code order_1}) cannot recur.
 *
 * <p>This is a <b>performance optimisation</b>, not a correctness requirement:
 * the engine persists every signal before publishing and re-reads the store on
 * {@code maestro.signal.wake-recheck-interval}, so a lost or late wake only
 * costs latency.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #publish} hands each {@code NOTIFY} to a fresh virtual
 * thread that borrows its own pooled connection; {@link #subscribe} and
 * {@link #unsubscribe} are concurrent-map operations on the listener.
 * Callbacks fire on the listener's thread.
 *
 * @see SignalNotifier
 * @see PostgresWakeListener
 */
public final class PostgresSignalNotifier implements SignalNotifier, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresSignalNotifier.class);

    /**
     * While {@code NOTIFY}s keep failing, how often the outage {@code WARN} is
     * repeated (R58) — so a notifier that never recovers does not go silent
     * after its first line.
     */
    static final Duration FAILURE_WARN_INTERVAL = Duration.ofMinutes(5);

    /** Virtual threads that carry each {@code NOTIFY} off the caller's thread (Issue 32). */
    private static final ThreadFactory NOTIFY_THREADS =
            Thread.ofVirtual().name("maestro-pg-notify-", 0).factory();

    private final DataSource dataSource;
    private final PostgresWakeListener listener;
    private final String channel;
    private final LongSupplier nanoClock;

    /** Whether the last {@code NOTIFY} failed — rate-limits outage logging. */
    private final AtomicBoolean notifyFailing = new AtomicBoolean();

    /** {@link #nanoClock} reading at the last outage {@code WARN}. */
    private final AtomicLong lastFailureWarnNanos = new AtomicLong();

    /**
     * @param dataSource the pool {@code NOTIFY}s borrow a connection from
     * @param listener   this node's listener; {@link #subscribe} registers on it
     * @param channel    the service's wake channel — the one {@code listener} LISTENs on
     */
    public PostgresSignalNotifier(DataSource dataSource, PostgresWakeListener listener, String channel) {
        this(dataSource, listener, channel, System::nanoTime);
    }

    /**
     * Test seam: as the public constructor, with the monotonic clock that paces
     * the outage {@code WARN} ({@link #FAILURE_WARN_INTERVAL}).
     */
    PostgresSignalNotifier(DataSource dataSource, PostgresWakeListener listener, String channel,
                           LongSupplier nanoClock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hands the {@code NOTIFY} to a fresh virtual thread and returns without
     * waiting for it (Issue 32): borrowing a pooled connection can park for the
     * pool's connection timeout when the pool is exhausted or Postgres is
     * unreachable, and the SPI requires {@code publish} not to block its
     * caller. The first failure after a success logs one {@code WARN},
     * repeated at most every {@link #FAILURE_WARN_INTERVAL} (5 min) while
     * failures persist; other failures log at {@code DEBUG}, and the first
     * {@code NOTIFY} that succeeds afterwards logs one {@code INFO}. Never throws.
     */
    @Override
    public void publish(String workflowId, String signalName) {
        try {
            NOTIFY_THREADS.newThread(() -> sendNotify(workflowId, signalName)).start();
        } catch (RuntimeException e) {
            onNotifyFailed(workflowId, signalName, e);
        }
    }

    private void sendNotify(String workflowId, String signalName) {
        try (var conn = dataSource.getConnection()) {
            // A pool configured with auto-commit=false would otherwise queue the NOTIFY in
            // a transaction that is rolled back when the connection is returned.
            conn.setAutoCommit(true);
            try (var stmt = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                stmt.setString(1, channel);
                stmt.setString(2, WakeChannel.encode(workflowId, signalName));
                stmt.execute();
            }
        } catch (SQLException | RuntimeException e) {
            onNotifyFailed(workflowId, signalName, e);
            return;
        }
        if (notifyFailing.compareAndSet(true, false)) {
            logger.info("Signal wakes over Postgres NOTIFY are being delivered again");
        }
    }

    private void onNotifyFailed(String workflowId, String signalName, Exception e) {
        var now = nanoClock.getAsLong();
        var lastWarn = lastFailureWarnNanos.get();
        var newOutage = notifyFailing.compareAndSet(false, true);
        if ((newOutage || now - lastWarn >= FAILURE_WARN_INTERVAL.toNanos())
                && lastFailureWarnNanos.compareAndSet(lastWarn, now)) {
            logger.warn("Failed to NOTIFY signal '{}' for workflow '{}' on channel '{}' ({}) — cross-node "
                            + "wakes fall back to the maestro.signal.wake-recheck-interval store re-read; "
                            + "other NOTIFY failures are logged at DEBUG, and this WARN repeats every {} "
                            + "until one succeeds",
                    signalName, workflowId, channel, e.toString(), FAILURE_WARN_INTERVAL);
        } else {
            logger.debug("Failed to NOTIFY signal '{}' for workflow '{}' on channel '{}': {}",
                    signalName, workflowId, channel, e.toString());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>An in-memory registration on the listener — live the moment it
     * returns, with no round trip to Postgres.
     */
    @Override
    public void subscribe(String workflowId, SignalCallback callback) {
        listener.register(workflowId, signalName -> callback.onSignal(workflowId, signalName));
        logger.debug("Subscribed to signal wakes for workflow '{}'", workflowId);
    }

    @Override
    public void unsubscribe(String workflowId) {
        listener.unregister(workflowId);
        logger.debug("Unsubscribed from signal wakes for workflow '{}'", workflowId);
    }

    /**
     * Releases nothing of its own: the listener is shared and owned by whoever
     * created it (the auto-configuration, in an application).
     */
    @Override
    public void close() {
        logger.debug("PostgresSignalNotifier closed");
    }
}
