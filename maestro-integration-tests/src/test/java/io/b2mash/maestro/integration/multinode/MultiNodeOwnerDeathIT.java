package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
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

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the lock handoff that makes a multi-node deployment survive a node
 * dying: node A holds the instance lock, stops renewing it without ever
 * releasing it, and node B adopts the workflow only once the TTL has expired.
 *
 * <h2>How death is simulated</h2>
 * <p>A dead JVM does two things at once: it stops renewing its instance lock,
 * and it never releases it. {@code shutdown()} models neither — it is a
 * <em>graceful</em> stop, and it would also block joining node A's in-flight
 * thread. So node A is given a {@link DyingLock}: a real
 * {@link PostgresDistributedLock} behind a switch that, once thrown, makes
 * renewals fail and releases no-ops. The lock row stays in Postgres with the
 * expiry stamped at the last successful renewal — exactly the state a
 * {@code kill -9} leaves behind — and TTL expiry is the only thing that can
 * free it.
 *
 * <p>This is the distinguishing coverage over {@code EnginePostgresRecoveryIT},
 * which gives its crashed node no lock backend at all and therefore never
 * exercises the expiry handoff.
 *
 * <p>Node A's workflow thread stays wedged inside step two until the
 * assertions have run — the same idiom as the recovery suite. Each node has
 * its own activity implementation and recorder, so "node B did not re-execute
 * the completed step" is asserted on node B's counters alone.
 */
@Tag("integration")
@DisplayName("A node that dies holding the instance lock hands its workflow over when the TTL expires")
class MultiNodeOwnerDeathIT extends PostgresIntegrationSupport {

    private static final String LOCK_KEY_PREFIX = "maestro:lock:workflow:";
    /** Short enough that expiry is fast, long enough to survive a slow CI renewal. */
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);
    /** Long enough that expiry cannot happen inside a test's observation window. */
    private static final Duration LONG_TTL = Duration.ofSeconds(60);

    private final CountDownLatch wedge = new CountDownLatch(1);
    private final CountDownLatch reachedStepTwo = new CountDownLatch(1);
    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();

    private DyingLock nodeALock;
    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        // Only after the assertions: node A's zombie may now exit so its
        // shutdown does not block on the wedged thread.
        wedge.countDown();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("node B adopts the abandoned workflow after the TTL and resumes at the first uncompleted step")
    void ownerDies_afterTtlExpiry_nodeBAdoptsAndResumesWithoutReExecuting() throws Exception {
        var workflowId = startAndAbandonOnNodeA(SHORT_TTL);

        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(recorderB),
                new PostgresDistributedLock(newDataSource()), SHORT_TTL);
        nodeB.startRecoveryPoller(Duration.ofMillis(200));

        // The log assertion below expects 4:WORKFLOW_COMPLETED, appended one
        // write after the status turns COMPLETED. See TerminalWait.
        var instance = awaitStatus(workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(30));
        assertEquals("seed-one-two-three",
                serializer.deserialize(instance.output(), String.class));

        assertEquals(0, recorderB.count("stepOne"),
                "step one was already durable — node B must replay it, not re-run it");
        assertEquals(1, recorderB.count("stepTwo"),
                "step two never completed on node A — node B must run it exactly once");
        assertEquals(1, recorderB.count("stepThree"));
        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                store.getEvents(instance.id()).stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList(),
                "the event log is continuous across the handoff — one event per sequence");
    }

    @Test
    @DisplayName("no node adopts the workflow while the dead owner's lock has not yet expired")
    void ownerDies_beforeTtlExpiry_noNodeAdopts() throws Exception {
        // A 60s TTL means the lock outlives this test — the only thing that
        // could let node B in is a missing lock check, not the clock.
        var workflowId = startAndAbandonOnNodeA(LONG_TTL);

        nodeB = node("node-b", new CountingActivities.RecordingChainActivities(recorderB),
                new PostgresDistributedLock(newDataSource()), SHORT_TTL);
        assertEquals(0, nodeB.recover(),
                "the dead owner's lock is still live — node B must see HELD_ELSEWHERE");

        nodeB.startRecoveryPoller(Duration.ofMillis(200));
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recorderB.invocations().isEmpty()
                        && store.getInstance(workflowId).orElseThrow().status()
                        == WorkflowStatus.RUNNING);
        assertEquals(1, store.getEvents(store.getInstance(workflowId).orElseThrow().id()).size(),
                "only node A's step-one event may exist — no adoption wrote anything");
    }

    @Test
    @DisplayName("the dead owner's lock row is left behind un-released and is freed only by TTL expiry")
    void ownerDies_leavesTheLockRowBehind_andOnlyTtlExpiryFreesIt() throws Exception {
        var workflowId = startAndAbandonOnNodeA(SHORT_TTL);
        var key = LOCK_KEY_PREFIX + workflowId;

        assertEquals(1, lockRowCount(key),
                "a dead node never releases — the row must still be there");

        // Nobody else is running: the only way the key becomes available is the
        // TTL lapsing, which is precisely the production handoff mechanism.
        var probe = new PostgresDistributedLock(newDataSource());
        var acquired = new AtomicReference<LockHandle>();
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    var handleOrEmpty = probe.tryAcquire(key, Duration.ofSeconds(5));
                    handleOrEmpty.ifPresent(acquired::set);
                    return handleOrEmpty.isPresent();
                });
        probe.release(acquired.get());
    }

    // ── crash simulation ──────────────────────────────────────────────────

    /**
     * Starts a workflow on node A, wedges it inside step two, then kills node
     * A's ability to renew or release its instance lock.
     *
     * @param ttl the instance-lock TTL node A runs with
     * @return the abandoned workflow's business ID
     * @throws InterruptedException if the test thread is interrupted
     */
    private String startAndAbandonOnNodeA(Duration ttl) throws InterruptedException, SQLException {
        nodeALock = new DyingLock(new PostgresDistributedLock(newDataSource()));
        nodeA = node("node-a", new WedgingChainActivities(), nodeALock, ttl);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("owner-death");
        var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");

        assertTrue(reachedStepTwo.await(15, TimeUnit.SECONDS),
                "node A must reach step two before it dies");
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getEventBySequence(handle.instanceId(), 1).isPresent());

        assertEquals(EventType.ACTIVITY_COMPLETED,
                store.getEventBySequence(handle.instanceId(), 1).orElseThrow().eventType());
        assertTrue(store.getEventBySequence(handle.instanceId(), 2).isEmpty(),
                "step two must have produced nothing before the death");
        assertEquals(WorkflowStatus.RUNNING, handle.status());
        assertEquals(1, lockRowCount(LOCK_KEY_PREFIX + workflowId),
                "node A must actually hold the instance lock before it dies");

        nodeALock.die();
        return workflowId;
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(String serviceName,
                                      CountingActivities.ChainActivities activities,
                                      DistributedLock lock,
                                      Duration ttl) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(lock)
                .instanceLockTtl(ttl)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class, activities);
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return harness;
    }

    /**
     * Counts rows for a lock key in the backing table.
     *
     * @param key the lock key
     * @return the number of rows, expired or not
     * @throws SQLException if the query fails
     */
    private int lockRowCount(String key) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM maestro_distributed_lock WHERE lock_key = ?")) {
            stmt.setString(1, key);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * A distributed lock that can be made to behave like the lock client of a
     * process that has just been killed: renewals stop succeeding and nothing
     * is ever released, so every row it owns can only be freed by TTL expiry.
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

    /** Chain activities whose second step never returns — node A's zombie thread. */
    private final class WedgingChainActivities implements CountingActivities.ChainActivities {

        @Override
        public String stepOne(String input) {
            recorderA.record("stepOne");
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            recorderA.record("stepTwo");
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
            recorderA.record("stepThree");
            return input + "-three";
        }
    }
}
