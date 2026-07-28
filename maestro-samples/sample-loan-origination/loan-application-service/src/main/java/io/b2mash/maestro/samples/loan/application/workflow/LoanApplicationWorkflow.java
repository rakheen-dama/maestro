package io.b2mash.maestro.samples.loan.application.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.loan.application.activity.FundingActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanMessagingActivities;
import io.b2mash.maestro.samples.loan.application.domain.DocumentUploaded;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.LoanResult;
import io.b2mash.maestro.samples.loan.application.domain.Signature;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.application.domain.VerificationResult;
import io.b2mash.maestro.samples.loan.application.domain.Withdrawal;
import io.b2mash.maestro.samples.loan.application.exception.LoanDeclinedException;
import io.b2mash.maestro.samples.loan.application.exception.LoanRejectedException;
import io.b2mash.maestro.samples.loan.application.exception.LoanWithdrawnException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Long-lived loan origination orchestrator (workflow ID {@code loan-{applicationId}}).
 *
 * <p>Implements SPEC steps 1–8: record → request verifications → verification
 * fan-in → document collection → withdrawal gate #1 → underwriting rounds →
 * funding saga (rate lock, signatures, withdrawal gate #2, disbursement).
 *
 * <p>Determinism: workflow code between activity calls uses only deterministic
 * constructs — no direct I/O, {@code Math.random()}, {@code Instant.now()} or
 * {@code UUID.randomUUID()}. Time/UUIDs would come from
 * {@code workflow.currentTime()} / {@code workflow.randomUUID()} if needed.
 */
@DurableWorkflow(name = "loan-application", taskQueue = "loan-application")
public class LoanApplicationWorkflow {

    /** Idiom 3 bound: max verification.result signals consumed before giving up. */
    static final int MAX_VERIFICATION_SIGNALS = 10;
    /** Max underwriting review rounds; CONDITIONS on the final round = REJECTED. */
    static final int MAX_UNDERWRITING_ROUNDS = 3;
    /** Bound on total package.signed signals consumed (dedupe tolerance). */
    static final int MAX_SIGNATURE_SIGNALS = 5;

    static final String VERDICT_APPROVED = "APPROVED";
    static final String VERDICT_REJECTED = "REJECTED";
    static final String VERDICT_CONDITIONS = "CONDITIONS";

    @ActivityStub(startToCloseTimeout = "PT10S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private LoanActivities loan;

    @ActivityStub(startToCloseTimeout = "PT10S",
                  retryPolicy = @RetryPolicy(maxAttempts = 5, initialInterval = "PT1S"))
    private LoanMessagingActivities messaging;

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private FundingActivities funding;

    // Volatile: read by @QueryMethod from caller threads, written by the workflow's virtual thread
    private volatile String currentStep = "CREATED";

    @WorkflowMethod
    @Saga(parallelCompensation = false)
    public LoanResult process(LoanApplication application) {
        var workflow = WorkflowContext.current();

        // Step 1: validate + persist demo state
        currentStep = "RECORDING_APPLICATION";
        loan.recordApplication(application);

        // Step 2: one verification request per type → loans.verification.requests
        currentStep = "REQUESTING_VERIFICATIONS";
        messaging.requestVerifications(application);

        // Step 3 — DESIGN IDIOM 3 (any-order fan-in): all verification results
        // arrive on ONE signal name ("verification.result"); loop awaitSignal
        // until all three types have been seen, bounded at 10 iterations,
        // tolerating duplicates and any arrival order. (@MaestroSignalListener
        // binds one signalName per method — never per-type signal names.)
        currentStep = "COLLECTING_VERIFICATIONS";
        collectVerificationResults(workflow);

        // Step 4: collect the required documents (any borrower, any order)
        currentStep = "COLLECTING_DOCUMENTS";
        workflow.collectSignals("document.uploaded", DocumentUploaded.class,
                application.requiredDocs().size(), LoanTimeouts.docTimeout());

        // Step 5 — DESIGN IDIOM 2 (withdrawal gate #1, before underwriting
        // submission): nothing reserved yet, so a withdrawal here fails the
        // workflow without any saga compensation.
        currentStep = "WITHDRAWAL_GATE_1";
        checkWithdrawalGate(workflow);

        // Step 6: underwriting rounds (max 3)
        runUnderwritingRounds(workflow, application);

        // Step 7: funding saga — reserveRateLock/disburse declare @Compensate
        // methods; the @Saga workflow method unwinds them (LIFO) on failure.
        currentStep = "RESERVING_RATE_LOCK";
        var rateLock = funding.reserveRateLock(application.applicationId(), application.amount());

        currentStep = "COLLECTING_SIGNATURES";
        collectSignatures(workflow, application);

        // DESIGN IDIOM 2 (withdrawal gate #2, before disbursement): a
        // withdrawal here throws → saga compensation releases the rate lock.
        currentStep = "WITHDRAWAL_GATE_2";
        checkWithdrawalGate(workflow);

        currentStep = "DISBURSING";
        var disbursement = funding.disburse(application.applicationId(), application.amount());

        // Step 8
        currentStep = "FUNDED";
        return new LoanResult(application.applicationId(), "FUNDED",
                rateLock.lockId(), disbursement.disbursementId());
    }

    @QueryMethod(name = "getStatus")
    public String getStatus() {
        return currentStep;
    }

    // ── Step 3: verification fan-in (idiom 3) ───────────────────────────

    private void collectVerificationResults(WorkflowContext workflow) {
        var expectedTypes = LoanMessagingActivities.VERIFICATION_TYPES;
        var verifiedTypes = new LinkedHashSet<String>();

        for (int i = 0; i < MAX_VERIFICATION_SIGNALS && verifiedTypes.size() < expectedTypes.size(); i++) {
            var result = workflow.awaitSignal("verification.result", VerificationResult.class,
                    LoanTimeouts.decisionTimeout());
            if (!result.approved()) {
                // Any declined verification fails the workflow (no saga yet —
                // nothing to compensate).
                throw new LoanDeclinedException("Verification '%s' declined: %s"
                        .formatted(result.type(), result.details()));
            }
            verifiedTypes.add(result.type());
        }

        if (verifiedTypes.size() < expectedTypes.size()) {
            throw new LoanDeclinedException(
                    "Verification fan-in consumed %d signals without seeing all of %s (got %s)"
                            .formatted(MAX_VERIFICATION_SIGNALS, expectedTypes, verifiedTypes));
        }
    }

    // ── Step 6: underwriting rounds ─────────────────────────────────────

    private void runUnderwritingRounds(WorkflowContext workflow, LoanApplication application) {
        for (int round = 1; round <= MAX_UNDERWRITING_ROUNDS; round++) {
            currentStep = "UNDERWRITING_ROUND_" + round;
            messaging.requestUnderwriting(application, round);

            // DESIGN IDIOM 1 (decision-as-payload): one signal name for the
            // decision point — the verdict (APPROVED/REJECTED/CONDITIONS)
            // lives in the payload, never in competing signal names.
            var decision = workflow.awaitSignal("underwriting.decision",
                    UnderwritingDecision.class, LoanTimeouts.decisionTimeout());

            if (VERDICT_APPROVED.equals(decision.verdict())) {
                return;
            }
            if (VERDICT_REJECTED.equals(decision.verdict())) {
                throw new LoanRejectedException("Underwriting rejected loan %s in round %d"
                        .formatted(application.applicationId(), round));
            }
            if (!VERDICT_CONDITIONS.equals(decision.verdict())) {
                throw new LoanRejectedException("Unknown underwriting verdict '%s' for loan %s"
                        .formatted(decision.verdict(), application.applicationId()));
            }

            // CONDITIONS on the final round is treated as REJECTED.
            if (round == MAX_UNDERWRITING_ROUNDS) {
                throw new LoanRejectedException(
                        "Underwriting returned CONDITIONS on final round %d for loan %s — treated as REJECTED"
                                .formatted(round, application.applicationId()));
            }

            // Collect one document per condition, then run the next round.
            currentStep = "COLLECTING_CONDITION_DOCUMENTS_ROUND_" + round;
            workflow.collectSignals("document.uploaded", DocumentUploaded.class,
                    decision.conditions().size(), LoanTimeouts.docTimeout());
        }
    }

    // ── Step 7: signature fan-in with dedupe ────────────────────────────

    private void collectSignatures(WorkflowContext workflow, LoanApplication application) {
        Set<String> required = new LinkedHashSet<>(application.borrowerIds());
        var signed = new LinkedHashSet<String>();

        var batch = workflow.collectSignals("package.signed", Signature.class,
                required.size(), LoanTimeouts.signTimeout());
        int received = batch.size();
        for (var signature : batch) {
            if (required.contains(signature.signerId())) {
                signed.add(signature.signerId());
            }
        }

        // Dedupe by signerId: duplicate/extra signatures just consume more
        // signals, bounded at MAX_SIGNATURE_SIGNALS total.
        while (signed.size() < required.size() && received < MAX_SIGNATURE_SIGNALS) {
            var signature = workflow.awaitSignal("package.signed", Signature.class,
                    LoanTimeouts.signTimeout());
            received++;
            if (required.contains(signature.signerId())) {
                signed.add(signature.signerId());
            }
        }

        if (signed.size() < required.size()) {
            throw new LoanRejectedException(
                    "Signature collection consumed %d signals but only %d of %d borrowers signed"
                            .formatted(received, signed.size(), required.size()));
        }
    }

    // ── Withdrawal gates (idiom 2) ──────────────────────────────────────

    /**
     * DESIGN IDIOM 2 (withdrawal gate): a short-timeout
     * {@code awaitSignal("application.withdrawn", ...)} inside a
     * {@link SignalTimeoutException} catch. Timeout = not withdrawn, continue.
     * A pre-arrived withdrawal signal is consumed instantly (no delay).
     */
    private void checkWithdrawalGate(WorkflowContext workflow) {
        try {
            var withdrawal = workflow.awaitSignal("application.withdrawn", Withdrawal.class,
                    LoanTimeouts.gateTimeout());
            throw new LoanWithdrawnException("Application withdrawn: " + withdrawal.reason());
        } catch (SignalTimeoutException notWithdrawn) {
            // No withdrawal within the gate window — proceed.
        }
    }
}
