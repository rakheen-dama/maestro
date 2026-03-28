/**
 * Apache Kafka implementation of the Maestro {@link io.maestro.core.spi.WorkflowMessaging} SPI.
 *
 * <p>Provides task dispatch, cross-service signal delivery, and lifecycle
 * event publishing using Spring Kafka 4.x. Messages are serialized as JSON
 * bytes via Jackson 3 and keyed by workflow ID for partition ordering.
 *
 * @see io.maestro.messaging.kafka.KafkaWorkflowMessaging
 * @see io.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration
 */
@NullMarked
package io.maestro.messaging.kafka;

import org.jspecify.annotations.NullMarked;
