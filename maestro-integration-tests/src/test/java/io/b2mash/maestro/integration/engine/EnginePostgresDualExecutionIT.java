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
 * Spec row C5 / Issue 11, end to end on real PostgreSQL: two nodes execute the
 * same workflow at once, and the one whose lease the adoption superseded is
 * fenced at its first write.
 *
 * <h2>How the dual execution is produced</h2>
 * <p>Both harnesses share one store and have <b>no lock backend</b> — the
 * lost-lock window (a node frozen past its instance-lock TTL while a peer
 * adopts the workflow) made permanent, so nothing but the store stands between
 * the two runs. Node A starts the workflow (lease epoch 0) and is held inside
 * {@code stepOne}; node B recovers the still-{@code RUNNING} instance — its
 * adoption bumps the row to epoch 1 — persists {@code stepOne} and is held
 * inside {@code stepTwo}; then node A's {@code stepOne} returns and its append
 * at sequence 1, carrying epoch 0, is <em>fenced</em>: the store writes
 * nothing and node A stands down as {@code LEASE_FENCED}. (Before lease
 * fencing the same append reached the unique index and stood down as
 * {@code STALE_RUN}; the index remains as depth, but the fence fires first.)
 * Only once node A has finished is node B released to complete the workflow.
 *
 * <h2>Why node B is held in {@code stepTwo}</h2>
 * <p>It is what makes the {@code stepTwo} count discriminate. Under the
 * pre-C5 behaviour the loser <em>adopted</em> the winner's {@code stepOne}
 * value and carried on: had node B already completed, node A's {@code stepTwo}
 * would merely have replayed node B's memoized result and the count would read
 * 1 either way. With node B's {@code stepTwo} not yet persisted, an adopting
 * loser runs {@code stepTwo} live — count 2 — while a loser that stands down
 * at its collision never reaches it — count 1.
 *
 * <h2>What the loser must do</h2>
 * <p>Accept the winner's record and stop: no adoption of the winner's value,
 * no further activity, nothing written, and a {@code LEASE_FENCED} stand-down
 * reported — the winner is alive and ahead, so anything the loser did next
 * would either be fenced again or repeat a side effect the winner also runs.
 */
@Tag("integration")
@DisplayName("A dual-execution loser is fenced at its first write and stands down")
class EnginePostgresDualExecutionIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    private final CountingActivities.Recorder recorder = new CountingActivities.Recorder();
    private final RecordingObserver observerA = new RecordingObserver();
    private final RecordingObserver observerB = new RecordingObserver();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch releaseB = new CountDownLatch(1);

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void closeNodes() {
        // Never leave either node's attempt wedged, whatever the assertions did.
        release.countDown();
        releaseB.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("with the lock lost, the loser is fenced at its first write (LEASE_FENCED) and runs no further activity")
    void lostLock_loserIsFencedAtFirstWrite() throws Exception {
        var enteredA = new CountDownLatch(1);
        var enteredB = new CountDownLatch(1);
        var gated = new GatedChainActivities(new CountingActivities.RecordingChainActivities(recorder),
                enteredA, release, enteredB, releaseB);
        nodeA = newNode(observerA).registerActivities(ChainActivities.class, gated)
                .registerWorkflow(TestWorkflows.LongTimeoutChainWorkflow.class);
        nodeB = newNode(observerB).registerActivities(ChainActivities.class, gated)
                .registerWorkflow(TestWorkflows.LongTimeoutChainWorkflow.class);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("dual");
        var handle = nodeA.start(workflowId, TestWorkflows.LongTimeoutChainWorkflow.class, "x");
        assertTrue(enteredA.await(30, TimeUnit.SECONDS), "node A must be inside stepOne");

        assertEquals(1, nodeB.recover(), "node B adopts the RUNNING instance — no lock to stop it");
        assertTrue(enteredB.await(30, TimeUnit.SECONDS),
                "node B must have persisted stepOne and be inside stepTwo");
        release.countDown();                                              // A's stepOne returns; its append at seq 1 collides
        await().atMost(BOUND).until(() -> !nodeA.executor().isRunning(workflowId));
        releaseB.countDown();                                             // only now may B persist stepTwo and finish
        awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);

        assertEquals(2, recorder.count("stepOne"), "both nodes executed stepOne — the double execution the lock normally prevents");
        assertEquals(1, recorder.count("stepTwo"),
                "the loser must not go on to stepTwo — with B's stepTwo still unpersisted, a loser that "
                        + "adopted B's stepOne value would have run it live (count 2)");
        assertEquals(1, recorder.count("stepThree"));
        assertEquals("x-one-two-three", handle.result(String.class));
        assertEquals(3, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
                "exactly one durable result per sequence");
        assertEquals(List.of(StandDownReason.LEASE_FENCED), observerA.standDowns().stream()
                        .filter(s -> s.workflowId().equals(workflowId)).map(RecordingObserver.StandDownCall::reason).toList(),
                "node B's adoption moved the lease epoch to 1; node A's epoch-0 append is fenced before the "
                        + "unique index is ever consulted");
        assertTrue(observerA.failed().isEmpty(), "a fenced run is never a workflow failure");
        assertTrue(observerB.standDowns().isEmpty());
        assertEquals(1, handle.instance().leaseEpoch(), "one adoption, one epoch bump");
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
     * (node A) until {@code releaseOne} opens, and whose {@code stepTwo} holds
     * its first caller (node B — the stood-down node A never reaches it) until
     * {@code releaseTwo} opens; every later caller and every other step
     * delegates straight through. The gates are held for less than the
     * fixture workflow's {@code PT10M} attempt timeout.
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe: shared by both nodes' attempt threads.
     */
    private static final class GatedChainActivities implements ChainActivities {

        private final ChainActivities delegate;
        private final CountDownLatch enteredOne;
        private final CountDownLatch releaseOne;
        private final CountDownLatch enteredTwo;
        private final CountDownLatch releaseTwo;
        private final AtomicBoolean firstStepOneCaller = new AtomicBoolean(true);
        private final AtomicBoolean firstStepTwoCaller = new AtomicBoolean(true);

        GatedChainActivities(ChainActivities delegate, CountDownLatch enteredOne, CountDownLatch releaseOne,
                             CountDownLatch enteredTwo, CountDownLatch releaseTwo) {
            this.delegate = delegate;
            this.enteredOne = enteredOne;
            this.releaseOne = releaseOne;
            this.enteredTwo = enteredTwo;
            this.releaseTwo = releaseTwo;
        }

        @Override
        public String stepOne(String input) {
            var result = delegate.stepOne(input);
            if (firstStepOneCaller.compareAndSet(true, false)) {
                hold(enteredOne, releaseOne);
            }
            return result;
        }

        @Override
        public String stepTwo(String input) {
            var result = delegate.stepTwo(input);
            if (firstStepTwoCaller.compareAndSet(true, false)) {
                hold(enteredTwo, releaseTwo);
            }
            return result;
        }

        private static void hold(CountDownLatch entered, CountDownLatch release) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String stepThree(String input) {
            return delegate.stepThree(input);
        }
    }
}
