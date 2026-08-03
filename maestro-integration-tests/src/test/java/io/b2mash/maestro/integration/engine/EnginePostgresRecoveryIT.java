package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves crash recovery against a real PostgreSQL store: a second engine
 * process picks up a workflow abandoned mid-flight, replays its completed steps
 * from the event log and resumes at the first uncompleted one.
 *
 * <h2>How the crash is simulated</h2>
 * <p>Node A starts the workflow with an activity implementation that blocks
 * forever on a latch. The workflow's virtual thread is then wedged exactly like
 * a thread inside a JVM that is about to be {@code kill -9}'d: step one is
 * durably recorded, step two has produced nothing, and node A will never write
 * another row. Node B — a second {@link MaestroEngineHarness} over the
 * <em>same</em> store, which is what "a new process" means in one JVM — then
 * recovers it.
 *
 * <p>Node A is deliberately built <b>without a lock backend</b>. A crashed JVM
 * stops renewing its instance lock and the lock expires by TTL; a wedged
 * in-process harness would keep renewing forever, because the lock renewer
 * cannot be stopped without {@code shutdown()} — and {@code shutdown()} would
 * join the wedged thread. Giving node A no lock backend reproduces the
 * post-expiry state directly. Node B keeps a real Postgres lock, so the
 * adoption path is exercised with locking engaged.
 *
 * <p>Each node has its own recorder and its own activity implementation, so
 * "node B did not re-execute step one" is asserted on node B's counters alone
 * and cannot be confused with node A's work.
 */
@Tag("integration")
@DisplayName("A second engine process recovers a workflow abandoned mid-flight")
class EnginePostgresRecoveryIT extends PostgresIntegrationSupport {

    private final CountDownLatch wedge = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwo = new CountDownLatch(1);
    private final CountingActivities.Recorder crashedRecorder = new CountingActivities.Recorder();

    private MaestroEngineHarness crashedNode;
    private MaestroEngineHarness recoveringNode;

    @AfterEach
    void releaseAndClose() {
        // Free the wedged thread only after the assertions have run, so node A's
        // zombie can exit and node A's shutdown does not block.
        wedge.countDown();
        if (recoveringNode != null) {
            recoveringNode.close();
        }
        if (crashedNode != null) {
            crashedNode.close();
        }
    }

    @Test
    @DisplayName("recover() replays completed steps and resumes at the first uncompleted step")
    void recover_replaysCompletedStepsAndResumesAtFirstUncompletedStep() throws Exception {
        var workflowId = crashMidFlight();

        var recoveredRecorder = new CountingActivities.Recorder();
        recoveringNode = node("node-b", new CountingActivities.RecordingChainActivities(recoveredRecorder),
                true);

        assertEquals(1, recoveringNode.recover(), "the abandoned workflow must be recoverable");
        awaitStatus(workflowId, WorkflowStatus.COMPLETED);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals("seed-one-two-three", serializer.deserialize(instance.output(), String.class),
                "the recovered run must produce the same result as an uninterrupted one");

        // The whole point: the completed step came out of the event log.
        assertEquals(0, recoveredRecorder.count("stepOne"),
                "step one was already durable — recovery must replay it, not re-run it");
        assertEquals(1, recoveredRecorder.count("stepTwo"),
                "step two never completed — recovery must run it exactly once");
        assertEquals(1, recoveredRecorder.count("stepThree"));

        // The event log is continuous across the crash: node A's step one and
        // node B's steps two and three share one sequence space.
        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                store.getEvents(instance.id()).stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList());
        assertEquals(3, instance.eventSequence());
        // service_name records the owning *service*, not the owning node, and
        // recovery never rewrites it: every node of a service shares one
        // maestro.service-name, so adoption does not change ownership. Pinning
        // the observed behaviour — an assertion of "node-b" fails here.
        assertEquals("node-a", instance.serviceName(),
                "adoption by another node must not rewrite the owning service");
    }

    @Test
    @DisplayName("the recovery poller adopts an abandoned workflow without an explicit recover() call")
    void recoveryPoller_adoptsAbandonedWorkflow() throws Exception {
        var workflowId = crashMidFlight();

        var recoveredRecorder = new CountingActivities.Recorder();
        recoveringNode = node("node-b", new CountingActivities.RecordingChainActivities(recoveredRecorder),
                true);
        recoveringNode.startRecoveryPoller(Duration.ofMillis(200));

        awaitStatus(workflowId, WorkflowStatus.COMPLETED);
        assertEquals(0, recoveredRecorder.count("stepOne"));
        assertEquals(1, recoveredRecorder.count("stepTwo"));
        assertEquals(1, recoveredRecorder.count("stepThree"));

        // The poller keeps running; a terminal workflow must not be picked up
        // again on a later cycle.
        var versionAfterCompletion = store.getInstance(workflowId).orElseThrow().version();
        // Hold the "unchanged" condition across several 200ms poll cycles rather
        // than sampling once — a re-adoption would bump the version.
        await().during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(workflowId).orElseThrow().version()
                        == versionAfterCompletion);
        assertEquals(1, recoveredRecorder.count("stepTwo"), "no step may run twice");
    }

    @Test
    @DisplayName("recovery does not launch a second copy of a workflow already running on this node")
    void recover_onTheNodeStillRunningTheWorkflow_doesNotRelaunchIt() throws Exception {
        var workflowId = crashMidFlight();

        // The workflow is still in flight on node A — wedged, but very much
        // alive. Node A's own recovery pass sees it in the store as RUNNING and
        // must recognise it as locally owned. Node A has no lock backend here,
        // so the in-JVM running-workflow check is the only guard in play.
        assertEquals(0, crashedNode.recover(),
                "a workflow already running on this node must not be relaunched");
        assertTrue(crashedNode.executor().isRunning(workflowId));
        assertEquals(1, crashedRecorder.count("stepOne"));
        // The decisive counter: a relaunch would replay step one from the log
        // and then execute step two live for a second time.
        assertEquals(1, crashedRecorder.count("stepTwo"),
                "a relaunch would have entered the wedged step a second time");
    }

    @Test
    @DisplayName("recovery is a no-op when no workflow was left in an active state")
    void recover_withNothingAbandoned_recoversNothing() {
        var recorder = new CountingActivities.Recorder();
        recoveringNode = node("node-b", new CountingActivities.RecordingChainActivities(recorder),
                true);

        var handle = recoveringNode.start(MaestroEngineHarness.uniqueWorkflowId("recover-noop"),
                TestWorkflows.ChainWorkflow.class, "seed");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        assertTrue(store.getRecoverableInstances().isEmpty());
        assertEquals(0, recoveringNode.recover(), "nothing active means nothing to recover");
        assertEquals(1, recorder.count("stepOne"), "recovery must not re-run a completed workflow");
    }

    // ── crash simulation ──────────────────────────────────────────────────

    /**
     * Starts a workflow on node A and wedges it inside step two, leaving a
     * RUNNING instance whose event log stops after step one.
     *
     * @return the abandoned workflow's ID
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    private String crashMidFlight() throws InterruptedException {
        crashedNode = node("node-a", new WedgingChainActivities(), false);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("crash");
        var handle = crashedNode.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");

        assertTrue(reachedStepTwo.await(15, TimeUnit.SECONDS),
                "node A must reach step two before the simulated crash");
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getEventBySequence(handle.instanceId(), 1).isPresent());

        var abandoned = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, abandoned.status(),
                "the abandoned instance must still look active to recovery");
        assertEquals(EventType.ACTIVITY_COMPLETED,
                store.getEventBySequence(abandoned.id(), 1).orElseThrow().eventType());
        assertTrue(store.getEventBySequence(abandoned.id(), 2).isEmpty(),
                "step two must have produced nothing before the crash");
        return workflowId;
    }

    /**
     * Chain activities whose second step never returns — the wedged thread of a
     * process that is about to die.
     */
    private final class WedgingChainActivities implements CountingActivities.ChainActivities {

        @Override
        public String stepOne(String input) {
            crashedRecorder.record("stepOne");
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            crashedRecorder.record("stepTwo");
            reachedStepTwo.countDown();
            try {
                wedge.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            return input + "-three";
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Builds one node.
     *
     * @param serviceName the node's service name — distinct per node
     * @param activities  the chain activity implementation for this node
     * @param withLock    whether this node gets a Postgres lock backend
     * @return a harness with the chain workflow registered
     */
    private MaestroEngineHarness node(String serviceName,
                                      CountingActivities.ChainActivities activities,
                                      boolean withLock) {
        var built = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(withLock ? newLock() : null)
                .instanceLockTtl(Duration.ofSeconds(2))
                .build();
        built.registerActivities(CountingActivities.ChainActivities.class, activities);
        built.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return built;
    }

    /**
     * Waits until the workflow reaches the given status — and, when that status
     * is terminal, until its terminal event has been appended too.
     *
     * @param workflowId the workflow to wait for
     * @param expected   the awaited status
     */
    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        awaitStatus(workflowId, expected, Duration.ofSeconds(15));
    }
}
