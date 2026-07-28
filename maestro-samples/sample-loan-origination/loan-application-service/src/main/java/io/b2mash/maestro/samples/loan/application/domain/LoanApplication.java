package io.b2mash.maestro.samples.loan.application.domain;

import java.util.List;

/**
 * Workflow input for {@code LoanApplicationWorkflow}.
 *
 * @param applicationId unique application identifier (also the loan ID in cross-service events)
 * @param borrowerIds   one or two borrowers who must each sign the closing package
 * @param amount        requested loan amount
 * @param income        annual borrower income (used by underwriting for DTI)
 * @param propertyValue appraised property value
 * @param requiredDocs  document types the borrowers must upload before underwriting
 */
public record LoanApplication(
        String applicationId,
        List<String> borrowerIds,
        long amount,
        long income,
        long propertyValue,
        List<String> requiredDocs
) {}
