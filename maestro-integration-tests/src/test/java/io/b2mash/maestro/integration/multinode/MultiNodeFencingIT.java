package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves lease fencing on real PostgreSQL (Issue 11, closed): a node that has
 * lost its instance lock and keeps running cannot persist workflow progress
 * once a peer has adopted the workflow — the store rejects its writes, it
 * stands down as {@code LEASE_FENCED}, and the adopting node's results are the
 * only ones in the log.
 *
 * <h2>The scenario</h2>
 * <ol>
 *   <li>Node A starts the workflow, persists step one, and is wedged inside
 *       step two. Its lock client then <em>dies</em> ({@link DyingLock}: no
 *       more renewals, no release) — the state a frozen or partitioned JVM is
 *       in while its workflow thread is still alive.</li>
 *   <li>Node B's recovery poller adopts the workflow once the TTL expires. The
 *       adoption bumps the row's {@code lease_epoch} from 0 to 1. Node B
 *       replays step one and is itself wedged inside step two.</li>
 *   <li>Node A's step two returns. Its result append carries lease epoch 0;
 *       the row is at 1; the {@code INSERT ... SELECT ... WHERE lease_epoch =
 *       0} inserts nothing and the store throws
 *       {@code FencedWriteException}. Node A stands down with exactly one
 *       {@code LEASE_FENCED} stand-down, runs no step three, and sequence 2
 *       is still empty — node B has not written it yet, which is what proves
 *       the rejection came from the fence rather than from the unique index.</li>
 *   <li>Node B's step two returns and the workflow completes on B. Sequences
 *       2–3 carry B's stamp, 1 carries A's, and the output is B's.</li>
 * </ol>
 *
 * <p>This is the "done when" of Issue 11: <em>a node that has lost its lock
 * cannot persist workflow progress, with a multi-node test proving the stale
 * node is fenced out.</em> It differs from {@code MultiNodeOwnerDeathIT}
 * (where the dead node never comes back) and from
 * {@code EnginePostgresDualExecutionIT} (no lock backend at all): here the
 * stale node holds a real, expired lock and resumes into the adopter's run.
 */
@Tag("integration")
@DisplayName("A node that lost its instance lock is fenced: its writes are rejected and the adopter's results stand")
class MultiNodeFencingIT extends PostgresIntegrationSupport {

    /** Short enough that expiry is fast, long enough to survive a slow CI renewal. */
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);
    private static final Duration BOUND = Duration.ofSeconds(30);

    private final CountDownLatch wedgeA = new CountDownLatch(1);
    private final CountDownLatch wedgeB = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwoA = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwoB = new CountDownLatch(1);
    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();
    private final RecordingObserver observerA = new RecordingObserver();
    private final RecordingObserver observerB = new RecordingObserver();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        wedgeA.countDown();
        wedgeB.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("the stale node's in-flight result is fenced (LEASE_FENCED, nothing written) and the adopter completes the workflow alone")
    void staleNodeIsFenced_adopterCompletes() throws Exception {
        var nodeALock = new DyingLock(new PostgresDistributedLock(newDataSource()));
        nodeA = node("node-a", new WedgingChainActivities(recorderA, "A", reachedStepTwoA, wedgeA), nodeALock, observerA);
        nodeB = node("node-b", new WedgingChainActivities(recorderB, "B", reachedStepTwoB, wedgeB),
                new PostgresDistributedLock(newDataSource()), observerB);

        // 1. Node A runs step one durably and wedges inside step two; then its lock client dies.
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("fence");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertTrue(reachedStepTwoA.await(15, TimeUnit.SECONDS), "node A must be inside step two");
        await().atMost(BOUND).until(() -> store.getEventBySequence(handle.instanceId(), 1).isPresent());
        assertEquals(0, store.getInstance(workflowId).orElseThrow().leaseEpoch(), "a fresh start is epoch 0");
        nodeALock.die();

        // 2. Node B adopts after the TTL: the epoch moves to 1 and B wedges inside step two.
        nodeB.startRecoveryPoller(Duration.ofMillis(200));
        assertTrue(reachedStepTwoB.await(30, TimeUnit.SECONDS), "node B must adopt and reach step two");
        assertEquals(1, store.getInstance(workflowId).orElseThrow().leaseEpoch(),
                "adoption bumps the lease epoch under the version CAS");
        assertEquals(0, recorderB.count("stepOne"), "B replays A's durable step one");
        assertTrue(store.getEventBySequence(handle.instanceId(), 2).isEmpty(), "nobody has written step two yet");

        // 3. Node A's step two returns into a world that has moved on.
        wedgeA.countDown();
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50))
                .until(() -> !nodeA.executor().isRunning(workflowId));

        assertEquals(List.of(StandDownReason.LEASE_FENCED), observerA.standDowns().stream()
                        .filter(s -> s.workflowId().equals(workflowId)).map(RecordingObserver.StandDownCall::reason).toList(),
                "the fence, not the unique index, rejected A's write");
        assertTrue(observerA.standDowns().getFirst().detail().startsWith("lease epoch 0 superseded"),
                "the stand-down names the superseded epoch: " + observerA.standDowns().getFirst().detail());
        assertTrue(store.getEventBySequence(handle.instanceId(), 2).isEmpty(),
                "A's step-two result never reached the log — and B has not written seq 2 yet, so nothing has");
        assertEquals(0, recorderA.count("stepThree"), "a fenced run executes nothing further");
        assertTrue(observerA.failed().isEmpty(), "a fence is never recorded as a workflow failure");
        assertEquals(WorkflowStatus.RUNNING, handle.status(), "A wrote nothing — the instance is B's to finish");

        // 4. Node B finishes alone.
        wedgeB.countDown();
        var instance = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed-one-A-two-B-three-B", serializer.deserialize(instance.output(), String.class),
                "step one is A's durable result; everything after the adoption is B's");
        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                store.getEvents(instance.id()).stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList(),
                "one event per sequence, written by exactly one node each");
        assertEquals("seed-one-A-two-B", serializer.deserialize(
                        store.getEventBySequence(instance.id(), 2).orElseThrow().payload(), String.class),
                "sequence 2 is B's result, not A's");
        assertEquals(1, instance.leaseEpoch(), "B completed under the epoch it adopted at");
        assertEquals(2, recorderA.count("stepTwo") + recorderB.count("stepTwo"),
                "step two executed on both nodes — the fence bounds persisted progress, not the activity "
                        + "already in flight at adoption; activities must stay idempotent");
        assertTrue(observerB.standDowns().isEmpty(), "the adopter never stands down");
        assertEquals(1, observerB.completed().size());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(String serviceName, CountingActivities.ChainActivities activities,
                                      DistributedLock lock, RecordingObserver observer) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(lock)
                .instanceLockTtl(SHORT_TTL)
                .observer(observer)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class, activities);
        harness.registerWorkflow(TestWorkflows.ChainWorkflow.class);
        return harness;
    }

    /**
     * A distributed lock that behaves like the lock client of a process that
     * has just been frozen or killed: renewals stop succeeding and nothing is
     * released, so its rows are freed only by TTL expiry — while the workflow
     * thread it was protecting keeps running.
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe — the death flag is an {@link AtomicBoolean} and the
     * delegate is itself thread-safe.
     */
    private static final class DyingLock implements DistributedLock {

        private final DistributedLock delegate;
        private final AtomicBoolean dead = new AtomicBoolean();

        private DyingLock(DistributedLock delegate) {
            this.delegate = delegate;
        }

        void die() {
            dead.set(true);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return dead.get() ? Optional.empty() : delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            if (!dead.get()) {
                delegate.release(handle);
            }
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return !dead.get() && delegate.renew(handle, ttl);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return !dead.get() && delegate.trySetLeader(electionKey, candidateId, ttl);
        }
    }

    /**
     * Chain activities that stamp every result with their node and park inside
     * step two until the node's gate opens — so the test controls exactly when
     * each node's in-flight result tries to land.
     */
    private static final class WedgingChainActivities implements CountingActivities.ChainActivities {

        private final CountingActivities.Recorder recorder;
        private final String suffix;
        private final CountDownLatch reachedStepTwo;
        private final CountDownLatch gate;

        private WedgingChainActivities(CountingActivities.Recorder recorder, String suffix,
                                       CountDownLatch reachedStepTwo, CountDownLatch gate) {
            this.recorder = recorder;
            this.suffix = suffix;
            this.reachedStepTwo = reachedStepTwo;
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
            reachedStepTwo.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
