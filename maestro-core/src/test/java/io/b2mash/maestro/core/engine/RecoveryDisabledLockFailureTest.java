package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.exception.LockAcquisitionException;
import io.b2mash.maestro.core.exception.WorkflowNotLaunchedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node that runs no recovery poller ({@code maestro.testing.recovery.enabled=false}):
 * nothing on it would later run a workflow a lock failure left behind, so a lock
 * failure must not leave one (PR #72 review, Group D):
 *
 * <ul>
 *   <li>a start whose lock is lost after its row was written, before its run
 *       registered, is relaunched once; if that also fails, the caller learns
 *       that the row was persisted and not launched (4622153273);</li>
 *   <li>admin Retry takes the instance lock before it reopens the row, so a lock
 *       failure mutates nothing and the command stays retryable (4622153285);</li>
 *   <li>the one-shot startup pass does not count as finished while it skipped a
 *       row because the lock backend failed; it retries after its interval
 *       (4622153294).</li>
 * </ul>
 */
class RecoveryDisabledLockFailureTest {

    private static final String ID = "wf-1";
    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:" + ID;

    private final CountingStore store = new CountingStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final FlakyLock lock = new FlakyLock();
    private final LostLockObserver observer = new LostLockObserver();
    private final BlockingWork work = new BlockingWork();
    private final WorkflowExecutor node = WorkflowExecutor.builder(store, serializer, SERVICE)
            .distributedLock(lock)
            .observer(observer)
            .lifecycleEventsEnabled(false)
            .instanceLockTtl(Duration.ofMillis(300)) // renewed every 100 ms: a forced loss is noticed at once
            .shutdownTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        node.shutdown();
    }

    // ── 4622153273 ───────────────────────────────────────────────────

    @Test
    void aStartWhoseLockIsLostBeforeItRegistersIsRelaunchedOnceAndRuns() {
        work.releaseAll();
        loseTheLockOnFirstAdmission(false);

        node.startWorkflow(ID, "WorkWorkflow", "orders", null, workflow(), workMethod());

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)), "the relaunch ran the workflow");
        assertEquals(1, work.entries(), "the activity ran once");
    }

    @Test
    void aStartWhoseLockIsLostAndWhoseRelaunchFailsReportsThePersistedRowNotARetry() {
        loseTheLockOnFirstAdmission(true);

        var e = assertThrows(WorkflowNotLaunchedException.class,
                () -> node.startWorkflow(ID, "WorkWorkflow", "orders", null, workflow(), workMethod()));

        assertEquals(ID, e.workflowId());
        assertTrue(e.getMessage().contains("was persisted"), e.getMessage());
        assertTrue(e.getMessage().contains("not launched"), e.getMessage());
        assertTrue(e.getMessage().contains("recovery"), e.getMessage());
        assertFalse(e.getMessage().contains("retry the start"), e.getMessage());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance(ID).orElseThrow().status(), "the row stays");
        assertEquals(0, work.entries());
    }

    // ── 4622153285 ───────────────────────────────────────────────────

    @Test
    void aRetryWhoseLockBackendFailsMutatesNothingAndStaysRetryable() throws Exception {
        var fail = new AtomicBoolean(true);
        var registration = failedBoom(fail);
        var failed = store.getInstance(ID).orElseThrow();
        var eventsBefore = store.getEvents(failed.id()).size();

        lock.failAcquire = true;
        var askedBefore = lock.failedAcquires.get();
        assertThrows(LockAcquisitionException.class, () -> node.retryWorkflow(ID, registration),
                "a lock failure is transient: thrown, so the transport redelivers the command");
        assertEquals(askedBefore + 1, lock.failedAcquires.get(),
                "the refusal came from the failing backend, not from a lock still held here");

        var after = store.getInstance(ID).orElseThrow();
        assertEquals(WorkflowStatus.FAILED, after.status(), "the row was not reopened");
        assertEquals(failed.version(), after.version());
        assertEquals(eventsBefore, store.getEvents(failed.id()).size(), "no failure memo was deleted");

        lock.failAcquire = false;
        fail.set(false);
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, node.retryWorkflow(ID, registration),
                "the redelivered command retries once the lock is back");
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    }

    @Test
    void aRetryWhoseLockIsLostAfterTheReopenIsRelaunchedOnceAndRuns() throws Exception {
        var fail = new AtomicBoolean(true);
        var registration = failedBoom(fail);
        fail.set(false);
        loseTheLockOnFirstAdmission(false);

        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, node.retryWorkflow(ID, registration),
                "the relaunch ran the reopened row");
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    }

    @Test
    void aRetryWhoseLockIsLostAfterTheReopenAndWhoseRelaunchFailsReportsTheReopenedRow() throws Exception {
        var fail = new AtomicBoolean(true);
        var registration = failedBoom(fail);
        loseTheLockOnFirstAdmission(true);

        var e = assertThrows(WorkflowNotLaunchedException.class, () -> node.retryWorkflow(ID, registration));

        assertEquals(ID, e.workflowId());
        assertTrue(e.getMessage().contains("was reopened"), e.getMessage());
        assertTrue(e.getMessage().contains("not launched"), e.getMessage());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance(ID).orElseThrow().status(),
                "the reopen committed; the row waits for a node with recovery");
    }

    // ── PR #72 review, round 3 ───────────────────────────────────────

    @Test
    void aRetryLaunchesTheReopenedRowEvenWhileTheFailedRunThatLostItsLockIsStillRegistered() throws Exception {
        // 4623896769: the failed run is held in workflowFailed (still
        // registered) and its lock is lost; Retry takes a new lock and reopens.
        var fail = new AtomicBoolean(true);
        var registration = new WorkflowRegistration("BoomWorkflow", "default",
                () -> new BoomWorkflow(boomProxy(fail)), boomMethod());
        var failedReported = new java.util.concurrent.CountDownLatch(1);
        var releaseFailedRun = new java.util.concurrent.CountDownLatch(1);
        observer.onWorkflowFailed = () -> {
            failedReported.countDown();
            try {
                releaseFailedRun.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            node.startWorkflow(ID, "BoomWorkflow", "default", null, registration.workflowFactory(), boomMethod());
            assertTrue(failedReported.await(5, java.util.concurrent.TimeUnit.SECONDS), "the run failed");
            assertTrue(node.isRunning(ID), "the failed run is still registered");
            lock.forceRelease(KEY);
            assertTrue(valueWithin(() -> observer.lost.contains(ID), true, Duration.ofSeconds(5)),
                    "the failed run's lock was reported lost");
            fail.set(false);

            assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, node.retryWorkflow(ID, registration),
                    "the stale registration of the failed run must not stop the launch");
        } finally {
            releaseFailedRun.countDown();
        }
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)), "the retried run ran");
    }

    @Test
    void aStartWhoseRelaunchFindsThePeerAlreadyFinishedItConvergesInsteadOfThrowing() {
        // 4623896766: the lock is lost before registration, and by the time of
        // the relaunch another node has run the row to its end.
        var first = new AtomicBoolean(true);
        node.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                lock.forceRelease(KEY);
                assertTrue(valueWithin(() -> observer.lost.contains(ID), true, Duration.ofSeconds(5)));
                finishElsewhere();
            }
        };

        var id = node.startWorkflow(ID, "WorkWorkflow", "orders", null, workflow(), workMethod());

        assertEquals(store.getInstance(ID).orElseThrow().id(), id, "the start returns normally");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance(ID).orElseThrow().status());
        assertEquals(0, work.entries(), "nothing ran here");
    }

    @Test
    void aRetryWhoseRelaunchFindsThePeerAlreadyFinishedItConvergesInsteadOfThrowing() throws Exception {
        var fail = new AtomicBoolean(true);
        var registration = failedBoom(fail);
        var first = new AtomicBoolean(true);
        node.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                lock.forceRelease(KEY);
                assertTrue(valueWithin(() -> observer.lost.contains(ID), true, Duration.ofSeconds(5)));
                finishElsewhere();
            }
        };

        assertEquals(WorkflowExecutor.RetryOutcome.LOCK_HELD_ELSEWHERE, node.retryWorkflow(ID, registration),
                "a peer ran the reopened row: the ordinary not-launched-here outcome, not an error");
    }

    @Test
    void aRetryReopenConflictSurvivesAFailingLockRelease() throws Exception {
        // 4623896773: the release in Retry's cleanup must not replace the
        // reopen's own failure.
        var fail = new AtomicBoolean(true);
        var registration = failedBoom(fail);
        var conflict = new io.b2mash.maestro.core.exception.OptimisticLockException(ID, 0, 1);
        store.reopenFailure = conflict;
        lock.releaseFailure = new InternalError("release failed (test)");

        var thrown = assertThrows(io.b2mash.maestro.core.exception.OptimisticLockException.class,
                () -> node.retryWorkflow(ID, registration));
        assertSame(conflict, thrown, "the reopen's conflict reaches the caller");
        assertEquals(1, thrown.getSuppressed().length, "the release failure is attached");
        assertSame(lock.releaseFailure, thrown.getSuppressed()[0]);
    }

    // ── 4622153294 ───────────────────────────────────────────────────

    @Test
    void theOneShotPassRetriesARowItSkippedBecauseTheLockBackendFailed() throws Exception {
        work.releaseAll();
        seedRecoverable(store, ID, "orders");
        lock.failAcquire = true;

        node.startOneShotRecovery(Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "orders", workflow(), workMethod())), Duration.ofMillis(100));
        assertTrue(valueWithin(() -> lock.failedAcquires.get() > 0, true, Duration.ofSeconds(5)),
                "the first pass met the failing backend");
        lock.failAcquire = false;

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)), "a later pass adopted the skipped row");
        assertFalse(valueWithin(node::isOneShotRecoveryRunning, false, Duration.ofSeconds(5)),
                "once a pass skipped nothing, the one-shot recovery ends");
    }

    @Test
    void aRowHeldElsewhereDoesNotKeepTheOneShotPassGoing() throws Exception {
        seedRecoverable(store, ID, "orders");
        lock.tryAcquire(KEY, Duration.ofSeconds(30)).orElseThrow(); // another node runs it

        node.startOneShotRecovery(Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "orders", workflow(), workMethod())), Duration.ofMillis(100));

        assertFalse(valueWithin(node::isOneShotRecoveryRunning, false, Duration.ofSeconds(5)),
                "a row another node holds is that node's: the pass is finished");
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Writes the row COMPLETED as another node that ran it to its end would. */
    private void finishElsewhere() {
        var current = store.getInstance(ID).orElseThrow();
        store.updateInstance(current.toBuilder().status(WorkflowStatus.COMPLETED)
                .completedAt(java.time.Instant.now()).version(current.version() + 1).build());
    }

    /** Runs a BoomWorkflow to FAILED and returns its registration; {@code fail} decides later attempts. */
    private WorkflowRegistration failedBoom(AtomicBoolean fail) {
        var registration = new WorkflowRegistration("BoomWorkflow", "default",
                () -> new BoomWorkflow(boomProxy(fail)), boomMethod());
        node.startWorkflow(ID, "BoomWorkflow", "default", null, registration.workflowFactory(), boomMethod());
        assertEquals(WorkflowStatus.FAILED, valueWithin(() -> store.getInstance(ID).orElseThrow().status(),
                WorkflowStatus.FAILED, Duration.ofSeconds(5)));
        // FAILED is visible before the run releases its lock: wait for the
        // backend release, so a Retry meets the backend, not a lock held here.
        assertFalse(valueWithin(() -> lock.isHeld(KEY), false, Duration.ofSeconds(5)),
                "the failed run released its instance lock");
        return registration;
    }

    /**
     * On the first launch admission, frees the key behind its holder's back and
     * waits until the renewer reports it lost; then, if {@code thenFail}, makes
     * every later acquisition fail.
     */
    private void loseTheLockOnFirstAdmission(boolean thenFail) {
        var first = new AtomicBoolean(true);
        node.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                lock.forceRelease(KEY);
                assertTrue(valueWithin(() -> observer.lost.contains(ID), true, Duration.ofSeconds(5)),
                        "the renewer reported the lock lost");
                lock.failAcquire = thenFail;
            }
        };
    }

    private java.util.function.Supplier<Object> workflow() {
        var proxy = work.proxy(node, store, serializer, "A");
        return () -> new WorkWorkflow(proxy);
    }

    private Boom boomProxy(AtomicBoolean fail) {
        Boom impl = () -> {
            if (fail.get()) {
                throw new IllegalStateException("boom (test)");
            }
            return "ok";
        };
        return new ActivityProxyFactory().createProxy(Boom.class, impl, store, null, RetryPolicy.noRetry(),
                Duration.ofSeconds(30), serializer, new RetryExecutor(),
                EngineObserver.NOOP, node.taskQueueGovernor());
    }

    private static Method boomMethod() {
        try {
            return BoomWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** Fails or succeeds as the test says. */
    @Activity(name = "boom")
    public interface Boom {
        /** @return a marker */
        String boom();
    }

    /** Calls {@link Boom} once. */
    @DurableWorkflow(name = "BoomWorkflow")
    public static final class BoomWorkflow {
        private final Boom boom;

        BoomWorkflow(Boom boom) {
            this.boom = boom;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return boom.boom();
        }
    }

    /** Records lost locks. */
    private static final class LostLockObserver implements EngineObserver {
        final List<String> lost = new CopyOnWriteArrayList<>();
        volatile Runnable onWorkflowFailed = () -> { };

        @Override
        public void workflowFailed(io.b2mash.maestro.core.observe.WorkflowInfo w, String exceptionType) {
            onWorkflowFailed.run();
        }

        @Override
        public void instanceLockLost(String workflowId) {
            lost.add(workflowId);
        }
    }

    /** A {@link SharedLock} whose {@code tryAcquire} throws while {@link #failAcquire} is set. */
    private static final class FlakyLock implements DistributedLock {

        private final SharedLock delegate = new SharedLock();
        final java.util.concurrent.atomic.AtomicInteger failedAcquires = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean failAcquire;

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (failAcquire) {
                failedAcquires.incrementAndGet();
                throw new IllegalStateException("lock backend unavailable (test)");
            }
            return delegate.tryAcquire(key, ttl);
        }

        volatile @org.jspecify.annotations.Nullable Error releaseFailure;

        @Override
        public void release(LockHandle handle) {
            var failure = releaseFailure;
            if (failure != null) {
                throw failure;
            }
            delegate.release(handle);
        }

        boolean isHeld(String key) {
            return delegate.isHeld(key);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return delegate.renew(handle, ttl);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return delegate.renewAll(handles, ttl);
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return delegate.heldKeys(keys);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }

        void forceRelease(String key) {
            delegate.forceRelease(key);
        }
    }
}
