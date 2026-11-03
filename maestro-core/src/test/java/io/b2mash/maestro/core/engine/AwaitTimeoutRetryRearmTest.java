package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins admin retry of a workflow that {@code FAILED} on an
 * {@code awaitSignal} timeout (R61).
 *
 * <h2>The regression</h2>
 * <p>Since C12 a parked await memoizes its absolute deadline
 * ({@code TIMER_SCHEDULED} at {@code seq}) and persists it as the timer row
 * {@code await-signal-<seq>}; the outcome sits at {@code seq+1}. Retry
 * ({@code deleteFailureEvents}) removed only the failing
 * {@code SIGNAL_TIMEOUT} at {@code seq+1}: the memo with its spent deadline
 * and the {@code FIRED} row survived, so the retried run's replay resumed the
 * await past its deadline and failed again within milliseconds — unless the
 * signal had already arrived. Loan-origination E2E scenario 10 caught it: the
 * operator retries first and sends the decision afterwards.
 *
 * <h2>The contract pinned here</h2>
 * <p>A retry of an await-timeout failure waits the <em>full</em> timeout
 * again, armed from the retry: the failing await's memo and row are deleted
 * with its timeout outcome, so the retried await takes the live path at the
 * same slot — a fresh {@code TIMER_SCHEDULED} memo and a fresh
 * {@code PENDING} row, then its outcome at {@code seq+1}. A signal that
 * arrived before the retry is still consumed first.
 *
 * <p>The executor runs on a controllable {@link InstantSource}, so "the
 * deadline is armed from the retry" is an exact equality, not a tolerance.
 */
@DisplayName("Retry after an awaitSignal timeout re-arms a fresh full deadline (R61)")
class AwaitTimeoutRetryRearmTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final InstantSource clock = now::get;

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;
    private WorkflowRegistration registration;

    @BeforeEach
    void setUp() throws Exception {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service", clock);
        registration = new WorkflowRegistration("ApprovalWorkflow", "default",
                ApprovalWorkflow::new, ApprovalWorkflow.class.getMethod("run"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("the retried await parks on a fresh deadline (retry time + timeout), then a signal completes it")
    void retryAfterAwaitTimeout_rearmsFreshDeadline_thenSignalCompletes() {
        var workflowId = "r61-rearm";
        var instanceId = failOnAwaitTimeout(workflowId);
        var failedLayout = awaitLayout(instanceId);

        // The operator retries an hour after the original deadline passed.
        var retryTime = T0.plus(Duration.ofHours(1));
        now.set(retryTime);
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(workflowId, registration));

        // RED on the old code: the replay resumed from the spent memo and the
        // workflow was FAILED again before this wait could see WAITING_SIGNAL.
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL);
        var memo = scheduledMemo(instanceId);
        var detail = serializer.deserialize(memo.payload(), SignalManager.AwaitSignalDetail.class);
        var expectedDeadline = retryTime.plus(TIMEOUT);
        assertEquals(expectedDeadline, Instant.parse(detail.deadline()),
                "the retried await must arm a FRESH full timeout from the retry time");
        assertEquals(failedLayout.memoSeq(), memo.sequenceNumber(),
                "the fresh memo must land in the SAME slot the failed run's memo used — no replay drift");
        assertEquals(SignalManager.TIMER_ID_PREFIX + failedLayout.memoSeq(), detail.timerId());

        var rows = rowsFor(instanceId, detail.timerId());
        assertEquals(1, rows.size(), "exactly one deadline row under the await's timer id — the spent one is gone");
        assertEquals(TimerStatus.PENDING, rows.getFirst().status());
        assertEquals(expectedDeadline, rows.getFirst().fireAt());
        assertFalse(rows.getFirst().id().equals(failedLayout.rowId()),
                "the spent FIRED row must not be reused");

        executor.deliverSignal(workflowId, "approval", "granted");
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        assertEquals("granted", serializer.deserialize(
                store.getInstance(workflowId).orElseThrow().output(), String.class));

        var outcome = store.getEventBySequence(instanceId, failedLayout.memoSeq() + 1).orElseThrow();
        assertEquals(EventType.SIGNAL_RECEIVED, outcome.eventType(),
                "the outcome lands at seq+1, exactly where the failed run's SIGNAL_TIMEOUT sat");
        assertEquals(SignalManager.STEP_PREFIX + "approval", outcome.stepName());
        assertEquals(TimerStatus.CANCELLED, rowsFor(instanceId, detail.timerId()).getFirst().status(),
                "the consumed signal best-effort cancels the fresh row");
    }

    @Test
    @DisplayName("the re-armed deadline is a full timeout: it does not expire before retry time + timeout")
    void retryAfterAwaitTimeout_rearmedDeadlineGovernsAgain() {
        var workflowId = "r61-rearm-expiry";
        var instanceId = failOnAwaitTimeout(workflowId);

        var retryTime = T0.plus(Duration.ofHours(1));
        now.set(retryTime);
        executor.retryWorkflow(workflowId, registration);
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL);

        // The fresh deadline then times out like any other: fire its row.
        var detail = serializer.deserialize(scheduledMemo(instanceId).payload(),
                SignalManager.AwaitSignalDetail.class);
        var row = rowsFor(instanceId, detail.timerId()).getFirst();
        now.set(retryTime.plus(TIMEOUT));
        executor.fireTimer(workflowId, detail.timerId(), row.id());
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a signal delivered before the retry is consumed at once by the retried await")
    void retryAfterAwaitTimeout_signalArrivedBeforeRetry_consumedImmediately() {
        var workflowId = "r61-early-signal";
        var instanceId = failOnAwaitTimeout(workflowId);
        var failedLayout = awaitLayout(instanceId);

        executor.deliverSignal(workflowId, "approval", "granted-early");
        now.set(T0.plus(Duration.ofHours(1)));
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(workflowId, registration));

        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        assertEquals("granted-early", serializer.deserialize(
                store.getInstance(workflowId).orElseThrow().output(), String.class));
        assertTrue(store.getUnconsumedSignals(workflowId, "approval").isEmpty(),
                "the early signal must be consumed by the retried await");
        var atMemoSlot = store.getEventBySequence(instanceId, failedLayout.memoSeq()).orElseThrow();
        assertEquals(EventType.SIGNAL_RECEIVED, atMemoSlot.eventType(),
                "a signal already waiting at entry is consumed at the await's slot (the live rule)");
        assertTrue(rowsFor(instanceId, SignalManager.TIMER_ID_PREFIX + failedLayout.memoSeq()).isEmpty(),
                "no deadline row is armed when the signal is already there");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Where the failed run's parked await sat: its memo slot and its (spent) row. */
    private record AwaitLayout(int memoSeq, UUID rowId) {
    }

    /**
     * Starts the workflow, lets its await park on {@code T0 + TIMEOUT}, then
     * expires it the way the timer poller would: clock past the deadline, row
     * fired. The workflow ends {@code FAILED} on the uncaught timeout.
     */
    private UUID failOnAwaitTimeout(String workflowId) {
        executor.startWorkflow(workflowId, "ApprovalWorkflow", "default", null,
                registration.workflowFactory(), registration.workflowMethod());
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL);
        var instanceId = store.getInstance(workflowId).orElseThrow().id();
        var memo = scheduledMemo(instanceId);
        var detail = serializer.deserialize(memo.payload(), SignalManager.AwaitSignalDetail.class);
        assertEquals(T0.plus(TIMEOUT), Instant.parse(detail.deadline()));

        var row = rowsFor(instanceId, detail.timerId()).getFirst();
        now.set(T0.plus(TIMEOUT));
        executor.fireTimer(workflowId, detail.timerId(), row.id());
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, Duration.ofSeconds(5));

        assertEquals(EventType.SIGNAL_TIMEOUT,
                store.getEventBySequence(instanceId, memo.sequenceNumber() + 1).orElseThrow().eventType(),
                "precondition: the failed run memoized its timeout at seq+1");
        assertEquals(TimerStatus.FIRED, rowsFor(instanceId, detail.timerId()).getFirst().status(),
                "precondition: the failed run's row is spent");
        return instanceId;
    }

    private AwaitLayout awaitLayout(UUID instanceId) {
        var memo = scheduledMemo(instanceId);
        var row = rowsFor(instanceId, SignalManager.TIMER_ID_PREFIX + memo.sequenceNumber()).getFirst();
        return new AwaitLayout(memo.sequenceNumber(), row.id());
    }

    private WorkflowEvent scheduledMemo(UUID instanceId) {
        var memos = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_SCHEDULED
                        && (SignalManager.STEP_PREFIX + "approval").equals(e.stepName()))
                .toList();
        assertEquals(1, memos.size(), "exactly one await memo for 'approval': " + memos);
        return memos.getFirst();
    }

    private List<WorkflowTimer> rowsFor(UUID instanceId, String timerId) {
        return store.allTimers().stream()
                .filter(t -> t.workflowInstanceId().equals(instanceId) && t.timerId().equals(timerId))
                .toList();
    }

    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() ->
                store.getInstance(workflowId).map(i -> i.status() == expected).orElse(false));
    }

    // ── Fixture ────────────────────────────────────────────────────────

    /** One uncaught timeout-guarded await: FAILED on timeout, the payload otherwise. */
    @DurableWorkflow(name = "ApprovalWorkflow")
    public static class ApprovalWorkflow {

        /** @return the approval payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("approval", String.class, TIMEOUT);
        }
    }
}
