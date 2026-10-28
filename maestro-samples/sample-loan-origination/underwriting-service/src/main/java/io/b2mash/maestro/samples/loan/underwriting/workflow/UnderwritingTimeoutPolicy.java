package io.b2mash.maestro.samples.loan.underwriting.workflow;

import java.time.Duration;

/**
 * The underwriting workflow's human-decision timeouts, produced by
 * {@code UnderwritingPolicyActivities.timeouts()} and so memoized in the
 * workflow history (deterministic on replay).
 *
 * <p>Immutable; safe to share across threads.
 *
 * @param underwriterTimeout how long to wait for the underwriter's decision
 *                           before escalating
 * @param seniorTimeout      how long to wait for the senior underwriter's
 *                           decision before rejecting with "no decision"
 */
public record UnderwritingTimeoutPolicy(Duration underwriterTimeout, Duration seniorTimeout) {

    /** SPEC defaults: 10 minutes per human decision desk. */
    public static final UnderwritingTimeoutPolicy SPEC_DEFAULTS = new UnderwritingTimeoutPolicy(
            Duration.ofMinutes(10), Duration.ofMinutes(10));

    /**
     * @throws IllegalArgumentException if either timeout is null, zero or negative
     */
    public UnderwritingTimeoutPolicy {
        requirePositive("underwriter", underwriterTimeout);
        requirePositive("senior", seniorTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "%s timeout must be positive, got %s".formatted(name, value));
        }
    }
}
