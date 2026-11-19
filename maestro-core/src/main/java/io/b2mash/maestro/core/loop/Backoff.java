package io.b2mash.maestro.core.loop;

import java.time.Duration;
import java.util.Objects;

/**
 * The wait after a failed iteration, as a function of the failure streak.
 * Internal until 1.0 (see the package Javadoc).
 *
 * <p><b>Thread safety:</b> immutable.
 */
public sealed interface Backoff {

    /**
     * The wait after the {@code consecutiveFailures}-th failure in a row.
     *
     * @param consecutiveFailures the streak, including this failure; at least 1
     * @return the wait before the next iteration; never negative
     */
    Duration after(int consecutiveFailures);

    /**
     * The same wait after every failure — the engine loops' schedule, which a
     * failure does not change.
     *
     * @param wait the wait; zero or positive
     * @return a fixed back-off
     * @throws IllegalArgumentException if {@code wait} is negative
     */
    static Backoff fixed(Duration wait) {
        return new Fixed(wait);
    }

    /**
     * {@code initial} after the first failure, doubling after each further
     * one, at most {@code max}.
     *
     * @param initial the wait after the first failure; positive
     * @param max     the longest wait; at least {@code initial}
     * @return an exponential back-off
     * @throws IllegalArgumentException if {@code initial} is not positive or
     *                                  {@code max} is shorter than it
     */
    static Backoff exponential(Duration initial, Duration max) {
        return new Exponential(initial, max);
    }

    /**
     * The same wait after every failure.
     *
     * @param delay the wait; zero or positive
     */
    record Fixed(Duration delay) implements Backoff {

        /** @throws IllegalArgumentException if {@code delay} is negative */
        public Fixed {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative, got " + delay);
            }
        }

        @Override
        public Duration after(int consecutiveFailures) {
            return delay;
        }
    }

    /**
     * Doubling waits, capped.
     *
     * @param initial the wait after the first failure
     * @param max     the longest wait
     */
    record Exponential(Duration initial, Duration max) implements Backoff {

        /** @throws IllegalArgumentException if {@code initial} is not positive or {@code max} is shorter */
        public Exponential {
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(max, "max");
            if (!initial.isPositive() || max.compareTo(initial) < 0) {
                throw new IllegalArgumentException("need 0 < initial <= max, got " + initial + ", " + max);
            }
        }

        @Override
        public Duration after(int consecutiveFailures) {
            var wait = initial;
            for (int i = 1; i < consecutiveFailures && wait.compareTo(max) < 0; i++) {
                wait = wait.multipliedBy(2);
            }
            return wait.compareTo(max) < 0 ? wait : max;
        }
    }
}
