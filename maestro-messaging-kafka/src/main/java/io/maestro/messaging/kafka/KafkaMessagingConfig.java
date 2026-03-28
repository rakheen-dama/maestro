package io.maestro.messaging.kafka;

import org.jspecify.annotations.Nullable;

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
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param tasksTopic       fixed task topic, or {@code null} for dynamic naming
 * @param signalsTopic     fixed signal topic, or {@code null} for dynamic naming
 * @param adminEventsTopic topic for admin lifecycle events
 * @param consumerGroup    Kafka consumer group ID
 */
public record KafkaMessagingConfig(
        @Nullable String tasksTopic,
        @Nullable String signalsTopic,
        String adminEventsTopic,
        String consumerGroup
) {}
