package io.b2mash.maestro.messaging.rabbitmq;

import org.jspecify.annotations.NullMarked;

import java.time.Duration;

/**
 * Redelivery policy for {@link RabbitMqWorkflowMessaging}.
 *
 * <p>A handler exception means the message was not processed — on the engine
 * signal channel it means the signal is not yet durable — so the container
 * must not acknowledge the message. Instead the listener container's stateless
 * retry interceptor re-invokes the handler in-process with exponential backoff
 * and, once the attempt budget is exhausted, a {@link
 * org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer} republishes
 * the message to {@link #deadLetterExchange()} with routing key equal to the
 * source queue name, after which the original message is acknowledged.
 *
 * <p>The delay before the attempt following the <i>n</i>-th failure is
 * {@code min(initialInterval × multiplier^(n-1), maxInterval)};
 * {@code maxAttempts} counts the initial attempt.
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param maxAttempts        total delivery attempts, including the first
 * @param initialInterval    backoff before the second attempt
 * @param multiplier         factor applied to the backoff after each failure
 * @param maxInterval        ceiling for the computed backoff
 * @param deadLetterExchange direct exchange exhausted messages are republished to
 */
@NullMarked
public record RabbitMqRedeliveryConfig(
        int maxAttempts,
        Duration initialInterval,
        double multiplier,
        Duration maxInterval,
        String deadLetterExchange
) {

    /**
     * Validates the redelivery policy.
     *
     * @throws IllegalArgumentException if any value is out of range
     */
    public RabbitMqRedeliveryConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, but was " + maxAttempts);
        }
        if (initialInterval.isNegative() || maxInterval.isNegative()) {
            throw new IllegalArgumentException("redelivery intervals must not be negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0, but was " + multiplier);
        }
    }

    /** @return the defaults documented on {@code maestro.messaging.redelivery.*} */
    public static RabbitMqRedeliveryConfig defaults() {
        return new RabbitMqRedeliveryConfig(
                10, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), "maestro.dead-letter");
    }
}
