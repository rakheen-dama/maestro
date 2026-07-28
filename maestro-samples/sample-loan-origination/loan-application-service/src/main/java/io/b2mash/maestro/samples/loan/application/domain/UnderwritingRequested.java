package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Kafka event published to {@code loans.underwriting.requests}. Consumed by
 * the underwriting service, which starts {@code underwriting-{loanId}-round{n}}.
 *
 * @param loanId                the loan application id (also the Kafka message key)
 * @param round                 1-based review round
 * @param amount                requested loan amount
 * @param income                annual borrower income (for DTI = amount / income)
 * @param propertyValue         appraised property value
 * @param verificationsApproved always {@code true} when requested — the
 *                              application workflow fails before underwriting
 *                              if any verification was declined
 */
public record UnderwritingRequested(
        String loanId,
        int round,
        long amount,
        long income,
        long propertyValue,
        boolean verificationsApproved
) {}
