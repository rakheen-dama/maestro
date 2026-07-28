package io.b2mash.maestro.samples.loan.underwriting.domain;

/**
 * A review round currently waiting on a human decision, as exposed by
 * {@code GET /underwriting/pending}.
 *
 * @param loanId    the loan application ID
 * @param round     the 1-based review round
 * @param dti       the computed debt-to-income ratio that routed this
 *                  application to the human queue
 * @param escalated whether the review has been escalated to a senior
 *                  underwriter after the first decision timeout
 */
public record PendingReview(String loanId, int round, double dti, boolean escalated) {
}
