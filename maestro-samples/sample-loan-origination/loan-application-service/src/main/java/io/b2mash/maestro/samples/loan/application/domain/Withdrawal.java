package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Payload of the {@code application.withdrawn} signal (delivered via REST).
 *
 * @param loanId the loan application id
 * @param reason why the borrower withdrew
 */
public record Withdrawal(
        String loanId,
        String reason
) {}
