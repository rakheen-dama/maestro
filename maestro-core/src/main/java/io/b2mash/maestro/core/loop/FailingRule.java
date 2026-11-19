package io.b2mash.maestro.core.loop;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * When a loop's failure streak counts as <em>failing</em> in its
 * {@link LoopSnapshot}: at least {@code minStreak} failures in a row, and the
 * streak at least {@code minDuration} old. Both, so a loop whose iterations
 * fail and succeed in turn never reads failing. Internal until 1.0 (see the
 * package Javadoc).
 *
 * @param minStreak   the least number of consecutive failures; at least 1
 * @param minDuration the least age of the streak (since its first failure);
 *                    zero or positive
 */
public record FailingRule(int minStreak, Duration minDuration) {

    /**
     * @throws IllegalArgumentException if {@code minStreak} is below 1 or
     *                                  {@code minDuration} is negative
     */
    public FailingRule {
        Objects.requireNonNull(minDuration, "minDuration");
        if (minStreak < 1) {
            throw new IllegalArgumentException("minStreak must be at least 1, got " + minStreak);
        }
        if (minDuration.isNegative()) {
            throw new IllegalArgumentException("minDuration must not be negative, got " + minDuration);
        }
    }

    /**
     * Failing after {@code n} failures in a row, however quickly they came.
     *
     * @param n the least number of consecutive failures
     * @return the rule
     */
    public static FailingRule streak(int n) {
        return new FailingRule(n, Duration.ZERO);
    }

    /**
     * Failing after {@code n} failures in a row spanning at least {@code age}.
     *
     * @param n   the least number of consecutive failures
     * @param age the least age of the streak
     * @return the rule
     */
    public static FailingRule streakFor(int n, Duration age) {
        return new FailingRule(n, age);
    }

    /**
     * Applies the rule.
     *
     * @param consecutiveFailures the current streak
     * @param streakAge           how long ago the streak's first failure was;
     *                            {@code null} when there is no streak
     * @return {@code true} when the streak counts as failing
     */
    public boolean isFailing(int consecutiveFailures, @Nullable Duration streakAge) {
        return consecutiveFailures >= minStreak && streakAge != null && streakAge.compareTo(minDuration) >= 0;
    }
}
