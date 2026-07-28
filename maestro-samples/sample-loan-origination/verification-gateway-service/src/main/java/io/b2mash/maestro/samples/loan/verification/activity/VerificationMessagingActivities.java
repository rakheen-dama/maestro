package io.b2mash.maestro.samples.loan.verification.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;

/**
 * Activities publishing verification results to downstream consumers.
 *
 * <p>Behind an interface so tests can use an in-memory fake instead of Kafka
 * (same pattern as the existing samples' messaging activities).
 */
@Activity
public interface VerificationMessagingActivities {

    /**
     * Publishes the verification result to the
     * {@code loans.verification.results} topic, keyed by {@code loanId}.
     *
     * @param result the verification outcome
     */
    void publishResult(VerificationResult result);
}
