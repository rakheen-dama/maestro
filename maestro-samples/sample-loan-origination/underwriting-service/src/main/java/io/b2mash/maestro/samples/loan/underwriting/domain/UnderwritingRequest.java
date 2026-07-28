package io.b2mash.maestro.samples.loan.underwriting.domain;

/**
 * A request for one underwriting review round, consumed from the
 * {@code loans.underwriting.requests} Kafka topic (published by
 * loan-application-service) and used as the workflow input for
 * {@code underwriting-{loanId}-round{n}}.
 *
 * @param loanId                the loan application ID (also the Kafka key)
 * @param round                 the 1-based review round (round 2+ after a
 *                              CONDITIONS verdict)
 * @param amount                the requested loan amount
 * @param income                the applicant's annual income
 * @param verificationsApproved whether all third-party verifications
 *                              (credit, employment, appraisal) were approved
 */
public record UnderwritingRequest(
        String loanId,
        int round,
        long amount,
        long income,
        boolean verificationsApproved
) {
}
