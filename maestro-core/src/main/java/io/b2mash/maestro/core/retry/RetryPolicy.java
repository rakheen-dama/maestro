package io.b2mash.maestro.core.retry;

import java.time.Duration;
import java.util.List;

/**
 * Immutable retry configuration for activity invocations.
 *
 * <p>Defines how the activity proxy retries failed calls before recording
 * a permanent failure. Uses exponential backoff:
 * {@code delay = min(initialInterval × backoffMultiplier^attempt, maxInterval)}.
 *
 * <h2>Exception Filtering</h2>
 * <ul>
 *   <li>If {@link #nonRetryableExceptions()} matches, the exception is never retried
 *       (takes precedence).</li>
 *   <li>If {@link #retryableExceptions()} is non-empty, only matching exceptions are retried.</li>
 *   <li>If both lists are empty, all exceptions are retried.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param maxAttempts           maximum number of attempts (including initial)
 * @param initialInterval       initial delay between retries
 * @param maxInterval           maximum delay between retries
 * @param backoffMultiplier     multiplier applied after each attempt
 * @param retryableExceptions   exception types to retry (empty = retry all)
 * @param nonRetryableExceptions exception types that should never be retried
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialInterval,
        Duration maxInterval,
        double backoffMultiplier,
        List<Class<? extends Throwable>> retryableExceptions,
        List<Class<? extends Throwable>> nonRetryableExceptions
) {

    /** Canonical constructor with defensive copies and validation. */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (initialInterval == null) {
            throw new IllegalArgumentException("initialInterval must not be null");
        }
        if (maxInterval == null) {
            throw new IllegalArgumentException("maxInterval must not be null");
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
        retryableExceptions = List.copyOf(retryableExceptions);
        nonRetryableExceptions = List.copyOf(nonRetryableExceptions);
    }

    /** Default policy: 3 attempts, 1s initial, 1m max, 2× backoff. */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(
                3,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                2.0,
                List.of(),
                List.of()
        );
    }

    /** No retries — fail immediately on first exception. */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(
                1,
                Duration.ZERO,
                Duration.ZERO,
                1.0,
                List.of(),
                List.of()
        );
    }

    /**
     * Converts an {@link io.b2mash.maestro.core.annotation.RetryPolicy} annotation
     * to this runtime configuration record.
     *
     * @param annotation the annotation to convert
     * @return the runtime retry policy
     */
    public static RetryPolicy fromAnnotation(io.b2mash.maestro.core.annotation.RetryPolicy annotation) {
        return new RetryPolicy(
                annotation.maxAttempts(),
                Duration.parse(annotation.initialInterval()),
                Duration.parse(annotation.maxInterval()),
                annotation.backoffMultiplier(),
                List.of(annotation.retryableExceptions()),
                List.of(annotation.nonRetryableExceptions())
        );
    }

    /** Returns a new builder for programmatic construction. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing {@link RetryPolicy} instances programmatically.
     */
    public static final class Builder {

        private int maxAttempts = 3;
        private Duration initialInterval = Duration.ofSeconds(1);
        private Duration maxInterval = Duration.ofMinutes(1);
        private double backoffMultiplier = 2.0;
        private List<Class<? extends Throwable>> retryableExceptions = List.of();
        private List<Class<? extends Throwable>> nonRetryableExceptions = List.of();

        private Builder() {}

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
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

        public Builder retryableExceptions(List<Class<? extends Throwable>> retryableExceptions) {
            this.retryableExceptions = retryableExceptions;
            return this;
        }

        public Builder nonRetryableExceptions(List<Class<? extends Throwable>> nonRetryableExceptions) {
            this.nonRetryableExceptions = nonRetryableExceptions;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(
                    maxAttempts, initialInterval, maxInterval,
                    backoffMultiplier, retryableExceptions, nonRetryableExceptions
            );
        }
    }
}
