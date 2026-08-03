package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Characterises what Maestro <em>actually</em> does when a multi-node
 * deployment is configured with no {@code DistributedLock} backend.
 *
 * <h2>The documented design, restated</h2>
 * <p>{@code WorkflowInstanceLockManager} reports {@code NO_BACKEND} rather than
 * refusing to run, and callers "proceed exactly as before this lock existed —
 * the store's unique event index and optimistic instance versioning remain the
 * correctness backstop". These tests pin what that costs and what it still
 * buys, because the difference matters to anyone choosing to deploy without a
 * lock:
 *
 * <ul>
 *   <li><b>Duplicate execution is real.</b> A second node's recovery launches a
 *       second copy of a workflow the first node is still running, and the
 *       activity that was in flight <em>is executed twice</em> — once per node.
 *       Activities must be idempotent in this configuration.</li>
 *   <li><b>Durably completed steps are still never re-run.</b> Memoization is
 *       independent of the lock, so the adopting node replays them.</li>
 *   <li><b>The event log stays single-valued.</b> The unique index on
 *       {@code (workflow_instance_id, sequence_number)} lets exactly one node
 *       win each sequence; the loser <em>discards its own result</em> and
 *       adopts the winner's, so both nodes converge on one output and the
 *       workflow completes exactly once.</li>
 *   <li><b>Finalisation converges too.</b> The second node to write the
 *       terminal status finds the row already versioned past its read and
 *       stands down instead of recording a failure. This is not free — it was
 *       BUG7, found by the concurrent-adoption test below, and fixed in
 *       {@code WorkflowExecutor}; before the fix a fully successful workflow
 *       was left {@code FAILED} with an {@code OptimisticLockException} as its
 *       output, contradicting its own {@code WORKFLOW_COMPLETED} event.</li>
 * </ul>
 *
 * <p>These are observations, not aspirations: if the engine's behaviour
 * changes, these tests must be re-read and re-written, not patched.
 */
@Tag("integration")
@DisplayName("With no lock backend two nodes both run the workflow, and only the store's unique index keeps them consistent")
class MultiNodeNoLockBackendIT extends PostgresIntegrationSupport {

    private final CountDownLatch wedge = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwo = new CountDownLatch(1);
    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        wedge.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("node B adopts a workflow node A is still running: the in-flight step runs twice, and node A's result is discarded in favour of node B's")
    void secondNodeAdoptsALiveWorkflow_theInFlightStepRunsTwice_andTheLosersResultIsDiscarded()
            throws Exception {
        // Each node stamps its results, so the event log itself says which node
        // won every sequence — a plain fixture could not tell them apart.
        nodeA = node("node-a", new SuffixedChainActivities(recorderA, "A", wedge));
        nodeB = node("node-b", new SuffixedChainActivities(recorderB, "B", null));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("nolock");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertEquals(true, reachedStepTwo.await(15, TimeUnit.SECONDS));

        // Reality check #1: with no lock, adoption of a live workflow succeeds.
        assertEquals(1, nodeA.executor().runningCount());
        assertEquals(1, nodeB.recover(),
                "no lock backend means nothing stops node B launching a second copy");

        handle.awaitStatus(WorkflowStatus.COMPLETED, Duration.ofSeconds(20));
        assertEquals("seed-one-A-two-B-three-B", handle.result(String.class),
                "node B ran the workflow to completion while node A was still inside step two");

        // Reality check #2: memoization is lock-independent — the durable step
        // is replayed, never re-executed.
        assertEquals(0, recorderB.count("stepOne"));
        assertEquals(1, recorderB.count("stepTwo"));
        assertEquals(1, recorderB.count("stepThree"));

        // Reality check #3: the in-flight step really did run on both nodes.
        assertEquals(2, recorderA.count("stepTwo") + recorderB.count("stepTwo"),
                "step two executed once per node — activities must be idempotent here");

        // Let node A's zombie finish and observe what the store does to it.
        wedge.countDown();
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> !nodeA.executor().isRunning(workflowId));

        // Reality check #4: node A's own step-two result never reaches the log;
        // it reads node B's back and its step three is replayed, not run.
        assertEquals(0, recorderA.count("stepThree"),
                "node A replays step three from node B's event rather than executing it");
        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                handle.events().stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList(),
                "exactly one event per sequence survives — the unique index is the guard");
        assertEquals(WorkflowStatus.COMPLETED, handle.status());
        assertEquals("seed-one-A-two-B-three-B", handle.result(String.class),
                "node A converges on node B's output instead of overwriting it with its own");
    }

    @Test
    @DisplayName("both nodes adopt the same ownerless workflow, and the store still yields one event per sequence and one output")
    void concurrentRecoveryWithNoLock_bothNodesAdopt_butTheStoreStaysSingleValued() throws Exception {
        nodeA = node("node-a", new SuffixedChainActivities(recorderA, "A", null));
        nodeB = node("node-b", new SuffixedChainActivities(recorderB, "B", null));

        var workflowId = seedOwnerlessRunningInstance();

        var barrier = new CyclicBarrier(2);
        var launched = new AtomicInteger();
        var raceA = recoverOnBarrier(nodeA, barrier, launched);
        var raceB = recoverOnBarrier(nodeB, barrier, launched);
        raceA.join();
        raceB.join();

        assertEquals(2, launched.get(),
                "without a lock both nodes adopt the same workflow — this is the behaviour, "
                        + "not a defect to be worked around in the test");

        // Sequence 4 is WORKFLOW_COMPLETED, appended one write after the status
        // turns COMPLETED — wait for the event, not the status. See TerminalWait.
        var instance = awaitStatus(workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(20));
        var events = store.getEvents(instance.id());
        assertEquals(List.of(1, 2, 3, 4),
                events.stream().map(e -> e.sequenceNumber()).toList(),
                "one event per sequence, no gaps, no duplicates");
        assertEquals(1, events.stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_COMPLETED)
                        .count(),
                "the workflow completes exactly once however many nodes ran it");

        // Which node won each sequence is genuinely racy; that the surviving
        // output agrees with the surviving event log is not.
        var stepThree = events.get(2);
        assertNotNull(instance.output());
        assertEquals(serializer.deserialize(stepThree.payload(), String.class),
                serializer.deserialize(instance.output(), String.class),
                "the recorded output is the memoized last step, whichever node wrote it");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Builds one node with <b>no</b> lock backend — the configuration under
     * characterisation.
     *
     * @param serviceName the node's service name
     * @param activities  this node's activity implementation
     * @return a harness with the chain workflow registered
     */
    private MaestroEngineHarness node(String serviceName,
                                      CountingActivities.ChainActivities activities) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(null)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class, activities);
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return harness;
    }

    /**
     * Inserts a RUNNING instance with no events, modelling a node that died
     * before writing anything.
     *
     * @return the seeded workflow's business ID
     */
    private String seedOwnerlessRunningInstance() {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("nolock-ownerless");
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("ChainWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .input(serializer.serialize("seed"))
                .serviceName("node-gone")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
        return workflowId;
    }

    private static Thread recoverOnBarrier(MaestroEngineHarness node, CyclicBarrier barrier,
                                           AtomicInteger launched) {
        return Thread.ofPlatform().start(() -> {
            try {
                barrier.await(15, TimeUnit.SECONDS);
                launched.addAndGet(node.recover());
            } catch (Exception e) {
                throw new IllegalStateException("recovery race thread failed", e);
            }
        });
    }

    /**
     * Chain activities that stamp every result with the node that produced it,
     * optionally blocking inside step two until a gate opens.
     */
    private final class SuffixedChainActivities implements CountingActivities.ChainActivities {

        private final CountingActivities.Recorder recorder;
        private final String suffix;
        private final @Nullable CountDownLatch gate;

        private SuffixedChainActivities(CountingActivities.Recorder recorder, String suffix,
                                        @Nullable CountDownLatch gate) {
            this.recorder = recorder;
            this.suffix = suffix;
            this.gate = gate;
        }

        @Override
        public String stepOne(String input) {
            recorder.record("stepOne");
            return input + "-one-" + suffix;
        }

        @Override
        public String stepTwo(String input) {
            recorder.record("stepTwo");
            if (gate != null) {
                reachedStepTwo.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return input + "-two-" + suffix;
        }

        @Override
        public String stepThree(String input) {
            recorder.record("stepThree");
            return input + "-three-" + suffix;
        }
    }
}
