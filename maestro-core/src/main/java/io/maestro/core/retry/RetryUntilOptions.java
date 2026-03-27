package io.maestro.core.retry;

import java.time.Duration;

/**
 * Immutable configuration for {@code workflow.retryUntil()} polling loops.
 *
 * <p>Controls how many times the supplier is called, the maximum wall-clock
 * duration for the entire loop, and the exponential backoff between attempts.
 * Each backoff interval creates a durable timer via {@code workflow.sleep()},
 * so the retry loop survives JVM restarts.
 *
 * <h2>Backoff Calculation</h2>
 * <p>{@code delay = min(initialInterval × backoffMultiplier^attempt, maxInterval)}
 *
 * <h2>Distinction from {@link RetryPolicy}</h2>
 * <p>{@link RetryPolicy} retries on <b>exceptions</b> (the activity failed).
 * {@code RetryUntilOptions} retries on <b>predicate failure</b> (the activity
 * succeeded but returned an unsatisfactory result). They serve different layers.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param maxAttempts       maximum number of supplier calls (must be &ge; 1)
 * @param maxDuration       maximum total wall-clock duration for the loop
 * @param initialInterval   initial delay between retry attempts
 * @param maxInterval       maximum delay between retry attempts
 * @param backoffMultiplier multiplier applied after each attempt (must be &ge; 1.0)
 */
public record RetryUntilOptions(
        int maxAttempts,
        Duration maxDuration,
        Duration initialInterval,
        Duration maxInterval,
        double backoffMultiplier
) {

    /** Canonical constructor with validation. */
    public RetryUntilOptions {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (maxDuration == null) {
            throw new IllegalArgumentException("maxDuration must not be null");
        }
        if (initialInterval == null) {
            throw new IllegalArgumentException("initialInterval must not be null");
        }
        if (maxInterval == null) {
            throw new IllegalArgumentException("maxInterval must not be null");
        }
        if (maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must not be negative, got " + maxDuration);
        }
        if (initialInterval.isNegative()) {
            throw new IllegalArgumentException("initialInterval must not be negative, got " + initialInterval);
        }
        if (maxInterval.isNegative()) {
            throw new IllegalArgumentException("maxInterval must not be negative, got " + maxInterval);
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0, got " + backoffMultiplier);
        }
    }

    /** Default options: 100 attempts, 24h max, 5s initial, 5m max, 2× backoff. */
    public static RetryUntilOptions defaultOptions() {
        return new RetryUntilOptions(
                100,
                Duration.ofHours(24),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                2.0
        );
    }

    /** Returns a new builder for programmatic construction. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing {@link RetryUntilOptions} instances programmatically.
     */
    public static final class Builder {

        private int maxAttempts = 100;
        private Duration maxDuration = Duration.ofHours(24);
        private Duration initialInterval = Duration.ofSeconds(5);
        private Duration maxInterval = Duration.ofMinutes(5);
        private double backoffMultiplier = 2.0;

        private Builder() {}

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Builder initialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
            return this;
        }

        public Builder maxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public RetryUntilOptions build() {
            return new RetryUntilOptions(
                    maxAttempts, maxDuration, initialInterval,
                    maxInterval, backoffMultiplier
            );
        }
    }
}
