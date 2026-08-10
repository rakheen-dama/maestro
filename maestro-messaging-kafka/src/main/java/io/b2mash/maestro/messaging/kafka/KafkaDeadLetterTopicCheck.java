package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Warn-only startup probe: does a topic's dead-letter companion
 * ({@code <topic><suffix>}) actually exist?
 *
 * <p>Maestro never creates topics, dead-letter topics included — they are
 * pre-declared by the operator ({@link KafkaRedeliveryErrorHandlers}). If one
 * is missing, nothing fails until the attempt budget for that topic is first
 * exhausted, and the failure then shows up as a stalled, noisily-retrying
 * consumer rather than a clear message at the moment the gap could still be
 * fixed for free. This class exists to surface that gap at subscription time
 * instead.
 *
 * <p>The probe never fails the caller: a broker that cannot be reached, or any
 * other failure of the check itself, is logged at {@code DEBUG} and treated as
 * "nothing to report" — this is a convenience diagnostic, not a startup gate.
 * Each missing dead-letter topic is logged once, at {@code WARN}, naming both
 * the missing topic and the source topic it belongs to.
 *
 * <p><b>Thread safety:</b> This class is a stateless utility; the caller owns
 * the {@link Admin} client's lifecycle.
 */
@NullMarked
public final class KafkaDeadLetterTopicCheck {

    private static final Logger logger = LoggerFactory.getLogger(KafkaDeadLetterTopicCheck.class);

    /** Bound on the whole probe, so a slow or unreachable broker cannot delay a subscription. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private KafkaDeadLetterTopicCheck() {
    }

    /**
     * The entry point both Maestro subscription paths call —
     * {@code KafkaWorkflowMessaging.subscribe}/{@code subscribeSignals} and
     * {@code MaestroSignalListenerBeanPostProcessor}'s container activation.
     * Builds a throwaway {@link Admin} client from the consumer factory's
     * full configuration (see {@link #adminConfigFrom}), runs the single-topic
     * probe, and closes it — never throws, exactly like
     * {@link #warnOnMissing(Admin, Collection, String)}.
     *
     * @param consumerFactory the factory whose configuration the probe's
     *                        {@link Admin} client is derived from
     * @param topic           the source topic about to be subscribed to
     * @param suffix          appended to {@code topic} to name its
     *                        dead-letter companion
     */
    public static void warnOnMissing(ConsumerFactory<String, byte[]> consumerFactory, String topic, String suffix) {
        try (var admin = Admin.create(adminConfigFrom(consumerFactory))) {
            warnOnMissing(admin, List.of(topic), suffix);
        } catch (Exception e) {
            logger.debug("Dead-letter topic check for '{}' could not run: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Derives an {@link Admin} client config from a consumer factory's full
     * configuration, keeping only the keys {@link AdminClientConfig} accepts.
     *
     * <p>Copying only {@code bootstrap.servers} (the prior behaviour) silently
     * drops every connection-security setting — {@code security.protocol},
     * {@code sasl.*}, {@code ssl.*} — so on a SASL/SSL-secured cluster
     * {@code Admin.create} would fail every time and this whole probe would be
     * permanently, silently dead (its caller catches everything at
     * {@code DEBUG} per this class's warn-only contract). Filtering the
     * consumer factory's <em>entire</em> configuration through
     * {@link AdminClientConfig#configNames()} instead carries those security
     * settings over automatically, while still excluding consumer-only keys
     * {@code AdminClientConfig} does not recognise — {@code group.id}, the key/
     * value deserializers, {@code auto.offset.reset}, etc. — which
     * {@link Admin#create} would otherwise reject or ignore.
     *
     * @param consumerFactory the factory whose configuration to filter
     * @return the Admin-accepted subset of the consumer factory's configuration
     */
    static Map<String, Object> adminConfigFrom(ConsumerFactory<?, ?> consumerFactory) {
        var adminConfigNames = AdminClientConfig.configNames();
        var props = new HashMap<String, Object>();
        for (var entry : consumerFactory.getConfigurationProperties().entrySet()) {
            if (adminConfigNames.contains(entry.getKey())) {
                props.put(entry.getKey(), entry.getValue());
            }
        }
        return props;
    }

    /**
     * Warns for every {@code source} topic whose {@code <source><suffix>}
     * dead-letter companion does not exist.
     *
     * @param admin   the admin client to probe with; the caller owns closing it
     * @param topics  the source topics to check the dead-letter companion of
     * @param suffix  appended to each source topic to name its dead-letter topic
     * @return the dead-letter topic names confirmed missing (for testability);
     *         never {@code null}, empty if none are missing or the probe itself failed
     */
    public static List<String> warnOnMissing(Admin admin, Collection<String> topics, String suffix) {
        if (topics.isEmpty()) {
            return List.of();
        }

        Map<String, String> dltBySource = new LinkedHashMap<>();
        for (var topic : topics) {
            dltBySource.put(topic, topic + suffix);
        }

        try {
            var options = new DescribeTopicsOptions().timeoutMs((int) PROBE_TIMEOUT.toMillis());
            var futures = admin.describeTopics(new ArrayList<>(dltBySource.values()), options).topicNameValues();

            var missing = new ArrayList<String>();
            for (var entry : dltBySource.entrySet()) {
                var sourceTopic = entry.getKey();
                var dltTopic = entry.getValue();
                if (isMissing(futures.get(dltTopic), dltTopic)) {
                    missing.add(dltTopic);
                    logger.warn("Dead-letter topic '{}' does not exist — redelivery for '{}' will exhaust its "
                                    + "attempts and then fail to publish; pre-create it or set "
                                    + "maestro.messaging.redelivery.enabled=false",
                            dltTopic, sourceTopic);
                }
            }
            return missing;
        } catch (Exception e) {
            // The probe itself failed (e.g. the broker is unreachable) — this is a
            // best-effort diagnostic, not a startup gate, so it never surfaces as
            // more than a DEBUG line.
            logger.debug("Dead-letter topic existence check could not run: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * @return {@code true} if the topic is confirmed absent; {@code false} if it
     *         exists or its own lookup failed for any other reason (logged at
     *         DEBUG — an inconclusive probe must never produce a false WARN)
     */
    private static boolean isMissing(org.apache.kafka.common.KafkaFuture<?> future, String dltTopic) {
        try {
            future.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return false;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return true;
            }
            logger.debug("Could not determine whether dead-letter topic '{}' exists: {}",
                    dltTopic, e.getMessage(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while probing dead-letter topic '{}'", dltTopic, e);
            return false;
        } catch (TimeoutException e) {
            logger.debug("Timed out probing dead-letter topic '{}'", dltTopic, e);
            return false;
        }
    }
}
