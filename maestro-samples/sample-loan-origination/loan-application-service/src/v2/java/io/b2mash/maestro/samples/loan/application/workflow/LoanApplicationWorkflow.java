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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * <b>v2 of the loan origination orchestrator.</b> Identical to the v1 class of
 * the same name in {@code src/main/java} except that steps 3 and 4 —
 * verification fan-in and document collection — run as two concurrent
 * {@code workflow.parallel()} branches instead of one after the other, behind a
 * {@code workflow.version()} gate. A borrower can upload the closing documents
 * while the three third-party verifications are still outstanding.
 *
 * <h2>Why this class shares v1's fully-qualified name</h2>
 * <p>A versioned redeploy is the same workflow type running different code, not
 * a second type. {@code loan-application-v2.jar} packages this class in front of
 * main's copy (see this module's {@code build.gradle.kts}); everything else in
 * the service is shared.
 *
 * <h2>Why not one branch per verification type</h2>
 * <p>The obvious shape — three branches, one per verification, each
 * {@code awaitSignal("verification.result", …)} — <b>cannot work on this
 * engine</b>, and fails in two different ways depending on timing:
 * <ul>
 *   <li><b>Both branches parked:</b> {@code ParkingLot.register} keys on
 *       {@code workflowId + ":signal:" + signalName} and throws
 *       {@code IllegalStateException("Parking key … already occupied")} on the
 *       second park, because one key holds one waiter.</li>
 *   <li><b>Signal already pending:</b> every branch takes
 *       {@code getUnconsumedSignals(…).getFirst()} — the <em>same row</em> —
 *       and {@code SignalManager.consumeSignal} deliberately proceeds when its
 *       CAS loses, so all three branches return the same verification result.</li>
 * </ul>
 * Both are pinned by {@code LoanApplicationWorkflowV2Test}: the first by
 * {@code concurrentBranchesOnOneSignalNameAreUnsupported}, the second by
 * {@code concurrentBranchesOnOneSignalNameConsumeTheSameSignalRow}, which
 * proxies the store to hold both branches at their pending-signal read until
 * each has the row — left to chance the two modes are a race, and the same
 * fixture can land on either. Concurrent branches
 * must therefore await <b>different signal names</b> — which is exactly what
 * {@code verification.result} ∥ {@code document.uploaded} does, and what the
 * engine's own {@code SignalManager} Javadoc anticipates ("parallel branches of
 * one workflow may await different signals concurrently").
 *
 * @see LoanApplicationWorkflowV2Test
 */
@DurableWorkflow(name = "loan-application", taskQueue = "loan-application")
public class LoanApplicationWorkflow {

    /** Change id for the parallel-verification gate. Never rename — it is durable. */
    static final String PARALLEL_VERIFICATION = "parallel-verification";
    /** Highest version this build carries: 2 = parallel verification fan-out. */
    static final int PARALLEL_VERIFICATION_VERSION = 2;

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

        // THE VERSION GATE — resolved before the first step, deliberately.
        //
        // version() peeks the NEXT sequence slot: if what it finds there is not
        // this change's marker, the history predates the change and it resolves
        // to DEFAULT_VERSION *without consuming the slot*, leaving the old event
        // log unshifted. Resolving at sequence 1 therefore pins EVERY loan that
        // has already recorded its application — i.e. every loan in flight when
        // this jar is deployed — to the v1 path. Resolving it later (say just
        // before step 3) would only pin loans that had already consumed a
        // verification result; one parked at the very first await would find an
        // empty slot, record version 2, and switch paths mid-flight.
        //
        // minSupported is DEFAULT_VERSION (-1), NOT 1. A history that predates
        // the change resolves to DEFAULT_VERSION, and DEFAULT_VERSION is -1 —
        // so `version(PARALLEL_VERIFICATION, 1, 2)` would put every in-flight
        // loan below the floor and throw UnsupportedWorkflowVersionException,
        // failing it and running its saga compensations. That inverts the whole
        // point of the gate. Proven, before this line was written, in
        // demo/.evidence/task-3-red-2-brief-literal-bounds-fail-inflight.log.
        // Raise the floor to 2 and delete awaitAllVerifications() only once no
        // pre-change loan can still be running.
        int v = workflow.version(PARALLEL_VERIFICATION, WorkflowContext.DEFAULT_VERSION,
                PARALLEL_VERIFICATION_VERSION);

        // Step 1: validate + persist demo state
        currentStep = "RECORDING_APPLICATION";
        loan.recordApplication(application);

        // Step 2: one verification request per type → loans.verification.requests
        currentStep = "REQUESTING_VERIFICATIONS";
        messaging.requestVerifications(application);

        // Steps 3 and 4 — THE VERSIONED CHANGE.
        if (v < PARALLEL_VERIFICATION_VERSION) {
            // v1 behaviour, unchanged: verification fan-in, then documents.
            currentStep = "COLLECTING_VERIFICATIONS";
            awaitAllVerifications();
            currentStep = "COLLECTING_DOCUMENTS";
            collectRequiredDocuments(application);
        } else {
            currentStep = "COLLECTING_VERIFICATIONS_AND_DOCUMENTS";
            // Branches return a label, not null: parallel() collects results
            // with List.copyOf, which rejects nulls — a Callable<Void> branch
            // fails the workflow with a message-less NullPointerException.
            workflow.parallel(List.<Callable<String>>of(
                    () -> {
                        awaitAllVerifications();
                        return "verifications";
                    },
                    () -> {
                        collectRequiredDocuments(application);
                        return "documents";
                    }));
        }

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

    // ── Steps 3 and 4 ───────────────────────────────────────────────────
    //
    // Byte-for-byte the v1 class's collectVerificationResults / step-4
    // collectSignals. Any change here changes the event sequence of loans that
    // were in flight across the deploy, which is the exact thing the version
    // gate exists to prevent.
    //
    // Both read WorkflowContext.current() rather than closing over the parent
    // context: inside parallel() the current context is the BRANCH context,
    // with its own partitioned sequence space. Closing over the parent's would
    // make both branches allocate from one counter and collide on
    // (workflow_instance_id, sequence_number).

    private void awaitAllVerifications() {
        var workflow = WorkflowContext.current();
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
            if (!expectedTypes.contains(result.type())) {
                // Only the requested verification types may satisfy the fan-in —
                // arbitrary approved types must not stand in for them.
                throw new LoanDeclinedException("Unexpected verification type: " + result.type());
            }
            verifiedTypes.add(result.type());
        }

        if (verifiedTypes.size() < expectedTypes.size()) {
            throw new LoanDeclinedException(
                    "Verification fan-in consumed %d signals without seeing all of %s (got %s)"
                            .formatted(MAX_VERIFICATION_SIGNALS, expectedTypes, verifiedTypes));
        }
    }

    /** Step 4, byte-for-byte from v1. */
    private void collectRequiredDocuments(LoanApplication application) {
        WorkflowContext.current().collectSignals("document.uploaded", DocumentUploaded.class,
                application.requiredDocs().size(), LoanTimeouts.docTimeout());
    }

    // ── Step 6: underwriting rounds ─────────────────────────────────────

    private void runUnderwritingRounds(WorkflowContext workflow, LoanApplication application) {
        for (int round = 1; round <= MAX_UNDERWRITING_ROUNDS; round++) {
            currentStep = "UNDERWRITING_ROUND_" + round;
            messaging.requestUnderwriting(application, round);

            // DESIGN IDIOM 1 (decision-as-payload): one signal name for the
            // decision point — the verdict (APPROVED/REJECTED/CONDITIONS)
            // lives in the payload, never in competing signal names.
            var decision = awaitDecisionForRound(workflow, round);

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

    /**
     * Awaits the underwriting decision for the given round, skipping delayed
     * or duplicated decisions from earlier rounds so they cannot be
     * mis-attributed to the active round. Bounded by the decision timeout on
     * each await.
     */
    private UnderwritingDecision awaitDecisionForRound(WorkflowContext workflow, int round) {
        while (true) {
            var decision = workflow.awaitSignal("underwriting.decision",
                    UnderwritingDecision.class, LoanTimeouts.decisionTimeout());
            if (decision.round() == round) {
                return decision;
            }
            // Stale decision from a previous round (delayed or redelivered) —
            // consume it and keep waiting for the active round's decision.
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
