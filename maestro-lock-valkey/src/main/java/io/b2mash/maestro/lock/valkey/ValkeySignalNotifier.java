package io.b2mash.maestro.lock.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.b2mash.maestro.core.spi.SignalNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;

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

    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final StatefulRedisConnection<String, String> publishConnection;
    private final ConcurrentHashMap<String, SignalCallback> callbacks = new ConcurrentHashMap<>();

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
        this.pubSubConnection = pubSubConnection;
        this.publishConnection = publishConnection;
        this.pubSubConnection.addListener(this);
    }

    @Override
    public void publish(String workflowId, String signalName) {
        var channel = CHANNEL_PREFIX + workflowId;
        try {
            publishConnection.sync().publish(channel, signalName);
            logger.debug("Published signal '{}' to channel '{}'", signalName, channel);
        } catch (Exception e) {
            logger.warn("Failed to publish signal '{}' to channel '{}': {}",
                    signalName, channel, e.getMessage());
        }
    }

    @Override
    public void subscribe(String workflowId, SignalCallback callback) {
        var channel = CHANNEL_PREFIX + workflowId;
        callbacks.put(workflowId, callback);
        pubSubConnection.async().subscribe(channel);
        logger.debug("Subscribed to signal channel '{}'", channel);
    }

    @Override
    public void unsubscribe(String workflowId) {
        var channel = CHANNEL_PREFIX + workflowId;
        callbacks.remove(workflowId);
        pubSubConnection.async().unsubscribe(channel);
        logger.debug("Unsubscribed from signal channel '{}'", channel);
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
                pubSubConnection.sync().unsubscribe(channel);
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
