package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec row C5, end to end on real PostgreSQL: two nodes execute the same
 * workflow at once, and the one that loses the {@code (workflow_instance_id,
 * sequence_number)} race stands down at its first collision.
 *
 * <h2>How the dual execution is produced</h2>
 * <p>Both harnesses share one store and have <b>no lock backend</b> — the
 * Issue 11 lost-lock window (a node frozen past its instance-lock TTL while a
 * peer adopts the workflow) made permanent, so nothing but the store's unique
 * index stands between the two runs. Node A starts the workflow and is held
 * inside {@code stepOne}; node B recovers the still-{@code RUNNING} instance
 * and runs it to completion; then node A's {@code stepOne} returns and its
 * append at sequence 1 collides with node B's.
 *
 * <h2>What the loser must do</h2>
 * <p>Accept the winner's record and stop: no adoption of the winner's value,
 * no further activity, nothing written, and a {@code STALE_RUN} stand-down
 * reported — the winner is alive and ahead, so anything the loser did next
 * would either collide again or repeat a side effect the winner also runs.
 */
@Tag("integration")
@DisplayName("A dual-execution loser stands down at its first append collision")
class EnginePostgresDualExecutionIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    private final CountingActivities.Recorder recorder = new CountingActivities.Recorder();
    private final RecordingObserver observerA = new RecordingObserver();
    private final RecordingObserver observerB = new RecordingObserver();
    private final CountDownLatch release = new CountDownLatch(1);

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void closeNodes() {
        // Never leave node A's attempt wedged, whatever the assertions did.
        release.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("with the lock lost, the loser stands down at its first append collision and runs no further activity")
    void lostLock_loserStandsDownAtFirstCollision() throws Exception {
        var entered = new CountDownLatch(1);
        var gated = new GatedChainActivities(new CountingActivities.RecordingChainActivities(recorder), entered, release);
        nodeA = newNode(observerA).registerActivities(ChainActivities.class, gated)
                .registerWorkflow(new TestWorkflows.ChainWorkflow());
        nodeB = newNode(observerB).registerActivities(ChainActivities.class, gated)
                .registerWorkflow(new TestWorkflows.ChainWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("dual");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "x");
        assertTrue(entered.await(30, TimeUnit.SECONDS), "node A must be inside stepOne");

        assertEquals(1, nodeB.recover(), "node B adopts the RUNNING instance — no lock to stop it");
        awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);          // B runs all three steps
        release.countDown();                                              // A's stepOne returns; its append at seq 1 collides
        await().atMost(BOUND).until(() -> !nodeA.executor().isRunning(workflowId));

        assertEquals(2, recorder.count("stepOne"), "both nodes executed stepOne — the double execution the lock normally prevents");
        assertEquals(1, recorder.count("stepTwo"), "the loser must not go on to stepTwo");
        assertEquals(1, recorder.count("stepThree"));
        assertEquals("x-one-two-three", handle.result(String.class));
        assertEquals(3, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
                "exactly one durable result per sequence");
        assertEquals(List.of(StandDownReason.STALE_RUN), observerA.standDowns().stream()
                .filter(s -> s.workflowId().equals(workflowId)).map(RecordingObserver.StandDownCall::reason).toList());
        assertTrue(observerB.standDowns().isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Builds one node of the {@code dual-node} service over the shared store,
     * with no lock backend.
     *
     * @param observer this node's own recorder
     * @return the unregistered harness
     */
    private MaestroEngineHarness newNode(RecordingObserver observer) {
        return MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("dual-node")
                .lock(null)
                .observer(observer)
                .build();
    }

    /**
     * Chain activities whose {@code stepOne} holds its <em>first</em> caller
     * (node A) until {@code release} opens; every later caller and every other
     * step delegates straight through.
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe: shared by both nodes' attempt threads.
     */
    private static final class GatedChainActivities implements ChainActivities {

        private final ChainActivities delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean firstCaller = new AtomicBoolean(true);

        GatedChainActivities(ChainActivities delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String stepOne(String input) {
            var result = delegate.stepOne(input);
            if (firstCaller.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }

        @Override
        public String stepTwo(String input) {
            return delegate.stepTwo(input);
        }

        @Override
        public String stepThree(String input) {
            return delegate.stepThree(input);
        }
    }
}
