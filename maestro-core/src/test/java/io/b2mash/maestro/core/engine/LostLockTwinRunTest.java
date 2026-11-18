package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.Work;
import io.b2mash.maestro.core.engine.WorkflowExecutorTerminateTest.BackoffWorkflow;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Two runs of one workflow id on one node, after the first lost its instance
 * lock (pilot readiness P3, review L1). Since a lock is mandatory this is the
 * only way to reach that state: run A loses its lock (renewals report it lost)
 * while run B of the same id, launched by another thread, acquires it and
 * adopts. Every release and every lost-lock stand-down goes by the handle a run
 * acquired, so:
 *
 * <ul>
 *   <li>a launch whose lock was lost before it registered does not run at all
 *       and leaves the newer run registered and locked;</li>
 *   <li>a run that lost its lock and ends removes only its own
 *       {@code runningWorkflows} entry and releases nothing of the newer run's
 *       lock;</li>
 *   <li>shutdown's drain still waits for, and stops, every run it tracks,
 *       including the one no longer in {@code runningWorkflows}.</li>
 * </ul>
 *
 * Before L1 the release went by workflow id: a run that lost its lock deleted
 * the newer run's lock, and a launch whose lock was lost before it registered
 * replaced the newer run's entry and ran on unlocked.
 */
class LostLockTwinRunTest {

    private static final String ID = "wf-twin";
    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:" + ID;

    private final CountingStore store = new CountingStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final SharedLock lock = new SharedLock();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final BlockingWork workA = new BlockingWork();
    private final AtomicInteger backoffInvocations = new AtomicInteger();
    private final AtomicReference<@Nullable Thread> threadA = new AtomicReference<>();
    private WorkflowExecutor node;
    private WorkflowInstance seeded;

    @BeforeEach
    void setUp() {
        node = WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .observer(observer)
                .lifecycleEventsEnabled(false)
                // renewed every 100 ms, so a forced loss is noticed at once
                .instanceLockTtl(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofSeconds(10))
                .build();
        seeded = seedRecoverable(store, ID, "default");
    }

    @AfterEach
    void tearDown() {
        workA.releaseAll();
        node.shutdown();
    }

    @Test
    void aLaunchWhoseLockIsLostBeforeItRegistersDoesNotRunAndLeavesTheNewerRunAlone() throws Exception {
        // A is held after adoption, before it registers.
        var aHeld = new CountDownLatch(1);
        var aGo = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        node.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                aHeld.countDown();
                await(aGo, "A was let go");
            }
        };
        var aLaunched = new AtomicReference<@Nullable Boolean>();
        var launcherA = Thread.ofVirtual().start(() -> aLaunched.set(node.resumeWorkflow(seeded, runA(), runAMethod())));
        await(aHeld, "A was admitted");

        loseTheLock();
        assertTrue(node.resumeWorkflow(seeded, runB(), backoffMethod()), "B acquired the free lock and launched");
        assertEquals(1, valueWithin(backoffInvocations::get, 1, Duration.ofSeconds(5)), "B is in its backoff");

        aGo.countDown();
        assertTrue(launcherA.join(Duration.ofSeconds(5)), "A's launch returned");

        assertEquals(Boolean.FALSE, aLaunched.get(), "a launch whose lock was lost before it registered must not run");
        assertEquals(0, workA.entries(), "A never reached its activity");
        assertTrue(node.isRunning(ID), "B is still registered");
        assertTrue(lock.isHeld(KEY), "B still holds its lock");

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var start = System.nanoTime();
            node.shutdown();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000, "B's backoff was abandoned");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("1 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
    }

    @Test
    void aRunThatLostItsLockEndsWithoutTouchingTheNewerRunsEntryOrLock() throws Exception {
        launchTwins();

        // A's activity returns; A is stale and stands down; its run ends.
        var a = threadA.get();
        assertTrue(a != null, "A recorded its thread");
        workA.releaseAll();
        assertTrue(a.join(Duration.ofSeconds(5)), "A's run ended");

        assertTrue(node.isRunning(ID), "A's end removed only its own entry: B is still registered");
        assertTrue(lock.isHeld(KEY), "A's end released nothing of B's lock");
    }

    @Test
    void shutdownWaitsForAndStopsEveryTrackedRunIncludingTheOneNoLongerRegistered() throws Exception {
        launchTwins();

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                node.shutdown();
                done.countDown();
            });
            assertFalse(done.await(500, TimeUnit.MILLISECONDS),
                    "shutdown waits for A, which is tracked only by the drain, still inside its activity");
            workA.releaseAll();
            assertTrue(done.await(5, TimeUnit.SECONDS), "shutdown returned once A ended");
            assertEquals(1, backoffInvocations.get(), "B's backoff was abandoned, not waited out");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("2 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
    }

    /**
     * B passes the already-running check and is held; A launches and enters its
     * (blocking) activity; A's lock is lost, which stands A down at its next
     * stop point; B is let go, acquires the lock, adopts and registers over A,
     * and parks in a retry backoff.
     */
    private void launchTwins() throws Exception {
        var bHeld = new CountDownLatch(1);
        var bGo = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        node.beforeResumeLaunch = () -> {
            if (first.compareAndSet(true, false)) {
                bHeld.countDown();
                await(bGo, "B was let go");
            }
        };
        var bLaunched = new AtomicReference<@Nullable Boolean>();
        var launcherB = Thread.ofVirtual().start(() -> bLaunched.set(node.resumeWorkflow(seeded, runB(), backoffMethod())));
        await(bHeld, "B passed the already-running check");

        assertTrue(node.resumeWorkflow(seeded, runA(), runAMethod()), "A launched");
        assertEquals(1, valueWithin(workA::entries, 1, Duration.ofSeconds(5)), "A is inside its activity");

        loseTheLock();
        bGo.countDown();
        assertTrue(launcherB.join(Duration.ofSeconds(5)), "B's launch returned");
        assertEquals(Boolean.TRUE, bLaunched.get(), "B acquired the free lock and launched over A");
        assertEquals(1, valueWithin(backoffInvocations::get, 1, Duration.ofSeconds(5)), "B is in its backoff");
    }

    /** Frees A's lock behind its back and waits until A's renewer reports it lost. */
    private void loseTheLock() {
        lock.forceRelease(KEY);
        assertTrue(valueWithin(() -> observer.lockLost().contains(ID), true, Duration.ofSeconds(5)),
                "A's renewer reported the lock lost");
    }

    private Supplier<Object> runA() {
        var proxy = workA.proxy(node, store, serializer, "A");
        return () -> new ThreadRecordingWorkflow(proxy, threadA);
    }

    private Supplier<Object> runB() {
        var proxy = WorkflowExecutorTerminateTest.failingProxy(store, serializer, backoffInvocations);
        return () -> new BackoffWorkflow(proxy);
    }

    private static Method runAMethod() {
        return method(ThreadRecordingWorkflow.class);
    }

    private static Method backoffMethod() {
        return method(BackoffWorkflow.class);
    }

    private static Method method(Class<?> type) {
        try {
            return type.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what, e);
        }
    }

    /** Records its run's thread, then calls the blocking activity. */
    @DurableWorkflow(name = "WorkWorkflow")
    public static final class ThreadRecordingWorkflow {
        private final Work work;
        private final AtomicReference<@Nullable Thread> thread;

        ThreadRecordingWorkflow(Work work, AtomicReference<@Nullable Thread> thread) {
            this.work = work;
            this.thread = thread;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            thread.set(Thread.currentThread());
            return work.work(WorkflowContext.current().workflowId());
        }
    }
}
