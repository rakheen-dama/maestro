package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.exception.WorkflowNotQueryableException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows.TaggedChainWorkflow;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1, multi-node: a {@code @QueryMethod} is answered by the workflow object of
 * the run the queried node holds — never by an object another node (or another
 * run) built — and a node whose run is gone cannot answer at all.
 *
 * <p>Node A dies holding the instance lock while wedged in step two (the same
 * {@link DyingLock} simulation as {@link MultiNodeOwnerDeathIT}); node B adopts
 * the workflow after the TTL and is held inside its own step two until its
 * query has been asserted, so both nodes are provably live when queried. Each
 * node's factory stamps its own tag into the objects it builds, so the query
 * answer names the object that served it.
 */
@Tag("integration")
@DisplayName("After a re-adoption, queries answer from the adopting node's per-run object")
class MultiNodePerRunQueryIT extends PostgresIntegrationSupport {

    private static final String WORKFLOW_TYPE = "TaggedChainWorkflow";
    /** Short enough that expiry is fast, long enough to survive a slow CI renewal. */
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);

    /** Holds node A's zombie inside step two. */
    private final CountDownLatch wedge = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwo = new CountDownLatch(1);
    /** Holds node B inside step two until its query has been asserted. */
    private final CountDownLatch releaseNodeB = new CountDownLatch(1);
    private final CountDownLatch nodeBReachedStepTwo = new CountDownLatch(1);
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        wedge.countDown();
        releaseNodeB.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    void afterReadoptionQueriesAnswerFromNodeBsObjectAndNodeAsIsUnreachable() throws Exception {
        var workflowId = startAndAbandonOnNodeA();
        assertEquals("node-a", nodeA.executor().queryWorkflow(workflowId, "tag", null, String.class));

        nodeB = node("node-b", new GatedChainActivities(), new PostgresDistributedLock(newDataSource()),
                () -> new TaggedChainWorkflow("node-b"));
        nodeB.startRecoveryPoller(Duration.ofMillis(200));
        await().atMost(Duration.ofSeconds(30)).until(() -> nodeB.executor().isRunning(workflowId));
        assertTrue(nodeBReachedStepTwo.await(30, TimeUnit.SECONDS),
                "node B must be live inside step two when queried");
        assertEquals("node-b", nodeB.executor().queryWorkflow(workflowId, "tag", null, String.class),
                "the adopting node answers from the object its own factory built for this run");

        // Let node B finish first, then free A's zombie: with B's step-two
        // result durable, the zombie's late append is the duplicate and it is
        // the one that stands down — B's completion is never put at risk.
        releaseNodeB.countDown();
        awaitStatus(workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(30));

        wedge.countDown();
        nodeA.close();                                             // A's zombie exits; A's run is gone
        assertThrows(WorkflowNotQueryableException.class,
                () -> nodeA.executor().queryWorkflow(workflowId, "tag", null, String.class));
        assertEquals(1, recorderB.count("stepTwo"), "node B ran step two exactly once");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance(workflowId).orElseThrow().status(),
                "A's released zombie stood down — it did not overwrite B's completion");
    }

    // ── crash simulation ──────────────────────────────────────────────────

    /**
     * Starts the workflow on node A, wedges it inside step two, then kills
     * node A's ability to renew or release its instance lock.
     *
     * @return the abandoned workflow's business ID
     * @throws InterruptedException if the test thread is interrupted
     */
    private String startAndAbandonOnNodeA() throws InterruptedException {
        var nodeALock = new DyingLock(new PostgresDistributedLock(newDataSource()));
        nodeA = node("node-a", new WedgingChainActivities(), nodeALock,
                () -> new TaggedChainWorkflow("node-a"));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("per-run-query");
        var handle = nodeA.start(workflowId, TaggedChainWorkflow.class, "seed");

        assertTrue(reachedStepTwo.await(15, TimeUnit.SECONDS),
                "node A must reach step two before it dies");
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getEventBySequence(handle.instanceId(), 1).isPresent());

        nodeALock.die();
        return workflowId;
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(String serviceName,
                                      CountingActivities.ChainActivities activities,
                                      DistributedLock lock,
                                      Supplier<TaggedChainWorkflow> factory) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(lock)
                .instanceLockTtl(SHORT_TTL)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class, activities);
        harness.registerWorkflow(TaggedChainWorkflow.class, factory);
        harness.executor().registerQueries(WORKFLOW_TYPE, TaggedChainWorkflow.class);
        return harness;
    }

    /**
     * A distributed lock that can be made to behave like the lock client of a
     * killed process: renewals stop succeeding and nothing is released.
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

        /** Kills this client: no more renewals, no more releases. */
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

    /** Chain activities whose second step never returns until released — node A's zombie. */
    private final class WedgingChainActivities implements CountingActivities.ChainActivities {

        @Override
        public String stepOne(String input) {
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            reachedStepTwo.countDown();
            block(wedge);
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            return input + "-three";
        }
    }

    /** Node B's chain activities: step two holds until the test has queried node B. */
    private final class GatedChainActivities implements CountingActivities.ChainActivities {

        @Override
        public String stepOne(String input) {
            recorderB.record("stepOne");
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            recorderB.record("stepTwo");
            nodeBReachedStepTwo.countDown();
            block(releaseNodeB);
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            recorderB.record("stepThree");
            return input + "-three";
        }
    }

    private static void block(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
