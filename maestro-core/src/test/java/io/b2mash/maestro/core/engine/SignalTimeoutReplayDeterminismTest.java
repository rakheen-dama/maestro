package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
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
        var proceeded = new java.util.concurrent.CountDownLatch(1);
        var workflow = new GatedWorkflow(proceeded);
        var method = GatedWorkflow.class.getMethod("run");
        var workflowId = "determinism-1";

        nodeA.startWorkflow(workflowId, "GatedWorkflow", "default", null, workflow, method);

        // Original execution: wait until the gate has GENUINELY timed out (the
        // latch trips inside the catch) — shutting down during the gate park
        // itself would be the legitimate crash-before-memo window, a different
        // scenario — then until the run parks awaiting "second".
        assertTrue(proceeded.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the gate should time out and the workflow proceed");
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
        var reg = new WorkflowRegistration("GatedWorkflow", "default",
                new GatedWorkflow(new java.util.concurrent.CountDownLatch(1)), method);
        nodeB.recoverWorkflows(Map.of("GatedWorkflow", reg));

        // Let the replay reach the "second" park again, then release it.
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));
        nodeB.deliverSignal(workflowId, "second", "go");

        TestTerminalWait.awaitTerminal(store, workflowId, Duration.ofSeconds(5));

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

        TestTerminalWait.awaitTerminal(store, workflowId, Duration.ofSeconds(5));

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

    @Test
    @DisplayName("retry of a timeout-failed workflow deletes the failing timeout memo and re-drives")
    void retryAfterTimeoutFailure_reDrivesThroughTheAwait() throws Exception {
        var workflow = new SingleAwaitWorkflow();
        var method = SingleAwaitWorkflow.class.getMethod("run");
        var workflowId = "retry-redrive-1";

        nodeA.startWorkflow(workflowId, "SingleAwaitWorkflow", "default", null, workflow, method);

        // The await times out uncaught → FAILED, with a SIGNAL_TIMEOUT memo.
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, Duration.ofSeconds(5));

        // Operator fixes the fault (delivers the signal) and retries.
        nodeA.deliverSignal(workflowId, "approval", "granted");
        var reg = new WorkflowRegistration("SingleAwaitWorkflow", "default",
                new SingleAwaitWorkflow(), method);
        nodeA.retryWorkflow(workflowId, reg);

        TestTerminalWait.awaitTerminal(store, workflowId, Duration.ofSeconds(5));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                "retry must delete the FAILING timeout memo so the re-driven await can "
                        + "consume the now-delivered signal — not deterministically re-time-out");
        assertEquals("granted", serializer.deserialize(instance.output(), String.class));
    }

    @Test
    @DisplayName("retry preserves an earlier CAUGHT gate-timeout memo (pre-failure determinism)")
    void retryAfterActivityFailure_preservesCaughtGateTimeoutMemo() throws Exception {
        var failOnce = new AtomicInteger(1);
        var proceeded = new java.util.concurrent.CountDownLatch(1);
        var workflow = new GateThenFlakyWorkflow(failOnce, proceeded);
        var method = GateThenFlakyWorkflow.class.getMethod("run");
        var workflowId = "retry-gate-memo-1";

        nodeA.startWorkflow(workflowId, "GateThenFlakyWorkflow", "default", null, workflow, method);

        // Gate times out (caught, memoized); the flaky step then fails → FAILED.
        assertTrue(proceeded.await(5, java.util.concurrent.TimeUnit.SECONDS));
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, Duration.ofSeconds(5));

        // A late gate signal arrives before the retry. The retry's replay must
        // STILL take the timed-out branch (the caught memo survives) — deleting
        // it would resurrect the Issue 19 divergence through the retry door.
        nodeA.deliverSignal(workflowId, "gate", "late-arrival");
        var reg = new WorkflowRegistration("GateThenFlakyWorkflow", "default",
                new GateThenFlakyWorkflow(failOnce, new java.util.concurrent.CountDownLatch(1)),
                method);
        nodeA.retryWorkflow(workflowId, reg);

        TestTerminalWait.awaitTerminal(store, workflowId, Duration.ofSeconds(5));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals("proceeded", serializer.deserialize(instance.output(), String.class),
                "the caught gate memo must survive retry — the replay must not consume "
                        + "the late gate signal");
        assertFalse(store.getUnconsumedSignals(workflowId, "gate").isEmpty(),
                "the late gate signal stays durably unconsumed across the retry");
    }

    /** Matches the executor's error-payload shape for FAILED outputs. */
    record ErrorShape(String exceptionType, String message) {
    }

    /** One uncaught timeout-guarded await: fails on timeout, returns the payload otherwise. */
    @DurableWorkflow(name = "SingleAwaitWorkflow")
    public static class SingleAwaitWorkflow {

        /** @return the awaited payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current()
                    .awaitSignal("approval", String.class, Duration.ofMillis(300));
        }
    }

    /**
     * Caught gate timeout followed by a step that fails on its first
     * invocation — the retry re-drives the step while the gate memo must
     * replay deterministically.
     */
    @DurableWorkflow(name = "GateThenFlakyWorkflow")
    public static class GateThenFlakyWorkflow {

        private final AtomicInteger failuresRemaining;
        private final java.util.concurrent.CountDownLatch proceeded;

        GateThenFlakyWorkflow(AtomicInteger failuresRemaining,
                              java.util.concurrent.CountDownLatch proceeded) {
            this.failuresRemaining = failuresRemaining;
            this.proceeded = proceeded;
        }

        /** @return which branch the gate took */
        @WorkflowMethod
        public String run() {
            var wf = WorkflowContext.current();
            String outcome;
            try {
                wf.awaitSignal("gate", String.class, Duration.ofMillis(300));
                outcome = "gate-signal-consumed";
            } catch (SignalTimeoutException e) {
                outcome = "proceeded";
                proceeded.countDown();
            }
            if (failuresRemaining.getAndDecrement() > 0) {
                throw new RuntimeException("flaky step failure");
            }
            return outcome;
        }
    }

    /**
     * Gate-then-proceed shape: a timeout-guarded await whose branch outcome is
     * visible in the output, followed by a second await that parks the run.
     */
    @DurableWorkflow(name = "GatedWorkflow")
    public static class GatedWorkflow {

        private final java.util.concurrent.CountDownLatch proceeded;

        GatedWorkflow(java.util.concurrent.CountDownLatch proceeded) {
            this.proceeded = proceeded;
        }

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
                proceeded.countDown();
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
