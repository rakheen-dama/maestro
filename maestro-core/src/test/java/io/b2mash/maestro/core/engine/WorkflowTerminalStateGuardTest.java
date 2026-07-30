package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the rule that <b>nothing resurrects a terminal workflow.</b>
 *
 * <h2>Why this matters</h2>
 * <p>{@code terminateWorkflow} writes {@code TERMINATED} from whichever node
 * received the command, which is usually <em>not</em> the node running the
 * workflow. The owner's virtual thread is still parked on a signal or a timer,
 * and every one of its wake paths used to write its next status against a
 * fresh read with no regard for what it read: a delivered signal or a fired
 * timer flipped the row straight back to {@code RUNNING}, and the run then
 * finalised itself as {@code COMPLETED} — silently undoing the operator's
 * terminate.
 *
 * <p>The guard lives in the two {@code updateInstanceStatus} helpers
 * ({@link SignalManager}, {@link DefaultWorkflowOperations}) plus a status
 * re-read in {@code awaitSignal}'s chunked wake loop, which is what bounds
 * cross-node convergence for a workflow parked on a signal that never arrives.
 *
 * <p>Terminate is modelled here as an out-of-band row write
 * ({@link VersionedInMemoryStore#forceStatus}) rather than via
 * {@code terminateWorkflow}: the hazard is about the <em>owner</em> reacting to
 * a terminal row it did not write, so the test must not rely on the local
 * eviction that a same-node terminate performs.
 */
@DisplayName("A terminal workflow is never resurrected by a late signal, timer or wake")
class WorkflowTerminalStateGuardTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = newExecutor(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private WorkflowExecutor newExecutor(Duration wakeRecheckInterval) {
        return new WorkflowExecutor(
                store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30),
                false, Duration.ofSeconds(5), wakeRecheckInterval);
    }

    // ── Signal delivery ────────────────────────────────────────────────

    @Test
    @DisplayName("a signal delivered after terminate does not flip the instance back to RUNNING")
    void deliveredSignal_doesNotResurrectTerminatedInstance() throws Exception {
        var workflow = new AwaitingWorkflow();
        executor.startWorkflow("resurrect-signal", "AwaitingWorkflow", "default",
                null, workflow, AwaitingWorkflow.class.getMethod("run"));
        awaitStatus("resurrect-signal", WorkflowStatus.WAITING_SIGNAL);

        // Another node terminated it while this node's thread stayed parked.
        store.forceStatus("resurrect-signal", WorkflowStatus.TERMINATED);

        executor.deliverSignal("resurrect-signal", AwaitingWorkflow.SIGNAL, "granted");

        // The parked thread must stand down, not consume-and-continue.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("resurrect-signal"),
                        "the terminated workflow's thread must unwind"));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("resurrect-signal")
                        .orElseThrow().status(),
                "a delivered signal must never resurrect a TERMINATED workflow");
        assertFalse(workflow.pastAwait.get(),
                "the workflow body must not continue past the await of a terminated run");
    }

    // ── Timer fire ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a timer fired after terminate does not flip the instance back to RUNNING")
    void firedTimer_doesNotResurrectTerminatedInstance() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("resurrect-timer", "SleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("resurrect-timer", WorkflowStatus.WAITING_TIMER);

        store.forceStatus("resurrect-timer", WorkflowStatus.TERMINATED);

        // The timer poller does not know about terminate — it fires what is due.
        var due = store.getDueTimers(Instant.now().plus(Duration.ofMinutes(1)), 10);
        assertEquals(1, due.size(), "the sleeping workflow must have a pending timer");
        executor.fireTimer(due.getFirst().workflowId(), due.getFirst().timerId(), due.getFirst().id());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("resurrect-timer"),
                        "the terminated workflow's thread must unwind"));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("resurrect-timer")
                        .orElseThrow().status(),
                "a fired timer must never resurrect a TERMINATED workflow");
        assertFalse(workflow.pastSleep.get(),
                "the workflow body must not continue past the sleep of a terminated run");
    }

    // ── Cross-node convergence for a parked await ──────────────────────

    @Test
    @DisplayName("a workflow parked on awaitSignal stands down within one wake-recheck interval")
    void parkedAwait_standsDownWithinOneRecheckInterval() throws Exception {
        var workflow = new AwaitingWorkflow();
        executor.startWorkflow("resurrect-parked", "AwaitingWorkflow", "default",
                null, workflow, AwaitingWorkflow.class.getMethod("run"));
        awaitStatus("resurrect-parked", WorkflowStatus.WAITING_SIGNAL);

        // No signal, no timer — only the terminal row. The await's periodic
        // store re-check is the only thing that can notice, which is exactly
        // what bounds cross-node terminate convergence to one interval.
        store.forceStatus("resurrect-parked", WorkflowStatus.TERMINATED);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("resurrect-parked"),
                        "a parked await must re-check the instance status and unwind"));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("resurrect-parked")
                .orElseThrow().status());
    }

    // ── Non-TERMINATED terminal states still converge quietly ──────────

    @Test
    @DisplayName("a COMPLETED row written by another runner is not overwritten either")
    void completedElsewhere_isNotOverwrittenByAWakingRun() throws Exception {
        var workflow = new AwaitingWorkflow();
        executor.startWorkflow("resurrect-completed", "AwaitingWorkflow", "default",
                null, workflow, AwaitingWorkflow.class.getMethod("run"));
        awaitStatus("resurrect-completed", WorkflowStatus.WAITING_SIGNAL);

        store.forceStatus("resurrect-completed", WorkflowStatus.COMPLETED);
        executor.deliverSignal("resurrect-completed", AwaitingWorkflow.SIGNAL, "granted");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("resurrect-completed"),
                        "the run must not keep going against a finalised row"));
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("resurrect-completed")
                        .orElseThrow().status(),
                "another runner's COMPLETED must stand");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(expected, store.getInstance(workflowId).orElseThrow().status()));
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** Parks on a signal that the tests deliberately never deliver in time. */
    @DurableWorkflow(name = "AwaitingWorkflow")
    public static class AwaitingWorkflow {

        static final String SIGNAL = "approval";

        final AtomicBoolean pastAwait = new AtomicBoolean();

        /** @return the signal payload, if the run ever gets that far */
        @WorkflowMethod
        public String run() {
            var decision = WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30));
            pastAwait.set(true);
            return decision;
        }
    }

    /** Parks on a durable timer the tests fire by hand. */
    @DurableWorkflow(name = "SleepingWorkflow")
    public static class SleepingWorkflow {

        final AtomicBoolean pastSleep = new AtomicBoolean();

        /** @return a fixed value, if the run ever gets that far */
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofSeconds(30));
            pastSleep.set(true);
            return "slept";
        }
    }
}
