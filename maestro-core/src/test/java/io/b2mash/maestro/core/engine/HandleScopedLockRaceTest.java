package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.WorkflowExecutorTerminateTest.BackoffWorkflow;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instance lock a run carries is the handle <em>its own</em> acquisition
 * returned (PR #72 review, Group A). While a launch is between its acquisition
 * and its registration, its handle can be reported lost and a second launch of
 * the same id can acquire a newer one. Then:
 *
 * <ul>
 *   <li>the first launch keeps its own (lost) handle, never the newer one, so it
 *       does not adopt the row (bumping the lease epoch under the newer run), does
 *       not register over it and does not release its lock (4622153262,
 *       4622153299);</li>
 *   <li>the lock manager's cleanup after a throwing {@code instanceLockAcquired}
 *       observer releases the failed attempt's own handle, never the newer one
 *       (4622153312);</li>
 *   <li>a run whose lock is lost after it registered but before its thread enters
 *       the workflow method never runs user code, whatever its task queue
 *       (4622153307).</li>
 * </ul>
 */
class HandleScopedLockRaceTest {

    private static final String ID = "wf-race";
    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:" + ID;

    private final CountingStore store = new CountingStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final SharedLock lock = new SharedLock();
    private final HookingObserver observer = new HookingObserver();
    private final AtomicInteger backoffInvocations = new AtomicInteger();
    private final WorkflowExecutor node = WorkflowExecutor.builder(store, serializer, SERVICE)
            .distributedLock(lock)
            .observer(observer)
            .lifecycleEventsEnabled(false)
            .instanceLockTtl(Duration.ofMillis(300)) // renewed every 100 ms: a forced loss is noticed at once
            .shutdownTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void tearDown() {
        node.shutdown();
    }

    @Test
    void aResumeWhoseHandleIsLostBeforeItAdoptsNeitherAdoptsNorTakesTheNewerRunsHandle() throws Exception {
        var seeded = seedRecoverable(store, ID, "default");
        var bLaunched = new AtomicReference<@Nullable Boolean>();
        // A's acquisition is reported to the observer; while A is still inside
        // it, A's handle is lost and B (another thread) acquires and adopts.
        observer.onFirstAcquire = () -> {
            loseTheLock();
            var b = Thread.ofVirtual().start(() -> bLaunched.set(node.resumeWorkflow(seeded, backoff(), backoffMethod())));
            join(b);
        };

        var aLaunched = node.resumeWorkflow(seeded, backoff(), backoffMethod());

        assertEquals(Boolean.TRUE, bLaunched.get(), "B acquired the freed lock and launched");
        assertFalse(aLaunched, "A must not run on B's lock");
        assertEquals(1L, store.getInstance(ID).orElseThrow().leaseEpoch(),
                "only B adopted: A, holding a lost handle, must not bump the lease epoch under B");
        assertEquals(1, valueWithin(backoffInvocations::get, 1, Duration.ofSeconds(5)), "B runs");
        assertTrue(node.isRunning(ID), "B is registered");
        assertTrue(lock.isHeld(KEY), "B still holds its lock");
    }

    @Test
    void aThrowingAcquiredObserverReleasesItsOwnAttemptsHandleNotTheNewerOne() throws Exception {
        var manager = new WorkflowInstanceLockManager(lock, SERVICE, WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofMillis(300), Duration.ofMillis(100), observer);
        var second = new AtomicReference<WorkflowInstanceLockManager.@Nullable Acquisition>();
        observer.onFirstAcquire = () -> {
            loseTheLock();
            var b = Thread.ofVirtual().start(() -> second.set(manager.tryAcquire(ID)));
            join(b);
            throw new AssertionError("observer failed after the lock changed hands (test)");
        };
        try {
            try {
                manager.tryAcquire(ID);
            } catch (AssertionError expected) {
                // the observer's Error reaches the caller, after cleanup
            }
            assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, second.get());
            assertTrue(manager.isHeld(ID), "the newer acquisition is still held by the manager");
            assertTrue(lock.isHeld(KEY), "the newer acquisition's lock row was not deleted");
        } finally {
            manager.close();
        }
    }

    @Test
    void aRunWhoseLockIsLostBeforeItsThreadEntersNeverRunsTheWorkflowOnAnUnboundedQueue() throws Exception {
        var invoked = new AtomicBoolean();
        var first = new AtomicBoolean(true);
        node.beforeRunThreadStart = () -> {
            if (first.compareAndSet(true, false)) {
                loseTheLock(); // the run is registered: the loss stands it down
            }
        };

        node.startWorkflow(ID, "FlagWorkflow", "default", null, () -> new FlagWorkflow(invoked), flagMethod());

        assertEquals(List.of(StandDownReason.STALE_RUN), valueWithin(observer::standDownReasons,
                List.of(StandDownReason.STALE_RUN), Duration.ofSeconds(5)), "the run stood down as stale");
        assertFalse(invoked.get(), "a run that lost its lock before it began must not run user code");
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Frees the key behind the holder's back and waits until its renewer reports it lost. */
    private void loseTheLock() {
        lock.forceRelease(KEY);
        assertTrue(valueWithin(() -> observer.lost.contains(ID), true, Duration.ofSeconds(5)),
                "the renewer reported the lock lost");
    }

    private Supplier<Object> backoff() {
        var proxy = WorkflowExecutorTerminateTest.failingProxy(store, serializer, backoffInvocations);
        return () -> new BackoffWorkflow(proxy);
    }

    private static Method backoffMethod() {
        return method(BackoffWorkflow.class);
    }

    private static Method flagMethod() {
        return method(FlagWorkflow.class);
    }

    private static Method method(Class<?> type) {
        try {
            return type.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static void join(Thread thread) {
        try {
            assertTrue(thread.join(Duration.ofSeconds(10)), "the second launch returned");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Sets a flag when its body runs. */
    @DurableWorkflow(name = "FlagWorkflow")
    public static final class FlagWorkflow {
        private final AtomicBoolean invoked;

        FlagWorkflow(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        /** @return a constant */
        @WorkflowMethod
        public String run() {
            invoked.set(true);
            return "done";
        }
    }

    /** Records lost locks and stand-downs; runs a hook on the first {@code instanceLockAcquired}. */
    private static final class HookingObserver implements EngineObserver {
        final List<String> lost = new CopyOnWriteArrayList<>();
        final List<StandDownReason> standDowns = new CopyOnWriteArrayList<>();
        final AtomicBoolean first = new AtomicBoolean(true);
        volatile Runnable onFirstAcquire = () -> { };

        @Override
        public void instanceLockAcquired(String workflowId) {
            if (first.compareAndSet(true, false)) {
                onFirstAcquire.run();
            }
        }

        @Override
        public void instanceLockLost(String workflowId) {
            lost.add(workflowId);
        }

        @Override
        public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
            standDowns.add(reason);
        }

        List<StandDownReason> standDownReasons() {
            return List.copyOf(standDowns);
        }
    }
}
