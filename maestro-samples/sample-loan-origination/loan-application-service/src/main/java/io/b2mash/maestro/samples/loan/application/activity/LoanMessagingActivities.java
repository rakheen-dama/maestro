package io.b2mash.maestro.samples.loan.application.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;

import java.util.List;

/**
 * Kafka-publishing activities, kept behind an interface so tests can register
 * an in-memory fake (same pattern as the existing samples' messaging activities).
 */
@Activity
public interface LoanMessagingActivities {

    /** The three verification types requested for every application. */
    List<String> VERIFICATION_TYPES = List.of("credit", "employment", "appraisal");

    /**
     * Publishes one {@code VerificationRequested} event per verification type
     * to {@code loans.verification.requests}.
     */
    void requestVerifications(LoanApplication application);

    /**
     * Publishes an {@code UnderwritingRequested} event for the given review
     * round to {@code loans.underwriting.requests}.
     */
    void requestUnderwriting(LoanApplication application, int round);
}
