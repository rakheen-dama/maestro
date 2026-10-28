package io.b2mash.maestro.lock.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.b2mash.maestro.core.spi.SignalNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Valkey/Redis implementation of {@link SignalNotifier} using pub/sub.
 *
 * <p>Uses two separate Lettuce connections:
 * <ul>
 *   <li>A regular command connection for {@code PUBLISH}</li>
 *   <li>A pub/sub connection for {@code SUBSCRIBE}/{@code UNSUBSCRIBE}</li>
 * </ul>
 *
 * <p>The pub/sub connection is required to be separate because Lettuce
 * (like Redis) puts pub/sub connections into a special mode where only
 * {@code (P)SUBSCRIBE} and {@code (P)UNSUBSCRIBE} commands are allowed.
 *
 * <h2>Channel Naming</h2>
 * <p>Channels follow the pattern {@code maestro:signal:{workflowId}}.
 * The signal name is sent as the message payload.
 *
 * <h2>Connection Lifecycle</h2>
 * <p>This class owns both connections passed to its constructor and
 * closes them when {@link #close()} is called. Callers should not
 * close the connections externally.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Lettuce connections are thread-safe,
 * and the callback map uses {@link ConcurrentHashMap}. Callbacks are
 * invoked on Lettuce's Netty event loop thread — they must be
 * lightweight and non-blocking.
 *
 * @see SignalNotifier
 */
public final class ValkeySignalNotifier extends RedisPubSubAdapter<String, String>
        implements SignalNotifier, Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ValkeySignalNotifier.class);

    private static final String CHANNEL_PREFIX = "maestro:signal:";

    /**
     * Longest {@link #subscribe} waits for the server's acknowledgement before
     * falling back to the wake-recheck interval (Ruling R37).
     */
    static final Duration SUBSCRIBE_ACK_TIMEOUT = Duration.ofSeconds(5);

    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final StatefulRedisConnection<String, String> publishConnection;
    private final ConcurrentHashMap<String, SignalCallback> callbacks = new ConcurrentHashMap<>();

    /**
     * While publishes keep failing, how often the outage {@code WARN} is
     * repeated (R58) — so a notifier that never recovers does not go silent
     * after its first line.
     */
    static final Duration FAILURE_WARN_INTERVAL = Duration.ofMinutes(5);

    /** Whether the last publish outcome was a failure — rate-limits outage logging. */
    private final AtomicBoolean publishFailing = new AtomicBoolean();

    /** {@link #nanoClock} reading at the last outage {@code WARN}. */
    private final AtomicLong lastFailureWarnNanos = new AtomicLong();

    private final LongSupplier nanoClock;

    /**
     * Creates a new Valkey signal notifier.
     *
     * <p>This class takes ownership of both connections and will close
     * them when {@link #close()} is called.
     *
     * @param pubSubConnection  dedicated pub/sub connection for subscriptions
     * @param publishConnection regular command connection for publishing
     */
    public ValkeySignalNotifier(
            StatefulRedisPubSubConnection<String, String> pubSubConnection,
            StatefulRedisConnection<String, String> publishConnection
    ) {
        this(pubSubConnection, publishConnection, System::nanoTime);
    }

    /**
     * Test seam: as {@link #ValkeySignalNotifier(StatefulRedisPubSubConnection,
     * StatefulRedisConnection)}, with the monotonic clock that paces the
     * outage {@code WARN} ({@link #FAILURE_WARN_INTERVAL}).
     */
    ValkeySignalNotifier(
            StatefulRedisPubSubConnection<String, String> pubSubConnection,
            StatefulRedisConnection<String, String> publishConnection,
            LongSupplier nanoClock
    ) {
        this.pubSubConnection = pubSubConnection;
        this.publishConnection = publishConnection;
        this.nanoClock = nanoClock;
        this.pubSubConnection.addListener(this);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Issues the {@code PUBLISH} asynchronously and returns without waiting
     * for the server's reply (Issue 32). The synchronous form waited for
     * Lettuce's command timeout (60 s by default) during a Valkey brownout,
     * stalling every signal sender for the whole outage. The reply's outcome is
     * logged from the command's future, on Lettuce's event loop: the first
     * failure after a success logs one {@code WARN}, repeated at most every
     * {@link #FAILURE_WARN_INTERVAL} (5 min) while failures persist; other
     * failures log at {@code DEBUG}, and the first success afterwards logs one
     * {@code INFO} — an outage costs a few log lines, not one per signal. A
     * brownout shorter than Lettuce's command timeout logs nothing at all: the
     * queued commands complete late, once the server answers again. A command that
     * cannot be issued at all (e.g. a closed connection) is treated the same
     * way; this method never throws. A missed notification is covered by the
     * receiving await's {@code maestro.signal.wake-recheck-interval} store
     * re-read.
     */
    @Override
    public void publish(String workflowId, String signalName) {
        var channel = CHANNEL_PREFIX + workflowId;
        try {
            publishConnection.async().publish(channel, signalName)
                    .whenComplete((receivers, failure) -> {
                        if (failure == null) {
                            onPublished(signalName, channel);
                        } else {
                            onPublishFailed(signalName, channel, failure);
                        }
                    });
        } catch (RuntimeException e) {
            onPublishFailed(signalName, channel, e);
        }
    }

    private void onPublished(String signalName, String channel) {
        if (publishFailing.compareAndSet(true, false)) {
            logger.info("Signal notifications to Valkey are being delivered again");
        }
        logger.debug("Published signal '{}' to channel '{}'", signalName, channel);
    }

    private void onPublishFailed(String signalName, String channel, Throwable failure) {
        var now = nanoClock.getAsLong();
        var lastWarn = lastFailureWarnNanos.get();
        var newOutage = publishFailing.compareAndSet(false, true);
        if ((newOutage || now - lastWarn >= FAILURE_WARN_INTERVAL.toNanos())
                && lastFailureWarnNanos.compareAndSet(lastWarn, now)) {
            logger.warn("Failed to publish signal '{}' to channel '{}' ({}) — cross-node wakes fall "
                            + "back to the maestro.signal.wake-recheck-interval store re-read; other "
                            + "publish failures are logged at DEBUG, and this WARN repeats every {} "
                            + "until one succeeds",
                    signalName, channel, describe(failure), FAILURE_WARN_INTERVAL);
        } else {
            logger.debug("Failed to publish signal '{}' to channel '{}': {}",
                    signalName, channel, describe(failure));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Waits for the server to acknowledge the subscription, bounded by
     * {@link #SUBSCRIBE_ACK_TIMEOUT} (5 s), before returning. Once
     * acknowledged, a {@link #publish} issued after this returns is always
     * delivered, which is what makes the caller's check-then-subscribe race
     * closure real.
     *
     * <p>The bound is deliberately far below Lettuce's command timeout (60 s
     * by default): this runs on every {@code awaitSignal} entry, and a
     * synchronous subscribe during a Valkey brownout would stall each one past
     * the shutdown drain. If the acknowledgement does not arrive in time, or
     * the command fails, the callback is removed, a {@code WARN} is logged and
     * this returns normally — it never throws into the workflow thread. The
     * workflow then still wakes, at the latest after
     * {@code maestro.signal.wake-recheck-interval}, from the store re-read. If
     * the waiting thread is interrupted, the interrupt flag is restored and the
     * callback removed. If the command cannot be issued at all (a
     * {@link RuntimeException} from Lettuce, e.g. a closed connection), the
     * callback is removed and the exception propagates; the engine's
     * {@code SignalManager} logs it and falls back to the same re-check.
     *
     * <p>The remaining gap is unchanged: a notification published while this
     * connection is disconnected. Lettuce re-subscribes on reconnect but does
     * not replay, and the wake-recheck store re-read covers it.
     */
    @Override
    public void subscribe(String workflowId, SignalCallback callback) {
        var channel = CHANNEL_PREFIX + workflowId;
        callbacks.put(workflowId, callback);
        try {
            pubSubConnection.async().subscribe(channel)
                    .get(SUBSCRIBE_ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            logger.debug("Subscribed to signal channel '{}'", channel);
        } catch (TimeoutException | ExecutionException e) {
            callbacks.remove(workflowId, callback);
            logger.warn("Subscription to signal channel '{}' was not acknowledged within {} ({}) — "
                            + "cross-node wake for workflow '{}' falls back to the "
                            + "maestro.signal.wake-recheck-interval store re-read",
                    channel, SUBSCRIBE_ACK_TIMEOUT, describe(e), workflowId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbacks.remove(workflowId, callback);
            logger.debug("Interrupted while subscribing to signal channel '{}'", channel);
        } catch (RuntimeException e) {
            // The command could not even be issued (e.g. the connection is
            // closed): unregister, then let the caller fall back.
            callbacks.remove(workflowId, callback);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the callback first, so no further notification is delivered
     * for this workflow, then issues the {@code UNSUBSCRIBE} without waiting
     * for the server: nothing depends on its acknowledgement, and waiting
     * would stall the workflow thread's exit from {@code awaitSignal} during a
     * brownout.
     */
    @Override
    public void unsubscribe(String workflowId) {
        var channel = CHANNEL_PREFIX + workflowId;
        callbacks.remove(workflowId);
        try {
            pubSubConnection.async().unsubscribe(channel);
            logger.debug("Unsubscribed from signal channel '{}'", channel);
        } catch (RuntimeException e) {
            logger.debug("Failed to issue unsubscribe for '{}': {}", channel, e.getMessage());
        }
    }

    private static String describe(Throwable e) {
        var cause = (e instanceof ExecutionException || e instanceof CompletionException)
                && e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    /**
     * Called by Lettuce when a message is received on a subscribed channel.
     * Runs on the Netty event loop thread — must be lightweight.
     */
    @Override
    public void message(String channel, String message) {
        if (!channel.startsWith(CHANNEL_PREFIX)) {
            return;
        }
        var workflowId = channel.substring(CHANNEL_PREFIX.length());
        var signalName = message;
        var callback = callbacks.get(workflowId);
        if (callback != null) {
            try {
                callback.onSignal(workflowId, signalName);
            } catch (Exception e) {
                logger.warn("Signal callback failed for workflow '{}', signal '{}': {}",
                        workflowId, signalName, e.getMessage());
            }
        }
    }

    /**
     * Closes this notifier, unsubscribing from all channels and
     * closing both connections.
     */
    @Override
    public void close() {
        // Unsubscribe from all active channels
        for (var workflowId : callbacks.keySet()) {
            var channel = CHANNEL_PREFIX + workflowId;
            try {
                // Not awaited: the connection is closed right after, and a
                // brownout must not stall shutdown for the command timeout.
                pubSubConnection.async().unsubscribe(channel);
            } catch (Exception e) {
                logger.debug("Failed to unsubscribe from '{}' during close: {}", channel, e.getMessage());
            }
        }
        callbacks.clear();
        pubSubConnection.removeListener(this);

        // Close owned connections
        try {
            pubSubConnection.close();
        } catch (Exception e) {
            logger.debug("Failed to close pub/sub connection: {}", e.getMessage());
        }
        try {
            publishConnection.close();
        } catch (Exception e) {
            logger.debug("Failed to close publish connection: {}", e.getMessage());
        }
    }
}
