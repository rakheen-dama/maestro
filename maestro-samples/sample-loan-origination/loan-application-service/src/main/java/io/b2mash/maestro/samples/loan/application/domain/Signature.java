package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Payload of the {@code package.signed} signal (delivered via REST).
 *
 * @param loanId   the loan application id
 * @param signerId the borrower who signed the closing package
 */
public record Signature(
        String loanId,
        String signerId
) {}
