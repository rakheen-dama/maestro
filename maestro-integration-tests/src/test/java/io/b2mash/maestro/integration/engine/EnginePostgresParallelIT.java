package io.b2mash.maestro.integration.engine;

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
 * Proves parallel-branch execution against a real PostgreSQL store.
 *
 * <p>Parallel branches partition the sequence space instead of sharing it:
 * branch {@code i} of a fork at parent sequence {@code p} starts at
 * {@code p × 1000 + (i + 1) × 1000}. That arithmetic is what keeps concurrent
 * branches from colliding on the {@code (workflow_instance_id, sequence_number)}
 * unique constraint, and it had never run against a real database — the loan
 * sample uses loop-fan-in, so {@code docs/test-plan.md} records parallel
 * branches as unexercised outside core unit tests.
 *
 * <p>The exact sequence numbers are asserted deliberately. They are persisted
 * into an {@code integer} column and read back by replay on another node; if
 * the partitioning scheme ever changes, every event log written by an older
 * version becomes unreplayable, so a silent change must break a test.
 */
@Tag("integration")
@DisplayName("Parallel branches partition the sequence space in Postgres")
class EnginePostgresParallelIT extends PostgresIntegrationSupport {

    private final CountDownLatch wedge = new CountDownLatch(1);
    private final CountDownLatch reachedThirdBranch = new CountDownLatch(1);

    private MaestroEngineHarness harness;
    private MaestroEngineHarness recoveringNode;

    @AfterEach
    void releaseAndClose() {
        wedge.countDown();
        if (recoveringNode != null) {
            recoveringNode.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("each branch persists its events in its own sequence block")
    void parallelBranches_persistDistinctSequenceBlocks() {
        var recorder = new CountingActivities.Recorder();
        harness = node("parallel-node", new CountingActivities.RecordingChainActivities(recorder), true);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("parallel");
        var handle = harness.start(workflowId, TestWorkflows.ParallelWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals("seed-one,seed-two,seed-three", handle.result(String.class),
                "branch results must be joined in branch order, not completion order");

        // Fork at sequence 1; branch bases 1×1000 + (i+1)×1000 → 2000/3000/4000,
        // each branch's first activity landing one past its base.
        assertEquals(
                List.of("1:SIDE_EFFECT:$maestro:parallel",
                        "2001:ACTIVITY_COMPLETED:chain.stepOne",
                        "3001:ACTIVITY_COMPLETED:chain.stepTwo",
                        "4001:ACTIVITY_COMPLETED:chain.stepThree",
                        "5001:WORKFLOW_COMPLETED:null"),
                describe(handle));

        // The parent counter resumes past every branch block, so anything the
        // workflow does after the join cannot collide with a branch.
        var instance = handle.instance();
        assertEquals(5000, instance.eventSequence());
        assertEquals(1, instance.version(), "one status transition, one version increment");

        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(1, recorder.count("stepThree"));
    }

    @Test
    @DisplayName("the fork point records how many branches were forked")
    void parallelFork_recordsBranchCount() {
        var recorder = new CountingActivities.Recorder();
        harness = node("parallel-node", new CountingActivities.RecordingChainActivities(recorder), true);

        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("parallel-fork"),
                TestWorkflows.ParallelWorkflow.class, "seed");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        var fork = handle.events().getFirst();
        assertEquals(3, fork.payload().get("branchCount").asInt(),
                "replay needs the branch count to reconstruct the same partitioning");
    }

    @Test
    @DisplayName("replay after a crash re-runs only the branch that never finished")
    void parallelReplay_resumesOnlyTheUnfinishedBranch() throws Exception {
        var crashedRecorder = new CountingActivities.Recorder();
        harness = node("node-a", new WedgingThirdBranch(crashedRecorder), false);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("parallel-crash");
        var handle = harness.start(workflowId, TestWorkflows.ParallelWorkflow.class, "seed");

        // Branches 0 and 1 finish and persist; branch 2 wedges — the crash point.
        assertTrue(reachedThirdBranch.await(15, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getEventBySequence(handle.instanceId(), 2001).isPresent()
                        && store.getEventBySequence(handle.instanceId(), 3001).isPresent());
        assertTrue(store.getEventBySequence(handle.instanceId(), 4001).isEmpty(),
                "the wedged branch must have produced nothing");

        var recoveredRecorder = new CountingActivities.Recorder();
        recoveringNode = node("node-b", new CountingActivities.RecordingChainActivities(recoveredRecorder),
                true);
        assertEquals(1, recoveringNode.recover());

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> i.status().isTerminal())
                        .orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals("seed-one,seed-two,seed-three",
                serializer.deserialize(instance.output(), String.class),
                "replayed branches must rejoin in the same order as a live run");

        // Branch partitioning is stable across processes: node B found branch 0
        // and 1's results at exactly the sequences node A wrote them to.
        assertEquals(0, recoveredRecorder.count("stepOne"), "branch 0 was durable — replay it");
        assertEquals(0, recoveredRecorder.count("stepTwo"), "branch 1 was durable — replay it");
        assertEquals(1, recoveredRecorder.count("stepThree"), "branch 2 must run exactly once");

        assertEquals(
                List.of("1:SIDE_EFFECT:$maestro:parallel",
                        "2001:ACTIVITY_COMPLETED:chain.stepOne",
                        "3001:ACTIVITY_COMPLETED:chain.stepTwo",
                        "4001:ACTIVITY_COMPLETED:chain.stepThree",
                        "5001:WORKFLOW_COMPLETED:null"),
                describe(instance.id()),
                "the fork event must not be re-appended on replay");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Chain activities where the third parallel branch never returns, leaving
     * the other two branches durably recorded.
     */
    private final class WedgingThirdBranch implements CountingActivities.ChainActivities {

        private final CountingActivities.Recorder recorder;

        /** @param recorder the recorder this node reports to */
        private WedgingThirdBranch(CountingActivities.Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public String stepOne(String input) {
            recorder.record("stepOne");
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            recorder.record("stepTwo");
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            recorder.record("stepThree");
            reachedThirdBranch.countDown();
            try {
                wedge.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return input + "-three";
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Builds one node with the parallel workflow registered.
     *
     * @param serviceName the node's service name
     * @param activities  the chain activity implementation for this node
     * @param withLock    whether this node gets a Postgres lock backend
     * @return the harness
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
        built.registerWorkflow(new TestWorkflows.ParallelWorkflow());
        return built;
    }

    /**
     * Renders a run's event log as {@code sequence:type:stepName} strings.
     *
     * @param handle the run
     * @return one descriptor per event, in sequence order
     */
    private static List<String> describe(io.b2mash.maestro.integration.support.WorkflowHandle handle) {
        return handle.events().stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }

    /**
     * Renders an instance's event log as {@code sequence:type:stepName} strings.
     *
     * @param instanceId the workflow instance
     * @return one descriptor per event, in sequence order
     */
    private List<String> describe(java.util.UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }
}
