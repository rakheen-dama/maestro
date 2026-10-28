package io.b2mash.maestro.samples.loan.underwriting.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.loan.underwriting.activity.AssessmentActivities;
import io.b2mash.maestro.samples.loan.underwriting.activity.DecisionMessagingActivities;
import io.b2mash.maestro.samples.loan.underwriting.activity.UnderwritingPolicyActivities;
import io.b2mash.maestro.samples.loan.underwriting.domain.Decision;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingRequest;
import io.b2mash.maestro.samples.loan.underwriting.domain.Verdict;

import java.util.List;

/**
 * Durable workflow for one underwriting review round
 * (workflow ID {@code underwriting-{loanId}-round{n}}).
 *
 * <ol>
 *   <li>{@code autoAssess} — deterministic DTI rules. Clear-cut cases are
 *       decided automatically; borderline cases go to the human queue.</li>
 *   <li>Human path — await {@code underwriter.decision}; on timeout,
 *       {@code escalate} and await {@code senior.decision}; a second
 *       timeout yields REJECTED("no decision").</li>
 *   <li>{@code publishDecision} — publish the outcome (always carrying the
 *       round from the workflow input) to {@code loans.underwriting.decisions}.</li>
 * </ol>
 *
 * <p><b>Decision-as-payload idiom (mandatory design idiom #1):</b> each
 * decision point awaits exactly ONE signal name — {@code underwriter.decision}
 * at the underwriter desk, {@code senior.decision} after escalation. The
 * verdict (APPROVED / REJECTED / CONDITIONS) lives in the {@link Decision}
 * payload, never in the signal name. Modelling approve/reject as competing
 * signal names would leave the workflow racing multiple awaits and silently
 * dropping the "losing" signal; with one name per decision point every
 * outcome flows through the same await.
 *
 * <p><b>Determinism:</b> the code between activity calls is pure — no clock
 * reads, no randomness, no I/O. Timeouts come from a memoized
 * policy activity, and every side effect (assessment, escalation log, Kafka
 * publish) is behind a memoized activity.
 */
@DurableWorkflow(name = "underwriting", taskQueue = "underwriting")
public class UnderwritingWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private AssessmentActivities assessment;

    // Publishing the decision is critical — losing it would leave the loan
    // workflow waiting forever. Aggressive retry rides out Kafka blips.
    @ActivityStub(startToCloseTimeout = "PT10S",
                  retryPolicy = @RetryPolicy(
                          maxAttempts = 30,
                          initialInterval = "PT1S",
                          maxInterval = "PT2M",
                          backoffMultiplier = 2.0
                  ))
    private DecisionMessagingActivities messaging;

    // Read ONCE at the top of review(): the configured timeouts are memoized in
    // history, so they are deterministic on replay.
    @ActivityStub(startToCloseTimeout = "PT10S")
    private UnderwritingPolicyActivities policy;

    // Volatile: read by @QueryMethod from caller's thread, written by workflow's virtual thread
    private volatile String currentStep = "RECEIVED";

    @WorkflowMethod
    public UnderwritingDecision review(UnderwritingRequest request) {
        var timeouts = policy.timeouts();

        currentStep = "AUTO_ASSESSING";
        var outcome = assessment.autoAssess(request);

        var decision = switch (outcome) {
            case AUTO_APPROVE -> decide(request, Verdict.APPROVED, List.of());
            case AUTO_REJECT -> decide(request, Verdict.REJECTED,
                    List.of("debt-to-income ratio too high"));
            case HUMAN_REVIEW -> awaitHumanDecision(request, timeouts);
        };

        currentStep = "PUBLISHING_DECISION";
        messaging.publishDecision(decision);

        currentStep = "DECIDED_" + decision.verdict();
        return decision;
    }

    /**
     * Human decision path: underwriter first, senior underwriter after a
     * timeout escalation, REJECTED("no decision") after a second timeout.
     */
    private UnderwritingDecision awaitHumanDecision(UnderwritingRequest request,
                                                    UnderwritingTimeoutPolicy timeouts) {
        var workflow = WorkflowContext.current();

        currentStep = "AWAITING_UNDERWRITER";
        try {
            // Single signal name; verdict arrives in the payload (idiom #1).
            var decision = workflow.awaitSignal(
                    "underwriter.decision", Decision.class, timeouts.underwriterTimeout());
            return decide(request, decision.verdict(), decision.conditions());
        } catch (SignalTimeoutException underwriterTimedOut) {
            currentStep = "ESCALATING";
            assessment.escalate(request.loanId(), request.round());

            currentStep = "AWAITING_SENIOR";
            try {
                var decision = workflow.awaitSignal(
                        "senior.decision", Decision.class, timeouts.seniorTimeout());
                return decide(request, decision.verdict(), decision.conditions());
            } catch (SignalTimeoutException seniorTimedOut) {
                currentStep = "NO_DECISION";
                return decide(request, Verdict.REJECTED, List.of("no decision"));
            }
        }
    }

    private static UnderwritingDecision decide(UnderwritingRequest request,
                                               Verdict verdict, List<String> conditions) {
        // The round always comes from the workflow input (SPEC contract).
        return new UnderwritingDecision(request.loanId(), request.round(), verdict, conditions);
    }

    @QueryMethod
    public String getStatus() {
        return currentStep;
    }
}
