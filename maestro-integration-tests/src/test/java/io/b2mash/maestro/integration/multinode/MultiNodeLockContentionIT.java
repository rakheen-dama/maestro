package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the per-instance distributed lock keeps two live nodes off the same
 * workflow — the guarantee the production topology rests on and which no
 * single-node suite can exercise.
 *
 * <h2>What "two nodes" means here</h2>
 * <p>Two {@link MaestroEngineHarness} instances with distinct service names over
 * the <em>same</em> Postgres store, each with its own
 * {@link PostgresDistributedLock} on its own {@code DataSource} — so the lock
 * tokens are genuinely different and contention is resolved in the database,
 * not by an in-JVM shortcut.
 *
 * <h2>Why counters, not return values</h2>
 * <p>Both nodes converge on the same output whether or not a duplicate ran:
 * the loser of an event-sequence race reads the winner's stored result back.
 * Only per-node activity invocation counters can distinguish "ran once" from
 * "ran twice and one copy was discarded", so every assertion here is made on
 * counters that belong to exactly one node.
 */
@Tag("integration")
@DisplayName("The per-instance lock stops a second node from running a workflow that is already live")
class MultiNodeLockContentionIT extends PostgresIntegrationSupport {

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final String LOCK_KEY_PREFIX = "maestro:lock:workflow:";

    /** Held until the test releases node A's in-flight step. */
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch reachedGatedStep = new CountDownLatch(1);

    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        gate.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("a second node's recovery pass does not launch a workflow the first node is running")
    void secondNodeRecovery_whileFirstNodeIsRunning_launchesNothing() throws Exception {
        nodeA = node("node-a", new GatedChainActivities(recorderA));
        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(recorderB));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("contention");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertTrue(reachedGatedStep.await(15, TimeUnit.SECONDS),
                "node A must be inside step two before node B tries to adopt");

        assertEquals(WorkflowStatus.RUNNING, handle.status(),
                "the workflow must still look adoptable to node B's recovery query");
        assertEquals(0, nodeB.recover(),
                "node B must observe HELD_ELSEWHERE and skip the workflow");
        assertFalse(nodeB.executor().isRunning(workflowId),
                "node B must not have launched a second copy");

        // A single sample could catch node B before it even tried; hold the
        // condition across a window instead.
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> recorderB.invocations().isEmpty());

        gate.countDown();
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals("seed-one-two-three", handle.result(String.class));

        // The decisive assertion: the body ran exactly once across BOTH nodes.
        assertEquals(1, recorderA.count("stepOne"));
        assertEquals(1, recorderA.count("stepTwo"));
        assertEquals(1, recorderA.count("stepThree"));
        assertEquals(List.of(), recorderB.invocations(),
                "node B must not have executed a single activity");
        assertEquals(4, handle.events().size(),
                "one event per step plus WORKFLOW_COMPLETED — no duplicates");
    }

    @Test
    @DisplayName("the instance lock is held for the whole run and released when the workflow completes")
    void instanceLock_isHeldForTheWholeRun_andReleasedOnCompletion() throws Exception {
        nodeA = node("node-a", new GatedChainActivities(recorderA));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lock-lifetime");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertTrue(reachedGatedStep.await(15, TimeUnit.SECONDS));

        // A third, uninvolved lock client is the honest observer of the key.
        var probe = new PostgresDistributedLock(newDataSource());
        var key = LOCK_KEY_PREFIX + workflowId;
        assertTrue(probe.tryAcquire(key, Duration.ofSeconds(1)).isEmpty(),
                "the key must be unavailable while node A runs the workflow");

        // Node A renews at TTL/3, so the key stays unavailable well past one TTL.
        await().during(LOCK_TTL.plusSeconds(1))
                .atMost(LOCK_TTL.plusSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> probe.tryAcquire(key, Duration.ofSeconds(1)).isEmpty());

        gate.countDown();
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        var acquired = new AtomicReference<LockHandle>();
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    var handleOrEmpty = probe.tryAcquire(key, Duration.ofSeconds(5));
                    handleOrEmpty.ifPresent(acquired::set);
                    return handleOrEmpty.isPresent();
                });
        probe.release(acquired.get());
    }

    @Test
    @DisplayName("a second node starting the same workflow ID is rejected by the store, not silently duplicated")
    void secondNodeStart_withTheSameWorkflowId_isRejected() throws Exception {
        nodeA = node("node-a", new GatedChainActivities(recorderA));
        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(recorderB));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("dup-start");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertTrue(reachedGatedStep.await(15, TimeUnit.SECONDS));

        assertThrows(WorkflowAlreadyExistsException.class,
                () -> nodeB.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed"),
                "the workflow_id uniqueness constraint is the outer guard");
        assertFalse(nodeB.executor().isRunning(workflowId));
        assertEquals(List.of(), recorderB.invocations());

        gate.countDown();
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals(1, recorderA.count("stepOne"));
    }

    @Test
    @DisplayName("when both nodes recover an ownerless workflow at once, exactly one of them adopts it")
    void concurrentRecoveryOnBothNodes_adoptsTheWorkflowExactlyOnce() throws Exception {
        nodeA = node("node-a", new CountingActivities.RecordingChainActivities(recorderA));
        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(recorderB));

        // An instance whose owner died before doing any work: RUNNING, no
        // events, no lock row. Both nodes see it as adoptable in the same
        // recovery cycle — the adoption race the lock exists to arbitrate.
        var workflowId = seedOwnerlessRunningInstance();

        var barrier = new CyclicBarrier(2);
        var launched = new AtomicInteger();
        var raceA = recoverOnBarrier(nodeA, barrier, launched);
        var raceB = recoverOnBarrier(nodeB, barrier, launched);
        raceA.join();
        raceB.join();

        assertEquals(1, launched.get(),
                "exactly one node may launch the workflow — the other must see HELD_ELSEWHERE");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.COMPLETED)
                        .orElse(false));

        for (var step : List.of("stepOne", "stepTwo", "stepThree")) {
            assertEquals(1, recorderA.count(step) + recorderB.count(step),
                    step + " must have run exactly once across both nodes");
        }
        assertEquals(4, store.getEvents(store.getInstance(workflowId).orElseThrow().id()).size());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Builds one node: its own service name, its own Postgres lock client on its
     * own DataSource, over the shared store.
     *
     * @param serviceName the node's service name
     * @param activities  this node's activity implementation
     * @return a harness with the chain workflow registered
     */
    private MaestroEngineHarness node(String serviceName,
                                      CountingActivities.ChainActivities activities) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(newDataSource()))
                .instanceLockTtl(LOCK_TTL)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class, activities);
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return harness;
    }

    /**
     * Inserts a RUNNING instance with no events and no owner, modelling a node
     * that died between {@code createInstance} and its first activity.
     *
     * @return the seeded workflow's business ID
     */
    private String seedOwnerlessRunningInstance() {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("ownerless");
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

    /**
     * Starts a platform thread that calls {@code recover()} the instant both
     * threads reach the barrier, adding the launch count to a shared total.
     *
     * @param node     the node to recover on
     * @param barrier  the rendezvous point
     * @param launched accumulator of launched workflows
     * @return the started thread
     */
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
     * Chain activities whose second step blocks on the test's gate — node A is
     * demonstrably still inside the workflow while node B tries to adopt it.
     */
    private final class GatedChainActivities implements CountingActivities.ChainActivities {

        private final CountingActivities.Recorder recorder;

        private GatedChainActivities(CountingActivities.Recorder recorder) {
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
            reachedGatedStep.countDown();
            try {
                if (!gate.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate was never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            recorder.record("stepThree");
            return input + "-three";
        }
    }
}
