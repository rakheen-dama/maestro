package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin retry of a workflow that {@code FAILED} on an {@code awaitSignal}
 * timeout, on a real Postgres store (R61; loan E2E scenario 10).
 *
 * <p>The await is the workflow's first step, so its layout is fixed: the
 * {@code TIMER_SCHEDULED} memo at seq 1, the outcome at seq 2, the deadline row
 * {@code await-signal-1}. The failed run's deadline is spent (row
 * {@code FIRED}); before R61 the retried run resumed from that memo and failed
 * again within milliseconds. The retried await must instead arm a fresh full
 * timeout from the retry — a new memo at seq 1 and a new {@code PENDING} row —
 * and a signal delivered before the retry must be consumed at once.
 *
 * <p>No poller runs: the original deadline is fired by bringing its row due and
 * draining due timers on the test thread. The harness is closed after each
 * test.
 */
@Tag("integration")
@DisplayName("Retry after an awaitSignal timeout re-arms a fresh deadline (Postgres)")
class AwaitTimeoutRetryRearmIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofHours(1);
    private static final String TIMER_ID = "await-signal-1";
    private static final String STEP = "$maestro:awaitSignal:" + ApprovalWorkflow.SIGNAL;

    private @Nullable MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    @DisplayName("the retried await parks on retry time + timeout with a fresh PENDING row, then a signal completes it")
    void retryAfterAwaitTimeout_rearmsFreshDeadline() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("r61-rearm");
        var harness = harness();
        var handle = harness.start(workflowId, ApprovalWorkflow.class, null);
        var spentRowId = failOnTimeout(harness, handle.instanceId());

        var retryStart = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, harness.retry(workflowId, ApprovalWorkflow.class));
        // RED before R61: the retried run was FAILED again before it ever parked.
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        var retryEnd = Instant.now();

        var memo = store.getEventBySequence(handle.instanceId(), 1).orElseThrow();
        assertEquals(EventType.TIMER_SCHEDULED, memo.eventType(), "the fresh memo lands in the same slot");
        assertEquals(STEP, memo.stepName());
        var deadline = Instant.parse(memo.payload().get("deadline").stringValue());
        assertFalse(deadline.isBefore(retryStart.plus(TIMEOUT)),
                "the deadline must be armed from the retry, not the original start: " + deadline);
        assertFalse(deadline.isAfter(retryEnd.plus(TIMEOUT)), "deadline " + deadline);
        assertTrue(store.getEventBySequence(handle.instanceId(), 2).isEmpty(),
                "the outcome slot is open while the retried await waits");

        assertEquals(1, countTimerRows(handle.instanceId()), "the spent row is replaced, not joined");
        var row = store.findTimer(handle.instanceId(), TIMER_ID).orElseThrow();
        assertNotEquals(spentRowId, row.id(), "the spent FIRED row must not be reused");
        assertEquals(TimerStatus.PENDING, row.status());
        assertEquals(deadline, row.fireAt());

        harness.deliverSignal(workflowId, ApprovalWorkflow.SIGNAL, "granted");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("granted", handle.result(String.class));
        assertEquals(List.of(EventType.TIMER_SCHEDULED, EventType.SIGNAL_RECEIVED, EventType.WORKFLOW_COMPLETED),
                handle.events().stream().map(WorkflowEvent::eventType).toList());
        assertEquals(TimerStatus.CANCELLED, store.findTimer(handle.instanceId(), TIMER_ID).orElseThrow().status());
    }

    @Test
    @DisplayName("a signal delivered before the retry is consumed at once, with no deadline row armed")
    void retryAfterAwaitTimeout_signalBeforeRetry_consumedAtOnce() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("r61-early");
        var harness = harness();
        var handle = harness.start(workflowId, ApprovalWorkflow.class, null);
        failOnTimeout(harness, handle.instanceId());

        harness.deliverSignal(workflowId, ApprovalWorkflow.SIGNAL, "granted-early");
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, harness.retry(workflowId, ApprovalWorkflow.class));

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("granted-early", handle.result(String.class));
        var atSlot = store.getEventBySequence(handle.instanceId(), 1).orElseThrow();
        assertEquals(EventType.SIGNAL_RECEIVED, atSlot.eventType());
        assertEquals(STEP, atSlot.stepName());
        assertEquals(0, countTimerRows(handle.instanceId()), "no deadline is armed for a signal already there");
        assertTrue(store.getUnconsumedSignals(workflowId, ApprovalWorkflow.SIGNAL).isEmpty());
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private MaestroEngineHarness harness() {
        var built = MaestroEngineHarness.builder(store, objectMapper).build();
        this.harness = built;
        built.registerWorkflow(ApprovalWorkflow.class);
        return built;
    }

    /**
     * Lets the await park, then fires its deadline as the poller would and
     * waits for the uncaught timeout to fail the workflow.
     *
     * @return the id of the (now spent) deadline row
     */
    private UUID failOnTimeout(MaestroEngineHarness current, UUID instanceId) throws SQLException {
        await().atMost(BOUND).pollInterval(Duration.ofMillis(20)).until(() ->
                store.findTimer(instanceId, TIMER_ID).isPresent()
                        && store.getEventBySequence(instanceId, 1).isPresent());
        var row = store.findTimer(instanceId, TIMER_ID).orElseThrow();
        makeDue(row.id());
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> current.fireDueTimers(10) == 1);
        await().atMost(BOUND).pollInterval(Duration.ofMillis(20)).until(() ->
                store.getEventBySequence(instanceId, 3)
                        .map(e -> e.eventType() == EventType.WORKFLOW_FAILED).orElse(false));

        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType(),
                "precondition: the timeout is memoized at seq 2");
        assertEquals(TimerStatus.FIRED, store.findTimer(instanceId, TIMER_ID).orElseThrow().status(),
                "precondition: the failed run's deadline row is spent");
        return row.id();
    }

    /** Counts every timer row of an instance, whatever its status. */
    private int countTimerRows(UUID instanceId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT count(*) FROM maestro_workflow_timer WHERE workflow_instance_id = ?")) {
            ps.setObject(1, instanceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Brings a timer row's {@code fire_at} forward to the database's {@code now()}. */
    private void makeDue(UUID timerDbId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_timer SET fire_at = now() WHERE id = ?")) {
            ps.setObject(1, timerDbId);
            assertEquals(1, ps.executeUpdate(), "the deadline row must exist to be made due");
        }
    }

    // ── Fixture ────────────────────────────────────────────────────────

    /** One uncaught one-hour await as the first step: FAILED on timeout, the payload otherwise. */
    @DurableWorkflow(name = "R61ApprovalWorkflow")
    public static class ApprovalWorkflow {

        /** Signal name this workflow awaits. */
        static final String SIGNAL = "approval";

        /**
         * @param input unused
         * @return the approval payload
         */
        @WorkflowMethod
        public String run(@Nullable String input) {
            return WorkflowContext.current().awaitSignal(SIGNAL, String.class, TIMEOUT);
        }
    }
}
