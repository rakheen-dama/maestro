package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Workflow output for {@code LoanApplicationWorkflow}.
 *
 * @param applicationId  the application this result belongs to
 * @param status         terminal business status ({@code "FUNDED"} on success)
 * @param rateLockId     the rate lock reserved during funding
 * @param disbursementId the disbursement transaction id
 */
public record LoanResult(
        String applicationId,
        String status,
        String rateLockId,
        String disbursementId
) {}
