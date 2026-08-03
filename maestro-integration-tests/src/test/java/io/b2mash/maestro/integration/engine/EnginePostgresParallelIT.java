package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("two branches parking at once do not collide on the instance status row")
    void twoParkingBranches_doNotRaceTheInstanceRow() {
        var barrierStore = new BranchParkBarrierStore(store);
        var workflow = new ParallelParkingWorkflow();
        harness = MaestroEngineHarness.builder(barrierStore, objectMapper)
                .serviceName("parking-branches-node")
                .lock(newLock())
                .instanceLockTtl(Duration.ofSeconds(30))
                .build();
        harness.registerWorkflow(workflow);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("parallel-park");
        var handle = harness.start(workflowId, ParallelParkingWorkflow.class, "seed");

        // Both branch threads are inside the barrier: each has read the same
        // instance version and neither has issued its CAS. Before the fix, the
        // loser's UPDATE ... WHERE version = ? matched zero rows and the
        // OptimisticLockException escaped parallel() into workflow code, where
        // executeWorkflow recorded the run FAILED and ran its compensations.
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(barrierStore::bothBranchesAtBarrier);

        harness.deliverSignal(workflowId, ParallelParkingWorkflow.SIGNAL_A, "a");
        harness.deliverSignal(workflowId, ParallelParkingWorkflow.SIGNAL_B, "b");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(30)),
                "a version conflict between two parking branches is not a workflow outcome");
        assertEquals("a,b", handle.result(String.class),
                "both parked branches must resume with their own signal payload");
        assertFalse(workflow.compensated.get(),
                "no compensation may run — nothing in this workflow failed");
        assertEquals(2, handle.events().stream()
                        .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED).count(),
                "both branches must genuinely park and consume their own signal");
        assertEquals(0, handle.events().stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED
                                || e.eventType() == EventType.COMPENSATION_STARTED).count(),
                "no failure or compensation may reach the durable log");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Forks two branches that both park on their own signal — the shape
     * {@code docs/cross-service.md} sells ("fan out to two services and await
     * both replies"), and the one that used to die on the instance-row CAS.
     *
     * <p>Two branches is the minimum that actually forks: {@code parallel()}
     * runs a single-task list inline on the calling thread.
     */
    @DurableWorkflow(name = "ParallelParkingWorkflow")
    public static class ParallelParkingWorkflow {

        /** Signal the first branch awaits. */
        public static final String SIGNAL_A = "goA";
        /** Signal the second branch awaits. */
        public static final String SIGNAL_B = "goB";

        /** Set if a compensation runs — it must not, since nothing here fails. */
        final AtomicBoolean compensated = new AtomicBoolean();

        /**
         * @param input the seed value, unused
         * @return both branches' signal payloads, joined in branch order
         */
        @WorkflowMethod
        public String run(String input) {
            var ctx = WorkflowContext.current();
            ctx.addCompensation("reverseCharge", () -> compensated.set(true));
            List<Callable<String>> branches = List.of(
                    () -> WorkflowContext.current()
                            .awaitSignal(SIGNAL_A, String.class, Duration.ofSeconds(60)),
                    () -> WorkflowContext.current()
                            .awaitSignal(SIGNAL_B, String.class, Duration.ofSeconds(60)));
            return String.join(",", ctx.parallel(branches));
        }
    }

    /**
     * A {@link WorkflowStore} decorator that forces the branch-parking
     * interleaving instead of hoping for it: the first {@code updateInstance}
     * on each of the two branch threads rendezvouses <em>before</em> the CAS
     * reaches Postgres, so both branches have already read the same version and
     * neither may write until both are here.
     *
     * <p>The barrier must sit between the read and the write. A read-side
     * barrier still lets the second read land after the first write, and the
     * test then passes against broken code.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use — the barrier is a {@link CyclicBarrier}, the
     * one-shot-per-thread flag is a {@link ThreadLocal}, and everything else
     * delegates straight through.
     */
    private static final class BranchParkBarrierStore implements WorkflowStore {

        private final WorkflowStore delegate;
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final ThreadLocal<Boolean> used = ThreadLocal.withInitial(() -> false);
        private final CountDownLatch atBarrier = new CountDownLatch(2);

        BranchParkBarrierStore(WorkflowStore delegate) {
            this.delegate = delegate;
        }

        /** @return whether both branch threads have reached the pre-CAS barrier */
        boolean bothBranchesAtBarrier() {
            return atBarrier.getCount() == 0;
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            if (Thread.currentThread().getName().contains("-branch-") && !used.get()) {
                used.set(true);
                atBarrier.countDown();
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Best effort — the assertions, not the barrier, are the oracle.
                }
            }
            delegate.updateInstance(instance);
        }

        // ── straight delegation ────────────────────────────────────────

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            return delegate.createInstance(instance);
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return delegate.getInstance(workflowId);
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return delegate.getRecoverableInstances();
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            delegate.appendEvent(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return delegate.getEventBySequence(instanceId, sequenceNumber);
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return delegate.getEvents(instanceId);
        }

        @Override
        public int deleteFailureEvents(UUID instanceId) {
            return delegate.deleteFailureEvents(instanceId);
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            delegate.saveSignal(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return delegate.getUnconsumedSignals(workflowId, signalName);
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return delegate.markSignalConsumed(signalId);
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            delegate.adoptOrphanedSignals(workflowId, instanceId);
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            delegate.saveTimer(timer);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return delegate.getDueTimers(now, batchSize);
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return delegate.findTimer(workflowInstanceId, timerId);
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return delegate.markTimerFired(timerId);
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            return delegate.markTimerCancelled(timerId);
        }
    }

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
