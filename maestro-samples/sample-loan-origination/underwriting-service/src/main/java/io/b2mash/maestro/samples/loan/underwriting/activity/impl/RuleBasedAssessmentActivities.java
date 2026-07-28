package io.b2mash.maestro.samples.loan.underwriting.activity.impl;

import io.b2mash.maestro.samples.loan.underwriting.activity.AssessmentActivities;
import io.b2mash.maestro.samples.loan.underwriting.domain.AssessmentOutcome;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingRequest;
import io.b2mash.maestro.samples.loan.underwriting.queue.PendingReviewRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic rule-based assessment (per SPEC):
 * <ul>
 *   <li>DTI = amount / income</li>
 *   <li>DTI &lt; 3 and all verifications approved → AUTO_APPROVE</li>
 *   <li>DTI &gt; 6 → AUTO_REJECT</li>
 *   <li>everything else → HUMAN_REVIEW (queued for an underwriter)</li>
 * </ul>
 */
@Component
public class RuleBasedAssessmentActivities implements AssessmentActivities {

    private static final Logger logger = LoggerFactory.getLogger(RuleBasedAssessmentActivities.class);

    private static final double AUTO_APPROVE_BELOW = 3.0;
    private static final double AUTO_REJECT_ABOVE = 6.0;

    private final PendingReviewRegistry registry;

    public RuleBasedAssessmentActivities(PendingReviewRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AssessmentOutcome autoAssess(UnderwritingRequest request) {
        // Non-positive income cannot service any loan — treat as unbounded DTI.
        var dti = request.income() <= 0
                ? Double.POSITIVE_INFINITY
                : (double) request.amount() / request.income();

        AssessmentOutcome outcome;
        if (dti > AUTO_REJECT_ABOVE) {
            outcome = AssessmentOutcome.AUTO_REJECT;
        } else if (dti < AUTO_APPROVE_BELOW && request.verificationsApproved()) {
            outcome = AssessmentOutcome.AUTO_APPROVE;
        } else {
            outcome = AssessmentOutcome.HUMAN_REVIEW;
            registry.queued(request.loanId(), request.round(), dti);
        }

        // DTI and verification details are sensitive financial data — log the
        // outcome at INFO and keep diagnostics at DEBUG.
        logger.info("Auto-assessed loan {} round {} -> {}",
                request.loanId(), request.round(), outcome);
        logger.debug("Assessment detail for loan {} round {}: DTI={} verificationsApproved={}",
                request.loanId(), request.round(), dti, request.verificationsApproved());
        return outcome;
    }

    @Override
    public void escalate(String loanId, int round) {
        registry.escalated(loanId, round);
        logger.warn("Underwriter decision timed out for loan {} round {} — escalated to senior underwriter",
                loanId, round);
    }
}
