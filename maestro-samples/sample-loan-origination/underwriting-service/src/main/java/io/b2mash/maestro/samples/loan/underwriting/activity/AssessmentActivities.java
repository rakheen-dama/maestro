package io.b2mash.maestro.samples.loan.underwriting.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.underwriting.domain.AssessmentOutcome;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingRequest;

/**
 * Assessment activities for the underwriting workflow.
 *
 * <p>Behind an interface so tests can substitute fakes while the workflow
 * code stays identical (same pattern as the other samples' activities).
 */
@Activity
public interface AssessmentActivities {

    /**
     * Applies the automatic underwriting rules to the request.
     *
     * @param request the underwriting request
     * @return the rule outcome (auto-approve, auto-reject, or human review)
     */
    AssessmentOutcome autoAssess(UnderwritingRequest request);

    /**
     * Records that the review timed out at the underwriter desk and has been
     * escalated to a senior underwriter.
     *
     * @param loanId the loan application ID
     * @param round  the review round being escalated
     */
    void escalate(String loanId, int round);
}
