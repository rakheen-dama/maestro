package io.b2mash.maestro.samples.loan.underwriting.workflow;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.loan.underwriting.activity.AssessmentActivities;
import io.b2mash.maestro.samples.loan.underwriting.activity.DecisionMessagingActivities;
import io.b2mash.maestro.samples.loan.underwriting.activity.impl.RuleBasedAssessmentActivities;
import io.b2mash.maestro.samples.loan.underwriting.domain.Decision;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingRequest;
import io.b2mash.maestro.samples.loan.underwriting.domain.Verdict;
import io.b2mash.maestro.samples.loan.underwriting.queue.PendingReviewRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, in-memory tests for {@link UnderwritingWorkflow} using
 * {@link TestWorkflowEnvironment}. Uses the real rule-based assessment
 * implementation and an in-memory fake for the Kafka publisher.
 */
class UnderwritingWorkflowTest {

    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(5);

    private TestWorkflowEnvironment env;
    private PendingReviewRegistry registry;
    private RecordingDecisionMessaging published;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        registry = new PendingReviewRegistry();
        published = new RecordingDecisionMessaging();
        env.registerActivities(AssessmentActivities.class, new RuleBasedAssessmentActivities(registry));
        env.registerActivities(DecisionMessagingActivities.class, published);
        // Generous defaults; timeout-path tests install sub-second values.
        UnderwritingWorkflow.configureTimeouts(Duration.ofMinutes(10), Duration.ofMinutes(10));
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
        UnderwritingWorkflow.resetTimeouts();
    }

    // ── Automatic path ───────────────────────────────────────────────────

    @Test
    void autoApprovesWhenDtiLowAndAllVerificationsApproved() throws TimeoutException {
        // DTI = 250000 / 100000 = 2.5 < 3, verifications approved
        var handle = start("loan-a1", 1, 250_000, 100_000, true);

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);

        assertEquals(Verdict.APPROVED, result.verdict());
        assertEquals("loan-a1", result.loanId());
        assertEquals(1, result.round());
        assertEquals(1, published.decisions.size());
        assertEquals(Verdict.APPROVED, published.decisions.getFirst().verdict());
    }

    @Test
    void autoRejectsWhenDtiTooHigh() throws TimeoutException {
        // DTI = 700000 / 100000 = 7 > 6
        var handle = start("loan-r1", 1, 700_000, 100_000, true);

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);

        assertEquals(Verdict.REJECTED, result.verdict());
        assertEquals(1, published.decisions.size());
        assertEquals(Verdict.REJECTED, published.decisions.getFirst().verdict());
    }

    // ── Human path ───────────────────────────────────────────────────────

    @Test
    void humanUnderwriterApproves() throws TimeoutException {
        // DTI = 400000 / 100000 = 4 → human queue
        var handle = start("loan-h1", 1, 400_000, 100_000, true);
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL);

        handle.signal("underwriter.decision", new Decision(Verdict.APPROVED, List.of()));

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);
        assertEquals(Verdict.APPROVED, result.verdict());
        assertEquals(1, published.decisions.size());
        assertEquals(Verdict.APPROVED, published.decisions.getFirst().verdict());
    }

    @Test
    void humanConditionsVerdictCarriesConditionsAndRound() throws TimeoutException {
        var conditions = List.of("proof of bonus income", "recent bank statements");
        // Round 2 — the published decision must echo it
        var handle = start("loan-h2", 2, 400_000, 100_000, true);
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL);

        handle.signal("underwriter.decision", new Decision(Verdict.CONDITIONS, conditions));

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);
        assertEquals(Verdict.CONDITIONS, result.verdict());

        assertEquals(1, published.decisions.size());
        var publishedDecision = published.decisions.getFirst();
        assertEquals("loan-h2", publishedDecision.loanId());
        assertEquals(2, publishedDecision.round());
        assertEquals(Verdict.CONDITIONS, publishedDecision.verdict());
        assertEquals(conditions, publishedDecision.conditions());
    }

    // ── Escalation path ──────────────────────────────────────────────────

    @Test
    void escalatesOnUnderwriterTimeoutThenAcceptsSeniorDecision() throws TimeoutException {
        UnderwritingWorkflow.configureTimeouts(Duration.ofMillis(200), Duration.ofSeconds(5));
        var handle = start("loan-e1", 1, 400_000, 100_000, true);

        // The escalate activity marks the registry before the senior await —
        // once visible, the underwriter desk has definitively timed out.
        waitUntil(() -> registry.isEscalated("loan-e1", 1), Duration.ofSeconds(3));
        assertTrue(registry.isEscalated("loan-e1", 1), "review should be escalated");

        handle.signal("senior.decision", new Decision(Verdict.APPROVED, List.of()));

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);
        assertEquals(Verdict.APPROVED, result.verdict());
        assertEquals(1, published.decisions.size());
    }

    @Test
    void rejectsWithNoDecisionWhenBothDesksTimeOut() throws TimeoutException {
        UnderwritingWorkflow.configureTimeouts(Duration.ofMillis(200), Duration.ofMillis(200));
        var handle = start("loan-e2", 1, 400_000, 100_000, true);

        var result = handle.getResult(UnderwritingDecision.class, RESULT_TIMEOUT);

        assertEquals(Verdict.REJECTED, result.verdict());
        assertEquals(List.of("no decision"), result.conditions());
        assertTrue(registry.isEscalated("loan-e2", 1), "review should have been escalated first");
        assertEquals(1, published.decisions.size());
        assertEquals(List.of("no decision"), published.decisions.getFirst().conditions());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TestWorkflowHandle start(String loanId, int round, long amount, long income,
                                     boolean verificationsApproved) {
        var workflowId = "underwriting-%s-round%d".formatted(loanId, round);
        var request = new UnderwritingRequest(loanId, round, amount, income, verificationsApproved);
        return env.startWorkflow(workflowId, UnderwritingWorkflow.class, request);
    }

    private static void waitForStatus(TestWorkflowHandle handle, WorkflowStatus expected) {
        waitUntil(() -> handle.getStatus() == expected, Duration.ofSeconds(3));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
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
        // Callers assert the post-condition; the condition may have been
        // transiently true and moved on (e.g. status transitions).
    }

    /** In-memory fake for the Kafka decision publisher. */
    private static final class RecordingDecisionMessaging implements DecisionMessagingActivities {
        private final List<UnderwritingDecision> decisions = new CopyOnWriteArrayList<>();

        @Override
        public void publishDecision(UnderwritingDecision decision) {
            decisions.add(decision);
        }
    }
}
