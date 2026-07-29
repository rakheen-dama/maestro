package io.b2mash.maestro.integration.shutdown;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graceful-shutdown contract of the engine, against a real PostgreSQL store
 * and a real Postgres lock backend (docs/test-plan.md §P5).
 *
 * <p>Stopping a node is a routine operational event — a deploy, a scale-in, a
 * pod eviction. From a workflow's point of view it must be indistinguishable
 * from that node never having run it: a workflow parked on {@code awaitSignal}
 * or {@code sleep} holds durable state that is still perfectly valid, so
 * shutdown must
 *
 * <ol>
 *   <li>leave it {@code WAITING_SIGNAL}/{@code WAITING_TIMER} and recoverable,
 *       never {@code FAILED};</li>
 *   <li>run no compensation — nothing failed;</li>
 *   <li>let in-flight activities drain instead of killing them;</li>
 *   <li>hand the instance lock back, so a surviving node adopts the workflow at
 *       once rather than waiting out the TTL;</li>
 *   <li>and leave the workflow completable by the next process that starts.</li>
 * </ol>
 *
 * <p>Only a real store settles most of this: "recoverable" means
 * {@code getRecoverableInstances()} returns the row, "no compensation" means no
 * compensation events in the durable log, and "the lock was released" means a
 * second lock client can take it immediately — none of which an in-memory fake
 * can prove.
 *
 * <p>The last test is the round trip that matters operationally: node A parks a
 * workflow, node A shuts down, node B starts, recovers it from Postgres alone
 * and runs it to completion — with node B's own activity counters proving the
 * pre-shutdown step was replayed from the event log rather than re-executed.
 */
@Tag("integration")
@DisplayName("Graceful shutdown leaves parked workflows recoverable, never failed")
class ShutdownContractIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    /** Long enough that no timer or await can fire during a test. */
    private static final Duration NEVER = Duration.ofMinutes(10);
    /** Deliberately longer than any test: an unreleased lock stays unavailable. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private CountingActivities.Recorder recorder;
    private final CountDownLatch releaseInFlight = new CountDownLatch(1);

    private @Nullable MaestroEngineHarness nodeA;
    private @Nullable MaestroEngineHarness nodeB;

    @BeforeEach
    void newRecorder() {
        recorder = new CountingActivities.Recorder();
    }

    @AfterEach
    void closeHarnesses() {
        // Free any wedged activity first, so a failed assertion cannot leave
        // close() blocking on the shutdown drain timeout.
        releaseInFlight.countDown();
        if (nodeB != null) {
            nodeB.close();
            nodeB = null;
        }
        if (nodeA != null) {
            nodeA.close();
            nodeA = null;
        }
    }

    // ── 1. Parked workflows stay recoverable ───────────────────────────

    @Test
    @DisplayName("a workflow parked on awaitSignal stays WAITING_SIGNAL and stays recoverable")
    void shutdown_withWorkflowParkedOnSignal_leavesItWaitingSignalAndRecoverable() throws Exception {
        var parked = new CountDownLatch(1);
        nodeA = node("node-a", new CountingActivities.RecordingChainActivities(recorder));
        nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-signal");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        nodeA.close();

        assertEquals(WorkflowStatus.WAITING_SIGNAL, handle.status(),
                "shutdown must not fail a workflow that was merely waiting for a signal");
        assertFalse(handle.isRunningLocally(),
                "the parked thread must have exited, otherwise shutdown never reached it");
        assertEquals(List.of(workflowId),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList(),
                "the workflow must still be recoverable by another node");
        assertFalse(hasEvent(handle.instanceId(), EventType.WORKFLOW_FAILED),
                "no WORKFLOW_FAILED event may be written for a graceful shutdown");
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(0, recorder.count("stepTwo"), "the post-signal step must not have run");
    }

    @Test
    @DisplayName("a workflow parked on sleep stays WAITING_TIMER with its timer still pending")
    void shutdown_withWorkflowParkedOnSleep_leavesItWaitingTimerAndRecoverable() {
        nodeA = node("node-a", new CountingActivities.RecordingChainActivities(recorder));
        nodeA.registerWorkflow(new TestWorkflows.SleepingWorkflow(NEVER));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-sleep");
        var handle = nodeA.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);

        nodeA.close();

        assertEquals(WorkflowStatus.WAITING_TIMER, handle.status(),
                "shutdown must not fail a workflow that was merely sleeping on a durable timer");
        assertFalse(handle.isRunningLocally());
        assertEquals(List.of(workflowId),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());
        assertFalse(hasEvent(handle.instanceId(), EventType.WORKFLOW_FAILED));
        // The durable timer is untouched: the TIMER_SCHEDULED event stands and
        // no TIMER_FIRED was forged, so the sleep resumes correctly on recovery.
        assertEquals(EventType.TIMER_SCHEDULED,
                store.getEventBySequence(handle.instanceId(), 2).orElseThrow().eventType());
        assertTrue(store.getEventBySequence(handle.instanceId(), 3).isEmpty(),
                "shutdown must not record the sleep as finished");
    }

    // ── 2. No compensation on shutdown ─────────────────────────────────

    @Test
    @DisplayName("a saga parked after committing compensable work is not compensated by shutdown")
    void shutdown_withSagaParkedAfterCommittedWork_runsNoCompensation() throws Exception {
        var parked = new CountDownLatch(1);
        nodeA = node("node-a", null);
        nodeA.registerActivities(SagaActivities.class,
                new CountingActivities.RecordingSagaActivities(recorder, null));
        nodeA.registerWorkflow(new ShutdownWorkflows.ParkingSagaWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-saga");
        var handle = nodeA.start(workflowId, ShutdownWorkflows.ParkingSagaWorkflow.class, "widget");
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        assertEquals(1, recorder.count("reserve"));
        assertEquals(1, recorder.count("charge"));

        nodeA.close();

        assertEquals(0, recorder.count("refund"),
                "shutting a node down must not refund a customer whose order is merely awaiting approval");
        assertEquals(0, recorder.count("releaseReservation"),
                "shutting a node down must not release stock reserved for a live order");
        assertEquals(WorkflowStatus.WAITING_SIGNAL, handle.status());
        assertFalse(hasEvent(handle.instanceId(), EventType.COMPENSATION_STARTED),
                "no compensation may be recorded in the durable event log");
        assertFalse(hasEvent(handle.instanceId(), EventType.WORKFLOW_FAILED));
    }

    @Test
    @DisplayName("shutdown mid-compensation leaves the workflow recoverable with no step recorded failed, "
            + "and a fresh node completes the compensation")
    void shutdown_duringCompensation_leavesItRecoverableAndCompletesOnRecovery() throws Exception {
        var released = new AtomicInteger();
        nodeA = node("node-a", null);
        nodeA.registerWorkflow(new ShutdownWorkflows.ParkingCompensationWorkflow(released));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-mid-comp");
        var handle = nodeA.start(workflowId, ShutdownWorkflows.ParkingCompensationWorkflow.class, "seed");

        // LIFO order runs "parking-compensation" (registered second) before
        // "release" (registered first) — shutdown here interrupts the very
        // first compensation, before the second has had a chance to run.
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        assertTrue(hasEvent(handle.instanceId(), EventType.COMPENSATION_STARTED),
                "must be genuinely mid-compensation, not an ordinary park");

        nodeA.close();

        assertEquals(0, released.get(), "the compensation later in LIFO order must not have run yet");
        assertFalse(hasEvent(handle.instanceId(), EventType.COMPENSATION_STEP_FAILED),
                "the compensation step interrupted by shutdown must not be recorded as failed");
        assertFalse(hasEvent(handle.instanceId(), EventType.WORKFLOW_FAILED),
                "shutdown must not finalise the workflow as FAILED while compensation is unresolved");
        assertTrue(handle.status().isActive(),
                "the instance must stay in an active, recoverable status — was " + handle.status());
        assertEquals(List.of(workflowId),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());

        // The instance lock is released too, even though shutdown landed
        // mid-compensation rather than mid-park — same guarantee as the
        // dedicated lock test above, exercised on this shape.
        var lockKey = "maestro:lock:workflow:" + workflowId;
        var peer = newLock();
        var adopted = peer.tryAcquire(lockKey, LOCK_TTL);
        assertTrue(adopted.isPresent(),
                "a shut-down node must hand its instance lock back even when shutdown landed mid-compensation");
        peer.release(adopted.orElseThrow());

        // A fresh node recovers it: the interrupted compensation re-parks
        // (nothing was memoized for it — it never completed), and once
        // resumed, finishes compensation and reaches FAILED, the correct
        // outcome for a genuine failure.
        var restartedReleased = new AtomicInteger();
        nodeA = null;
        nodeB = node("node-b", null);
        nodeB.registerWorkflow(new ShutdownWorkflows.ParkingCompensationWorkflow(restartedReleased));

        assertEquals(1, nodeB.recover(),
                "the workflow abandoned mid-compensation must be recoverable from Postgres alone");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        nodeB.deliverSignal(workflowId, ShutdownWorkflows.ParkingCompensationWorkflow.RESUME_SIGNAL, "resumed");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        assertEquals(1, restartedReleased.get(), "the remaining compensation must complete once resumed");
        assertFalse(hasEvent(handle.instanceId(), EventType.COMPENSATION_STEP_FAILED),
                "no compensation step may be recorded failed across the full recovery");
        assertTrue(hasEvent(handle.instanceId(), EventType.COMPENSATION_COMPLETED));
        assertTrue(hasEvent(handle.instanceId(), EventType.WORKFLOW_FAILED),
                "the genuine failure must still be finalised once compensation completes");
    }

    // ── 3. In-flight activities drain ──────────────────────────────────

    @Test
    @DisplayName("shutdown blocks until an in-flight activity drains, then records its result")
    void shutdown_withInFlightActivity_drainsItInsteadOfKillingIt() throws Exception {
        var enteredStepTwo = new CountDownLatch(1);
        nodeA = node("node-a", new BlockingChainActivities(enteredStepTwo));
        nodeA.registerWorkflow(new TestWorkflows.ChainWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-drain");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertTrue(enteredStepTwo.await(30, TimeUnit.SECONDS), "step two must be in flight");

        // Shut down from another thread so this one can observe that shutdown
        // *blocks* while the activity is unfinished — the direct evidence of
        // draining, with nothing sleeping in place of synchronisation.
        var shutdownReturned = new CountDownLatch(1);
        var shutdownNode = nodeA;
        Thread.ofVirtual().start(() -> {
            shutdownNode.close();
            shutdownReturned.countDown();
        });

        assertFalse(shutdownReturned.await(500, TimeUnit.MILLISECONDS),
                "shutdown must wait for in-flight work rather than returning over the top of it");
        assertEquals(WorkflowStatus.RUNNING, handle.status());

        releaseInFlight.countDown();

        assertTrue(shutdownReturned.await(30, TimeUnit.SECONDS), "shutdown must return once work drains");
        assertEquals(WorkflowStatus.COMPLETED, handle.status(),
                "an activity in flight at shutdown must be allowed to finish and be recorded");
        assertEquals("seed-one-two-three", handle.result(String.class));
        nodeA = null; // already closed above
    }

    // ── 4. Instance locks are handed back ──────────────────────────────

    @Test
    @DisplayName("shutdown releases the instance lock of a parked workflow instead of leaving it to the TTL")
    void shutdown_withWorkflowParkedOnSignal_releasesTheInstanceLock() throws Exception {
        var parked = new CountDownLatch(1);
        nodeA = node("node-a", new CountingActivities.RecordingChainActivities(recorder));
        nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-lock");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        var lockKey = "maestro:lock:workflow:" + workflowId;
        var peer = newLock();
        assertTrue(peer.tryAcquire(lockKey, LOCK_TTL).isEmpty(),
                "node A must hold the instance lock while the workflow is parked on it");

        nodeA.close();

        // The lock TTL is a minute; acquiring it now can only mean node A
        // released it rather than leaving peers to wait the TTL out.
        var adopted = peer.tryAcquire(lockKey, LOCK_TTL);
        assertTrue(adopted.isPresent(),
                "a shut-down node must hand its instance locks back, not strand them until the TTL expires");
        peer.release(adopted.orElseThrow());
    }

    // ── 5. Restart round trip ──────────────────────────────────────────

    @Test
    @DisplayName("a node started after the shutdown recovers the parked workflow and completes it")
    void afterShutdown_aFreshNodeRecoversTheParkedWorkflowAndCompletesIt() throws Exception {
        var parked = new CountDownLatch(1);
        nodeA = node("node-a", new CountingActivities.RecordingChainActivities(recorder));
        nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("shutdown-restart");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        nodeA.close();
        nodeA = null;

        // A brand-new process: its own recorder, so every counter below is
        // node B's work alone and cannot be confused with node A's.
        var restartedRecorder = new CountingActivities.Recorder();
        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(restartedRecorder));
        nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        assertEquals(1, nodeB.recover(),
                "the workflow left parked by the shutdown must be recoverable from Postgres alone");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one:approved-two", handle.result(String.class),
                "the recovered run must produce what an uninterrupted one would have");
        assertEquals(0, restartedRecorder.count("stepOne"),
                "step one was durable before the shutdown — recovery must replay it, not re-run it");
        assertEquals(1, restartedRecorder.count("stepTwo"),
                "the post-signal step must run exactly once, on the node that finished the workflow");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Builds one node over the shared store, with a real Postgres lock backend
     * and a TTL long enough that lock release must be explicit to be observed.
     *
     * @param serviceName the node's service name — distinct per node
     * @param chain       chain activities to register, or {@code null} for none
     * @return a harness with nothing registered yet
     */
    private MaestroEngineHarness node(String serviceName, @Nullable ChainActivities chain) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .build();
        if (chain != null) {
            harness.registerActivities(ChainActivities.class, chain);
        }
        return harness;
    }

    private boolean hasEvent(UUID instanceId, EventType type) {
        return store.getEvents(instanceId).stream().anyMatch(e -> e.eventType() == type);
    }

    /**
     * Chain activities whose second step blocks until the test releases it —
     * an activity that is genuinely in flight when shutdown begins.
     */
    private final class BlockingChainActivities implements ChainActivities {

        private final CountDownLatch enteredStepTwo;

        BlockingChainActivities(CountDownLatch enteredStepTwo) {
            this.enteredStepTwo = enteredStepTwo;
        }

        @Override
        public String stepOne(String input) {
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            enteredStepTwo.countDown();
            try {
                if (!releaseInFlight.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("in-flight activity was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("in-flight activity was interrupted", e);
            }
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            return input + "-three";
        }
    }
}
