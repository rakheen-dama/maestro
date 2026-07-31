package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins replay determinism of a timed-out {@code awaitSignal} (Issue 19,
 * coordinator Ruling 4).
 *
 * <h2>The defect</h2>
 * <p>A timed-out await consumed a sequence slot but appended <em>no durable
 * record</em> of the timeout — the "designed gap". On recovery replay the await
 * re-executes at that slot; if the awaited signal has <em>arrived late</em> in
 * the meantime, the replay consumes it and takes a different branch than the
 * original execution. Observed end-to-end by the chaos harness under a routine
 * graceful rolling restart: a saga's withdrawal was consumed at gate #1 on
 * replay (the original run had timed out there and consumed it at gate #2), the
 * divergent path's compensation stack was empty, and the reserved rate lock
 * leaked.
 *
 * <p>The contract pinned here (mirroring Issue 13's {@code TIMER_CANCELLED}
 * memoization): the live timeout path appends a {@code SIGNAL_TIMEOUT} event at
 * the await's slot <em>before</em> throwing, and replay re-throws the timeout
 * deterministically from the log alone — no store read, no signal consumption;
 * the late signal stays durably unconsumed (never discarded).
 */
@DisplayName("A timed-out awaitSignal replays deterministically even when the signal arrived late")
class SignalTimeoutReplayDeterminismTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        nodeA = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) {
            nodeA.shutdown();
        }
        if (nodeB != null) {
            nodeB.shutdown();
        }
    }

    @Test
    @DisplayName("replay re-raises the timeout and does not consume the late signal")
    void replayAfterLateSignal_reRaisesTimeout_leavesSignalUnconsumed() throws Exception {
        var workflow = new GatedWorkflow();
        var method = GatedWorkflow.class.getMethod("run");
        var workflowId = "determinism-1";

        nodeA.startWorkflow(workflowId, "GatedWorkflow", "default", null, workflow, method);

        // Original execution: the gate times out (300ms), the workflow proceeds
        // and parks awaiting "second".
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // Node stops gracefully mid-park — the workflow stays recoverable.
        nodeA.shutdown();
        nodeA = null;

        // The gate signal arrives LATE, between the stop and the recovery.
        nodeB = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        nodeB.deliverSignal(workflowId, "gate", "late-arrival");

        // Recovery replay on the replacement node.
        var reg = new WorkflowRegistration("GatedWorkflow", "default", new GatedWorkflow(), method);
        nodeB.recoverWorkflows(Map.of("GatedWorkflow", reg));

        // Let the replay reach the "second" park again, then release it.
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));
        nodeB.deliverSignal(workflowId, "second", "go");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status().isTerminal())
                        .orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals("proceeded:go",
                serializer.deserialize(instance.output(), String.class),
                "replay must re-raise the timeout — the same branch as the original "
                        + "execution — not consume the late-arriving gate signal");
        assertFalse(store.getUnconsumedSignals(workflowId, "gate").isEmpty(),
                "the late gate signal must remain durably unconsumed (never discarded)");
        assertTrue(store.getEvents(instance.id()).stream()
                        .anyMatch(e -> e.eventType() == EventType.SIGNAL_TIMEOUT),
                "the timeout must be memoized as a SIGNAL_TIMEOUT event at the await's slot");
    }

    @Test
    @DisplayName("saga shape: the late withdrawal is honoured at gate #2 and the compensation runs (no leak)")
    void sagaReplayAfterLateWithdrawal_compensatesReservedResource() throws Exception {
        var reserved = new AtomicInteger();
        var released = new AtomicInteger();
        var workflow = new SagaGateWorkflow(reserved, released);
        var method = SagaGateWorkflow.class.getMethod("run");
        var workflowId = "saga-no-leak-1";

        nodeA.startWorkflow(workflowId, "SagaGateWorkflow", "default", null, workflow, method);

        // Original execution: gate #1 times out, the resource is reserved (with
        // its compensation registered), the workflow parks at gate #2.
        await().atMost(Duration.ofSeconds(5)).until(() -> reserved.get() >= 1);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // Graceful stop; the withdrawal lands late, before the replacement recovers.
        nodeA.shutdown();
        nodeA = null;
        nodeB = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        nodeB.deliverSignal(workflowId, "withdraw", "late-withdrawal");

        var reg = new WorkflowRegistration("SagaGateWorkflow", "default",
                new SagaGateWorkflow(reserved, released), method);
        nodeB.recoverWorkflows(Map.of("SagaGateWorkflow", reg));

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status().isTerminal())
                        .orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.FAILED, instance.status());
        assertTrue(serializer.deserialize(instance.output(), ErrorShape.class)
                        .message().contains("gate 2"),
                "the replay must follow the original path and honour the withdrawal at "
                        + "gate #2, where the compensation stack is populated");
        assertTrue(released.get() >= 1,
                "the reserved resource must be compensated — the divergent replay "
                        + "left it leaked");
    }

    /** Matches the executor's error-payload shape for FAILED outputs. */
    record ErrorShape(String exceptionType, String message) {
    }

    /**
     * Gate-then-proceed shape: a timeout-guarded await whose branch outcome is
     * visible in the output, followed by a second await that parks the run.
     */
    @DurableWorkflow(name = "GatedWorkflow")
    public static class GatedWorkflow {

        /** @return which branch ran, plus the second signal's payload */
        @WorkflowMethod
        public String run() {
            var wf = WorkflowContext.current();
            String outcome;
            try {
                wf.awaitSignal("gate", String.class, Duration.ofMillis(300));
                outcome = "gate-signal-consumed";
            } catch (SignalTimeoutException e) {
                outcome = "proceeded";
            }
            String second = wf.awaitSignal("second", String.class, Duration.ofMinutes(5));
            return outcome + ":" + second;
        }
    }

    /**
     * The loan-saga shape reduced to its essentials: withdrawal gate #1
     * (timeout-guarded), resource reservation with compensation, withdrawal
     * gate #2 (long await) whose consumption fails the workflow and must
     * trigger the compensation.
     */
    @DurableWorkflow(name = "SagaGateWorkflow")
    public static class SagaGateWorkflow {

        private final AtomicInteger reserved;
        private final AtomicInteger released;

        SagaGateWorkflow(AtomicInteger reserved, AtomicInteger released) {
            this.reserved = reserved;
            this.released = released;
        }

        /** @return never — the workflow is always withdrawn in this test */
        @WorkflowMethod
        public String run() {
            var wf = WorkflowContext.current();
            try {
                wf.awaitSignal("withdraw", String.class, Duration.ofMillis(300));
                throw new RuntimeException("withdrawn at gate 1");
            } catch (SignalTimeoutException e) {
                // not withdrawn — continue
            }
            reserved.incrementAndGet();
            wf.addCompensation(released::incrementAndGet);
            wf.awaitSignal("withdraw", String.class, Duration.ofMinutes(5));
            throw new RuntimeException("withdrawn at gate 2");
        }
    }
}
