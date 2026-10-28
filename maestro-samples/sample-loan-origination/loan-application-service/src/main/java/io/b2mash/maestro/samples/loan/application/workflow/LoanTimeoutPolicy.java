package io.b2mash.maestro.samples.loan.application.workflow;

import java.time.Duration;

/**
 * The loan workflow's wait durations, produced by
 * {@code LoanPolicyActivities.timeouts()} and therefore memoized in the
 * workflow history: the values a run started with are the values it replays
 * with, even if the deployment's configuration changes mid-flight.
 *
 * <p>Immutable; safe to share across threads.
 *
 * @param docTimeout      how long to wait for required/condition documents
 * @param signTimeout     how long to wait for closing-package signatures
 * @param decisionTimeout how long to wait for verification/underwriting decisions
 * @param gateTimeout     short await at each withdrawal gate (timeout = not withdrawn)
 */
public record LoanTimeoutPolicy(
        Duration docTimeout,
        Duration signTimeout,
        Duration decisionTimeout,
        Duration gateTimeout) {

    /** SPEC defaults: 10 minutes for every human wait, 1 second at each withdrawal gate. */
    public static final LoanTimeoutPolicy SPEC_DEFAULTS = new LoanTimeoutPolicy(
            Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(1));

    /**
     * @throws IllegalArgumentException if any timeout is null, zero or negative
     *                                  (an invalid deployment configuration must
     *                                  fail loudly instead of producing instant
     *                                  workflow timeouts)
     */
    public LoanTimeoutPolicy {
        requirePositive("doc", docTimeout);
        requirePositive("sign", signTimeout);
        requirePositive("decision", decisionTimeout);
        requirePositive("gate", gateTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "%s timeout must be positive, got %s".formatted(name, value));
        }
    }
}
