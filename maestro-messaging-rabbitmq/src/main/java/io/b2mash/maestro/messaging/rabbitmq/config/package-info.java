/**
 * Spring Boot auto-configuration for RabbitMQ-based workflow messaging.
 *
 * <p>When Spring AMQP is on the classpath and {@code maestro.messaging.type}
 * is {@code "rabbitmq"}, this package's auto-configuration creates the
 * necessary beans for RabbitMQ publishing, consuming, and exchange/queue
 * topology declaration.
 */
@NullMarked
package io.b2mash.maestro.messaging.rabbitmq.config;

import org.jspecify.annotations.NullMarked;
