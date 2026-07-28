package io.b2mash.maestro.samples.loan.application.workflow;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.loan.application.activity.FundingActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanMessagingActivities;
import io.b2mash.maestro.samples.loan.application.activity.impl.InMemoryLoanActivities;
import io.b2mash.maestro.samples.loan.application.activity.impl.SimulatedFundingActivities;
import io.b2mash.maestro.samples.loan.application.domain.DocumentUploaded;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.LoanResult;
import io.b2mash.maestro.samples.loan.application.domain.Signature;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationResult;
import io.b2mash.maestro.samples.loan.application.domain.Withdrawal;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import io.b2mash.maestro.test.TestWorkflowHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC test matrix for the loan-application service, run against the
 * in-memory {@link TestWorkflowEnvironment} with fast (≤1s) timeouts and an
 * in-memory fake for the Kafka-publishing activities.
 */
class LoanApplicationWorkflowTest {

    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String APP_ID = "app-1";
    private static final String WORKFLOW_ID = "loan-" + APP_ID;
    private static final String BORROWER_1 = "borrower-1";
    private static final String BORROWER_2 = "borrower-2";

    private TestWorkflowEnvironment env;
    private RecordingMessagingActivities messaging;

    @BeforeEach
    void setUp() {
        // Fast test timeouts (≤1s); gate timeout kept short because each gate
        // waits it out in real time on the not-withdrawn path.
        LoanTimeouts.configure(
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMillis(200));

        env = TestWorkflowEnvironment.create();
        messaging = new RecordingMessagingActivities();
        env.registerActivities(LoanActivities.class, new InMemoryLoanActivities());
        env.registerActivities(LoanMessagingActivities.class, messaging);
        env.registerActivities(FundingActivities.class, new SimulatedFundingActivities());
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
        LoanTimeouts.reset();
    }

    // ── Happy path ───────────────────────────────────────────────────────

    @Test
    void happyPathFundsLoan() throws TimeoutException {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision", approved(1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var result = handle.getResult(LoanResult.class, RESULT_TIMEOUT);

        assertEquals(APP_ID, result.applicationId());
        assertEquals("FUNDED", result.status());
        assertNotNull(result.rateLockId());
        assertNotNull(result.disbursementId());
        assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());

        assertEquals(List.of("credit", "employment", "appraisal"),
                messaging.verificationRequests.stream().map(VerificationRequested::type).toList());
        assertEquals(List.of(1),
                messaging.underwritingRequests.stream().map(UnderwritingRequested::round).toList());

        assertActivityCompleted(handle, "FundingActivities.disburse");
        assertNoCompensation(handle);
    }

    // ── Verification results: reverse order + duplicate ──────────────────

    @Test
    void verificationResultsInReverseOrderWithDuplicateAreTolerated() throws TimeoutException {
        var handle = start(application());

        // Reverse of request order, with a duplicate employment result.
        handle.signal("verification.result", approvedVerification("appraisal"));
        handle.signal("verification.result", approvedVerification("employment"));
        handle.signal("verification.result", approvedVerification("employment"));
        handle.signal("verification.result", approvedVerification("credit"));

        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision", approved(1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var result = handle.getResult(LoanResult.class, RESULT_TIMEOUT);
        assertEquals("FUNDED", result.status());
    }

    // ── Documents pre-delivered before the workflow exists ────────────────

    @Test
    void documentsPreDeliveredBeforeStartAreAdopted() throws TimeoutException {
        // Orphan pre-delivery: signals persisted with a null instance id,
        // adopted when the workflow starts.
        env.preDeliverSignal(WORKFLOW_ID, "document.uploaded",
                new DocumentUploaded(APP_ID, "payslip", BORROWER_1));
        env.preDeliverSignal(WORKFLOW_ID, "document.uploaded",
                new DocumentUploaded(APP_ID, "bank-statement", BORROWER_2));

        var handle = start(application());

        deliverApprovedVerifications(handle);
        handle.signal("underwriting.decision", approved(1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var result = handle.getResult(LoanResult.class, RESULT_TIMEOUT);
        assertEquals("FUNDED", result.status());
    }

    // ── Conditions loop: two underwriting rounds ─────────────────────────

    @Test
    void conditionsLoopRunsSecondRoundAfterExtraDocument() throws TimeoutException {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision",
                new UnderwritingDecision(APP_ID, 1, "CONDITIONS", List.of("proof-of-funds")));
        handle.signal("document.uploaded", new DocumentUploaded(APP_ID, "proof-of-funds", BORROWER_1));
        handle.signal("underwriting.decision", approved(2));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var result = handle.getResult(LoanResult.class, RESULT_TIMEOUT);

        assertEquals("FUNDED", result.status());
        assertEquals(List.of(1, 2),
                messaging.underwritingRequests.stream().map(UnderwritingRequested::round).toList());
    }

    // ── Withdrawal at gate #1: fail without compensation ─────────────────

    @Test
    void withdrawalAtGateOneFailsWithoutCompensation() {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        // Withdrawal arrives before gate #1 — consumed instantly at the gate.
        handle.signal("application.withdrawn", new Withdrawal(APP_ID, "changed my mind"));

        var failure = assertThrows(RuntimeException.class,
                () -> handle.getResult(LoanResult.class, RESULT_TIMEOUT));

        assertTrue(failure.getMessage().contains("LoanWithdrawnException"),
                "expected LoanWithdrawnException in failure detail: " + failure.getMessage());
        assertEquals(WorkflowStatus.FAILED, handle.getStatus());
        // Nothing was reserved — no compensation, and underwriting never requested.
        assertNoCompensation(handle);
        assertTrue(messaging.underwritingRequests.isEmpty());
    }

    // ── Withdrawal at gate #2: rate-lock compensation runs ────────────────

    @Test
    void withdrawalAtGateTwoReleasesRateLockViaCompensation() {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision", approved(1));

        // Wait until gate #1 has passed and the rate lock is reserved, so the
        // withdrawal cannot be consumed by gate #1.
        waitUntil(() -> "COLLECTING_SIGNATURES".equals(
                handle.query("getStatus", null, String.class)), Duration.ofSeconds(5));

        // Deliver the withdrawal BEFORE the signatures: it is persisted and
        // then consumed instantly at gate #2, after signature collection.
        handle.signal("application.withdrawn", new Withdrawal(APP_ID, "found a better rate"));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var failure = assertThrows(RuntimeException.class,
                () -> handle.getResult(LoanResult.class, RESULT_TIMEOUT));

        assertTrue(failure.getMessage().contains("LoanWithdrawnException"),
                "expected LoanWithdrawnException in failure detail: " + failure.getMessage());
        assertEquals(WorkflowStatus.FAILED, handle.getStatus());

        // Saga compensation released the rate lock (asserted via events).
        var events = handle.getEvents();
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.COMPENSATION_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.COMPENSATION_COMPLETED));
        assertActivityCompleted(handle, "FundingActivities.releaseRateLock");
        // Disbursement never happened, so it was not (and must not be) reversed.
        assertFalse(events.stream().anyMatch(e ->
                "FundingActivities.disburse".equals(e.stepName())
                        && e.eventType() == EventType.ACTIVITY_COMPLETED));
    }

    // ── Signature dedupe ─────────────────────────────────────────────────

    @Test
    void duplicateSignatureFromSameSignerIsDeduped() throws TimeoutException {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision", approved(1));
        // Same signer twice, then the second signer.
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_1));
        handle.signal("package.signed", new Signature(APP_ID, BORROWER_2));

        var result = handle.getResult(LoanResult.class, RESULT_TIMEOUT);
        assertEquals("FUNDED", result.status());
    }

    // ── Underwriting REJECTED ────────────────────────────────────────────

    @Test
    void underwritingRejectionFailsWorkflow() {
        var handle = start(application());

        deliverApprovedVerifications(handle);
        deliverRequiredDocuments(handle);
        handle.signal("underwriting.decision",
                new UnderwritingDecision(APP_ID, 1, "REJECTED", List.of()));

        var failure = assertThrows(RuntimeException.class,
                () -> handle.getResult(LoanResult.class, RESULT_TIMEOUT));

        assertTrue(failure.getMessage().contains("LoanRejectedException"),
                "expected LoanRejectedException in failure detail: " + failure.getMessage());
        assertEquals(WorkflowStatus.FAILED, handle.getStatus());
        // Rejection happens before funding — no compensation to run.
        assertNoCompensation(handle);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TestWorkflowHandle start(LoanApplication application) {
        return env.startWorkflow(WORKFLOW_ID, LoanApplicationWorkflow.class, application);
    }

    private static LoanApplication application() {
        return new LoanApplication(
                APP_ID,
                List.of(BORROWER_1, BORROWER_2),
                400_000,
                100_000,
                500_000,
                List.of("payslip", "bank-statement"));
    }

    private static VerificationResult approvedVerification(String type) {
        return new VerificationResult(APP_ID, type, true, "ok");
    }

    private static UnderwritingDecision approved(int round) {
        return new UnderwritingDecision(APP_ID, round, "APPROVED", List.of());
    }

    private static void deliverApprovedVerifications(TestWorkflowHandle handle) {
        handle.signal("verification.result", approvedVerification("credit"));
        handle.signal("verification.result", approvedVerification("employment"));
        handle.signal("verification.result", approvedVerification("appraisal"));
    }

    private static void deliverRequiredDocuments(TestWorkflowHandle handle) {
        handle.signal("document.uploaded", new DocumentUploaded(APP_ID, "payslip", BORROWER_1));
        handle.signal("document.uploaded", new DocumentUploaded(APP_ID, "bank-statement", BORROWER_2));
    }

    private static void assertActivityCompleted(TestWorkflowHandle handle, String stepName) {
        assertTrue(handle.getEvents().stream().anyMatch(e ->
                        stepName.equals(e.stepName()) && e.eventType() == EventType.ACTIVITY_COMPLETED),
                "expected ACTIVITY_COMPLETED event for " + stepName);
    }

    private static void assertNoCompensation(TestWorkflowHandle handle) {
        assertTrue(handle.getEvents().stream().noneMatch(e ->
                        e.eventType() == EventType.COMPENSATION_STARTED
                                || e.eventType() == EventType.COMPENSATION_STEP_COMPLETED
                                || e.eventType() == EventType.COMPENSATION_COMPLETED),
                "expected no compensation events");
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    /** In-memory fake for the Kafka-publishing activities. */
    static final class RecordingMessagingActivities implements LoanMessagingActivities {

        final List<VerificationRequested> verificationRequests = new CopyOnWriteArrayList<>();
        final List<UnderwritingRequested> underwritingRequests = new CopyOnWriteArrayList<>();

        @Override
        public void requestVerifications(LoanApplication application) {
            for (var type : VERIFICATION_TYPES) {
                verificationRequests.add(new VerificationRequested(
                        application.applicationId(), type, application.amount()));
            }
        }

        @Override
        public void requestUnderwriting(LoanApplication application, int round) {
            underwritingRequests.add(new UnderwritingRequested(
                    application.applicationId(), round, application.amount(),
                    application.income(), application.propertyValue(), true));
        }
    }
}
