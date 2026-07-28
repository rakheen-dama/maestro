package io.b2mash.maestro.samples.loan.verification.workflow;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.loan.verification.activity.VerificationMessagingActivities;
import io.b2mash.maestro.samples.loan.verification.activity.VerificationProviderActivities;
import io.b2mash.maestro.samples.loan.verification.activity.impl.SimulatedVerificationProviderActivities;
import io.b2mash.maestro.samples.loan.verification.domain.ProviderOutcome;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationTask;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, in-memory tests for {@link VerificationWorkflow} using
 * {@link TestWorkflowEnvironment} — no Postgres, Kafka or Valkey.
 */
class VerificationWorkflowTest {

    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;
    private RecordingMessaging messaging;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        messaging = new RecordingMessaging();
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    // ── result published after sleep (controllable clock) ───────────────

    @Test
    void resultIsPublishedOnlyAfterSimulatedLatencyElapses() throws TimeoutException {
        env.registerActivities(VerificationProviderActivities.class, new AlwaysApprovingProvider());
        env.registerActivities(VerificationMessagingActivities.class, messaging);

        var handle = env.startWorkflow(
                "verification-loan-1-credit",
                VerificationWorkflow.class,
                new VerificationTask("loan-1", "credit", 250_000, 2_000));

        // The workflow must park on the durable latency timer, with nothing
        // published yet.
        awaitStatus(handle, WorkflowStatus.WAITING_TIMER);
        assertTrue(messaging.published.isEmpty(),
                "no result may be published before the simulated latency elapses");

        // Advance the controllable clock past the 2s latency — fires the timer.
        env.advanceTime(Duration.ofMinutes(1));

        var result = handle.getResult(VerificationResult.class, RESULT_TIMEOUT);

        assertEquals(new VerificationResult("loan-1", "credit", true, "approved by fake provider"),
                result);
        assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
        assertEquals(List.of(result), messaging.published);
    }

    // ── flaky provider recovered by retry policy ─────────────────────────

    @Test
    void flakyProviderFailsTwiceAndSucceedsOnThirdAttempt() throws TimeoutException {
        var provider = new SimulatedVerificationProviderActivities();
        env.registerActivities(VerificationProviderActivities.class, provider);
        env.registerActivities(VerificationMessagingActivities.class, messaging);

        // Amount ends in 7 → simulated provider throws on attempts 1 and 2.
        var handle = env.startWorkflow(
                "verification-loan-7-employment",
                VerificationWorkflow.class,
                new VerificationTask("loan-7", "employment", 300_007, 10));

        awaitStatus(handle, WorkflowStatus.WAITING_TIMER);
        env.advanceTime(Duration.ofMinutes(1));

        var result = handle.getResult(VerificationResult.class, RESULT_TIMEOUT);

        assertEquals(3, provider.attempts("loan-7", "employment"),
                "provider must be called exactly 3 times (2 failures + 1 success)");
        assertTrue(result.approved(), "retry policy must recover the transient failures");
        assertEquals("loan-7", result.loanId());
        assertEquals("employment", result.type());
        assertEquals(List.of(result), messaging.published);
    }

    // ── deterministic decline for amounts ending in 13 ───────────────────

    @Test
    void amountEndingIn13IsDeterministicallyDeclined() throws TimeoutException {
        var provider = new SimulatedVerificationProviderActivities();
        env.registerActivities(VerificationProviderActivities.class, provider);
        env.registerActivities(VerificationMessagingActivities.class, messaging);

        var handle = env.startWorkflow(
                "verification-loan-13-appraisal",
                VerificationWorkflow.class,
                new VerificationTask("loan-13", "appraisal", 500_013, 10));

        awaitStatus(handle, WorkflowStatus.WAITING_TIMER);
        env.advanceTime(Duration.ofMinutes(1));

        var result = handle.getResult(VerificationResult.class, RESULT_TIMEOUT);

        // A decline is a business outcome: the workflow COMPLETES with
        // approved=false and still publishes the result.
        assertFalse(result.approved());
        assertTrue(result.details().contains("declined"),
                "details should explain the decline, got: " + result.details());
        assertEquals(1, provider.attempts("loan-13", "appraisal"),
                "a decline must not be retried");
        assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
        assertEquals(List.of(result), messaging.published);
    }

    // ── idempotent duplicate request handling ────────────────────────────

    @Test
    void duplicateStartForSameWorkflowIdIsRejectedAndOriginalUnaffected() throws TimeoutException {
        env.registerActivities(VerificationProviderActivities.class, new AlwaysApprovingProvider());
        env.registerActivities(VerificationMessagingActivities.class, messaging);

        var workflowId = "verification-loan-9-credit";
        var task = new VerificationTask("loan-9", "credit", 100_000, 10);

        var handle = env.startWorkflow(workflowId, VerificationWorkflow.class, task);

        // A duplicate request maps to the same workflow id and is rejected —
        // the Kafka listener catches this exception and skips (idempotent).
        assertThrows(WorkflowAlreadyExistsException.class,
                () -> env.startWorkflow(workflowId, VerificationWorkflow.class, task));

        awaitStatus(handle, WorkflowStatus.WAITING_TIMER);
        env.advanceTime(Duration.ofMinutes(1));

        var result = handle.getResult(VerificationResult.class, RESULT_TIMEOUT);
        assertTrue(result.approved());
        assertEquals(1, messaging.published.size(),
                "exactly one result may be published despite the duplicate request");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void awaitStatus(TestWorkflowHandle handle, WorkflowStatus expected) {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (handle.getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        assertEquals(expected, handle.getStatus(),
                "workflow did not reach status " + expected + " in time");
    }

    /** Always-approving provider fake. */
    private static final class AlwaysApprovingProvider implements VerificationProviderActivities {
        @Override
        public ProviderOutcome callProvider(String loanId, String type, long amount) {
            return new ProviderOutcome(true, "approved by fake provider");
        }
    }

    /** In-memory recording fake for the Kafka publisher activity. */
    private static final class RecordingMessaging implements VerificationMessagingActivities {
        private final List<VerificationResult> published = new CopyOnWriteArrayList<>();

        @Override
        public void publishResult(VerificationResult result) {
            published.add(result);
        }
    }
}
