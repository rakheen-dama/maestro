package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;

import java.time.Duration;

/**
 * Redelivery policy for {@link PostgresWorkflowMessaging}.
 *
 * <p>A handler exception means the message was not processed — on the engine
 * signal channel it means the signal is not yet durable — so the queue row must
 * not be retired. Instead it goes back to {@code PENDING} with an incremented
 * attempt count and a future {@code next_attempt_at}, and only once the attempt
 * budget is exhausted does it park in {@code DEAD_LETTER}, where it stays
 * inspectable and replayable.
 *
 * <p>The delay before the attempt following the <i>n</i>-th failure is
 * {@code min(initialInterval × multiplier^(n-1), maxInterval)};
 * {@code maxAttempts} counts the initial attempt.
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param maxAttempts     total delivery attempts, including the first
 * @param initialInterval backoff before the second attempt
 * @param multiplier      factor applied to the backoff after each failure
 * @param maxInterval     ceiling for the computed backoff
 */
@NullMarked
public record PostgresRedeliveryConfig(
        int maxAttempts,
        Duration initialInterval,
        double multiplier,
        Duration maxInterval
) {

    /**
     * Validates the policy.
     *
     * @throws IllegalArgumentException if any value is out of range
     */
    public PostgresRedeliveryConfig {
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
    public static PostgresRedeliveryConfig defaults() {
        return new PostgresRedeliveryConfig(10, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30));
    }

    /**
     * Computes how long to wait before the attempt that follows a failure.
     *
     * @param failedAttempts how many attempts have failed so far (at least 1)
     * @return the backoff, capped at {@link #maxInterval()}
     */
    public Duration backoffAfter(int failedAttempts) {
        var exponent = Math.max(0, failedAttempts - 1);
        var millis = initialInterval.toMillis() * Math.pow(multiplier, exponent);
        return millis >= maxInterval.toMillis()
                ? maxInterval
                : Duration.ofMillis((long) millis);
    }
}
