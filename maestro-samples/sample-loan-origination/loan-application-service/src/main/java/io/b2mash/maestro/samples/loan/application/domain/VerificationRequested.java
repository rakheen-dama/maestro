package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Kafka event published to {@code loans.verification.requests} — one per
 * verification type. Consumed by the verification-gateway service.
 *
 * @param loanId the loan application id (also the Kafka message key)
 * @param type   verification type: {@code credit}, {@code employment} or {@code appraisal}
 * @param amount the requested loan amount (drives the gateway's simulated outcomes)
 */
public record VerificationRequested(
        String loanId,
        String type,
        long amount
) {}
