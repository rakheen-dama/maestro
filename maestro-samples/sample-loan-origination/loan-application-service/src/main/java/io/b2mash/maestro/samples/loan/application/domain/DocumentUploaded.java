package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Payload of the {@code document.uploaded} signal (delivered via REST).
 *
 * @param loanId     the loan application id
 * @param docType    document type (e.g. {@code payslip})
 * @param uploadedBy the borrower who uploaded it
 */
public record DocumentUploaded(
        String loanId,
        String docType,
        String uploadedBy
) {}
