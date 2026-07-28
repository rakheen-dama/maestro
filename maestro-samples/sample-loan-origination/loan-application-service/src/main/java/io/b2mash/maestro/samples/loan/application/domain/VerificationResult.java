package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Payload of the {@code verification.result} signal (and the Kafka event on
 * {@code loans.verification.results}).
 *
 * @param loanId   the loan application id
 * @param type     verification type: {@code credit}, {@code employment} or {@code appraisal}
 * @param approved whether the verification passed
 * @param details  human-readable provider details
 */
public record VerificationResult(
        String loanId,
        String type,
        boolean approved,
        String details
) {}
