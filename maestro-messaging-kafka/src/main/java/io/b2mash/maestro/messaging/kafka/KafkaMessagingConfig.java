package io.b2mash.maestro.messaging.kafka;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Resolved configuration for Kafka-based workflow messaging.
 *
 * <p>Topic fields that are {@code null} indicate dynamic topic naming:
 * <ul>
 *   <li>{@code tasksTopic == null} → topics named {@code maestro.tasks.{taskQueue}}</li>
 *   <li>{@code signalsTopic == null} → topics named {@code maestro.signals.{serviceName}}</li>
 * </ul>
 *
 * <p>Non-null values override the dynamic naming with a fixed topic.
 *
 * <p>The redelivery fields carry {@code maestro.messaging.redelivery.*} into
 * the adapter: a handler exception is redelivered with exponential backoff
 * ({@code min(initialInterval × multiplier^(n-1), maxInterval)}) and, once
 * {@code maxAttempts} — which counts the initial attempt — is spent, the record
 * is published to {@code <topic><deadLetterSuffix>}. That topic is
 * pre-declared by the operator; Maestro never creates topics.
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param tasksTopic        fixed task topic, or {@code null} for dynamic naming
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
 *                          and skips the dead-letter-topic startup probe
 */
public record KafkaMessagingConfig(
        @Nullable String tasksTopic,
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

    /**
     * Validates the redelivery policy.
     *
     * @throws IllegalArgumentException if any redelivery value is out of range
     */
    public KafkaMessagingConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, but was " + maxAttempts);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0, but was " + multiplier);
        }
    }
}
