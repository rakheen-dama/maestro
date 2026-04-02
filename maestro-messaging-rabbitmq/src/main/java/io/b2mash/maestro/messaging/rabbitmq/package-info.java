/**
 * RabbitMQ implementation of the Maestro {@link io.b2mash.maestro.core.spi.WorkflowMessaging} SPI.
 *
 * <p>Provides task dispatch, cross-service signal delivery, and lifecycle
 * event publishing using Spring AMQP. Messages are serialized as JSON
 * bytes via Jackson 3 and routed by workflow ID.
 *
 * <h2>Exchange Topology</h2>
 * <ul>
 *   <li>{@code maestro.tasks} — direct exchange, routing key is the task queue name</li>
 *   <li>{@code maestro.signals} — direct exchange, routing key is the service name</li>
 *   <li>{@code maestro.admin.events} — fanout exchange for lifecycle events</li>
 * </ul>
 *
 * @see io.b2mash.maestro.messaging.rabbitmq.RabbitMqWorkflowMessaging
 * @see io.b2mash.maestro.messaging.rabbitmq.config.RabbitMqMessagingAutoConfiguration
 */
@NullMarked
package io.b2mash.maestro.messaging.rabbitmq;

import org.jspecify.annotations.NullMarked;
