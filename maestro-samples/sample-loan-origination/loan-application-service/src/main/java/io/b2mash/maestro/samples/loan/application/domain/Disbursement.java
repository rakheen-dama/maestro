package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Result of disbursing loan funds.
 *
 * @param disbursementId the disbursement transaction id (needed by the saga compensation)
 * @param applicationId  the loan application the disbursement belongs to
 * @param amount         the disbursed amount
 */
public record Disbursement(
        String disbursementId,
        String applicationId,
        long amount
) {}
