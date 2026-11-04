package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.spring.config.MaestroProperties.TopicsProperties.TopicVerification;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * Resolved configuration for Kafka-based workflow messaging.
 *
 * <p>A {@code null} {@code signalsTopic} indicates dynamic topic naming —
 * topics named {@code maestro.signals.{serviceName}}; a non-null value
 * overrides the dynamic naming with a fixed topic. {@link #signalTopicFor}
 * is the one place that rule lives.
 *
 * <p>The redelivery fields carry {@code maestro.messaging.redelivery.*} into
 * the adapter: a handler exception is redelivered with exponential backoff
 * ({@code min(initialInterval × multiplier^(n-1), maxInterval)}) and, once
 * {@code maxAttempts} — which counts the initial attempt — is spent, the record
 * is published to {@code <topic><deadLetterSuffix>}. That topic is
 * pre-declared by the operator; Maestro never creates topics.
 *
 * <p>{@code verifyOnStartup} carries {@code maestro.messaging.topics.verify-on-startup}:
 * what {@link KafkaTopicVerifier} does, during context refresh, about a topic
 * this service needs that does not exist, and whether the consumer containers
 * may be started with {@code missingTopicsFatal} ({@link TopicVerification#FAIL}
 * only, and only once the probe has confirmed every required topic present —
 * {@link KafkaTopicVerifier#fatalOnMissing}).
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param signalsTopic      fixed signal topic, or {@code null} for dynamic naming
 * @param adminEventsTopic  topic for admin lifecycle events
 * @param consumerGroup     Kafka consumer group ID
 * @param maxAttempts       total delivery attempts, including the first
 * @param initialInterval   backoff before the second attempt
 * @param multiplier        factor applied to the backoff after each failure
 * @param maxInterval       ceiling for the computed backoff
 * @param deadLetterSuffix  suffix appended to a topic to name its dead-letter topic
 * @param redeliveryEnabled whether handler-failure redelivery and
 *                          dead-lettering are active; {@code false} installs a
 *                          zero-retry, no-dead-letter error handler instead
 *                          and drops the {@code .DLT} topics from the set the
 *                          startup verification requires
 * @param verifyOnStartup   the startup policy for missing topics; see
 *                          {@link TopicVerification}
 */
public record KafkaMessagingConfig(
        @Nullable String signalsTopic,
        String adminEventsTopic,
        String consumerGroup,
        int maxAttempts,
        Duration initialInterval,
        double multiplier,
        Duration maxInterval,
        String deadLetterSuffix,
        boolean redeliveryEnabled,
        TopicVerification verifyOnStartup
) {

    /**
     * Validates the redelivery policy.
     *
     * @throws IllegalArgumentException if any redelivery value is out of range
     * @throws NullPointerException     if {@code verifyOnStartup} is {@code null}
     */
    public KafkaMessagingConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, but was " + maxAttempts);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0, but was " + multiplier);
        }
        Objects.requireNonNull(verifyOnStartup, "verifyOnStartup must not be null");
    }

    /**
     * Compatibility constructor for callers that predate
     * {@code verifyOnStartup}; uses the property's default,
     * {@link TopicVerification#FAIL}.
     *
     * @param signalsTopic      fixed signal topic, or {@code null} for dynamic naming
     * @param adminEventsTopic  topic for admin lifecycle events
     * @param consumerGroup     Kafka consumer group ID
     * @param maxAttempts       total delivery attempts, including the first
     * @param initialInterval   backoff before the second attempt
     * @param multiplier        factor applied to the backoff after each failure
     * @param maxInterval       ceiling for the computed backoff
     * @param deadLetterSuffix  suffix appended to a topic to name its dead-letter topic
     * @param redeliveryEnabled whether redelivery and dead-lettering are active
     */
    public KafkaMessagingConfig(
            @Nullable String signalsTopic,
            String adminEventsTopic,
            String consumerGroup,
            int maxAttempts,
            Duration initialInterval,
            double multiplier,
            Duration maxInterval,
            String deadLetterSuffix,
            boolean redeliveryEnabled
    ) {
        this(signalsTopic, adminEventsTopic, consumerGroup, maxAttempts, initialInterval, multiplier,
                maxInterval, deadLetterSuffix, redeliveryEnabled, TopicVerification.FAIL);
    }

    /**
     * The topic a service's inbound signals arrive on: the fixed
     * {@link #signalsTopic()} when one is configured, otherwise
     * {@code maestro.signals.<serviceName>}.
     *
     * @param serviceName the service whose signal topic to name
     * @return the signal topic
     */
    public String signalTopicFor(String serviceName) {
        return signalsTopic != null ? signalsTopic : "maestro.signals." + serviceName;
    }

    /**
     * @param topic a topic a Maestro consumer subscribes to
     * @return the dead-letter companion redelivery parks exhausted records on
     */
    public String deadLetterTopicFor(String topic) {
        return topic + deadLetterSuffix;
    }
}
