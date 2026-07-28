package io.b2mash.maestro.samples.loan.verification.domain;

/**
 * Verification outcome published to the {@code loans.verification.results}
 * Kafka topic (keyed by {@code loanId}).
 *
 * <p>The loan-application service routes this event into the
 * {@code loan-{loanId}} workflow as a {@code verification.result} signal.
 * Field names and order are part of the cross-service contract defined in
 * {@code SPEC.md} — do not change them.
 *
 * @param loanId   the loan application id
 * @param type     the verification type: {@code credit}, {@code employment}
 *                 or {@code appraisal}
 * @param approved whether the verification passed
 * @param details  human-readable outcome detail
 */
public record VerificationResult(String loanId, String type, boolean approved, String details) {}
