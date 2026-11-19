package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.exception.WorkflowNotLaunchedException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 2 (Issue 70), the launch side: a launch admitted
 * before shutdown began but still taking its instance lock when the deadline
 * passes is given up with every other run, and then writes nothing — no
 * instance row for a start, no adoption compare-and-set (and no lease-epoch
 * bump) for a resume — and starts no run.
 */
@Timeout(60)
class GivenUpLaunchTest {

    private final LaunchGatedStore store = new LaunchGatedStore();
    private final GatedAcquireLock lock = new GatedAcquireLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final AtomicInteger factoryCalls = new AtomicInteger();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, SERVICE)
            .lifecycleEventsEnabled(false)
            .distributedLock(lock)
            .observer(observer)
            .shutdownTimeout(Duration.ofMillis(300))
            .build();

    @AfterEach
    void tearDown() {
        lock.open.countDown();
        executor.shutdown();
    }

    @Test
    void aStartStillTakingItsLockAtTheDeadlineWritesNoRowAndIsRefused() throws Exception {
        var thrown = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.startWorkflow("wf-start", "QuickWorkflow", "default", null, QuickWorkflow::new,
                        quickMethod());
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        assertTrue(lock.inAcquire.await(10, TimeUnit.SECONDS), "the start is inside tryAcquire");

        executor.shutdown(); // hits its 300 ms deadline: the start is still in tryAcquire
        lock.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(IllegalStateException.class, thrown.get(), String.valueOf(thrown.get()));
        assertTrue(store.getInstance("wf-start").isEmpty(), "a given-up start wrote its instance row");
        assertEquals(0, lock.delegate.heldCount(), "the given-up start released the lock it took");
        assertFalse(executor.isRunning("wf-start"));
    }

    @Test
    void aResumeStillTakingItsLockAtTheDeadlineDoesNotAdoptOrRun() throws Exception {
        var seeded = seedRecoverable(store, "wf-resume", "default");
        var launched = new AtomicReference<@Nullable Boolean>();
        var launcher = Thread.ofVirtual().start(() ->
                launched.set(executor.resumeWorkflow(seeded, QuickWorkflow::new, quickMethod())));
        assertTrue(lock.inAcquire.await(10, TimeUnit.SECONDS), "the resume is inside tryAcquire");

        executor.shutdown();
        lock.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertEquals(Boolean.FALSE, launched.get(), "a given-up resume must not launch");
        var row = store.getInstance("wf-resume").orElseThrow();
        assertEquals(seeded.version(), row.version(), "no adoption compare-and-set after the give-up");
        assertEquals(seeded.leaseEpoch(), row.leaseEpoch(), "no lease-epoch bump after the give-up");
        assertEquals(0, lock.delegate.heldCount(), "the given-up resume released the lock it took");
    }

    // ── PR #75 review: every later launcher write is fenced too ─────────

    /**
     * 4626925546: the start's row commits only after the deadline passed (its
     * {@code createInstance} returns late). The launcher then adopts no
     * orphaned signals, builds no workflow object and starts no run, releases
     * its lock, and tells the truth: the row exists, so the start throws
     * {@code WorkflowNotLaunchedException}, not "nothing was persisted".
     */
    @Test
    void aStartWhoseRowCommitsAfterTheDeadlineWritesNothingMoreAndSaysItWasPersisted() throws Exception {
        lock.open.countDown();
        var gate = store.blockOnce(LaunchGatedStore.Step.AFTER_CREATE);
        var launch = startInBackground("wf-late-row");
        assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "the row is committed; createInstance not returned");

        executor.shutdown(); // hits its 300 ms deadline and gives the start up
        gate.open.countDown();

        var thrown = launch.awaitThrown();
        assertInstanceOf(WorkflowNotLaunchedException.class, thrown, String.valueOf(thrown));
        assertEquals(io.b2mash.maestro.core.model.WorkflowStatus.RUNNING,
                store.getInstance("wf-late-row").orElseThrow().status(), "persisted, left recoverable");
        assertEquals(0, store.adoptions.get(), "no orphan-signal adoption after the give-up");
        assertEquals(0, factoryCalls.get(), "no workflow object built, no run started");
        assertTrue(observer.started().isEmpty(), "no workflowStarted / WORKFLOW_STARTED");
        assertEquals(0, lock.delegate.heldCount(), "the lock was released");
    }

    /**
     * 4626925602: a resume whose re-read returns only after the deadline does
     * not adopt — no compare-and-set, no lease-epoch bump — and releases its lock.
     */
    @Test
    void aResumeWhoseRereadReturnsAfterTheDeadlineDoesNotAdopt() throws Exception {
        lock.open.countDown();
        var seeded = seedRecoverable(store, "wf-late-read", "default");
        var gate = store.blockOnce(LaunchGatedStore.Step.GET_INSTANCE);
        var launched = new AtomicReference<@Nullable Boolean>();
        var launcher = Thread.ofVirtual().start(() ->
                launched.set(executor.resumeWorkflow(seeded, this::quickWorkflow, quickMethod())));
        assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "the resume is inside its re-read");

        executor.shutdown();
        gate.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertEquals(Boolean.FALSE, launched.get());
        var row = store.getInstance("wf-late-read").orElseThrow();
        assertEquals(seeded.version(), row.version(), "no adoption compare-and-set after the give-up");
        assertEquals(seeded.leaseEpoch(), row.leaseEpoch(), "no lease-epoch bump after the give-up");
        assertEquals(0, factoryCalls.get());
        assertEquals(0, lock.delegate.heldCount());
    }

    /**
     * Fix the class: a start whose workflow object is still being built at
     * the deadline publishes no {@code WORKFLOW_STARTED}, starts no run,
     * releases its lock and says the row was persisted.
     */
    @Test
    void aStartWhoseFactoryReturnsAfterTheDeadlineStartsNoRun() throws Exception {
        lock.open.countDown();
        var reached = new CountDownLatch(1);
        var go = new CountDownLatch(1);
        var thrown = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.startWorkflow("wf-slow-factory", "QuickWorkflow", "default", null, () -> {
                    reached.countDown();
                    awaitQuietly(go);
                    return new QuickWorkflow();
                }, quickMethod());
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        assertTrue(reached.await(10, TimeUnit.SECONDS), "the factory is running");

        executor.shutdown();
        go.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(WorkflowNotLaunchedException.class, thrown.get(), String.valueOf(thrown.get()));
        assertTrue(observer.started().isEmpty(), "no workflowStarted / WORKFLOW_STARTED");
        assertFalse(executor.isRunning("wf-slow-factory"));
        assertEquals(0, lock.delegate.heldCount());
    }

    /**
     * Fix the class: a Retry whose reopen commits only after the deadline
     * does not adopt or launch the reopened row; it is left RUNNING for
     * recovery, and the Retry says so ({@code WorkflowNotLaunchedException}).
     */
    @Test
    void aRetryWhoseReopenCommitsAfterTheDeadlineDoesNotAdoptOrRun() throws Exception {
        lock.open.countDown();
        executor.startWorkflow("wf-retry-late", "FailingWorkflow", "default", null, FailingWorkflow::new,
                FailingWorkflow.class.getMethod("run"));
        assertTrue(RecoveryCapacityFixtures.valueWithin(() -> store.getInstance("wf-retry-late").orElseThrow().status(),
                io.b2mash.maestro.core.model.WorkflowStatus.FAILED, Duration.ofSeconds(5))
                == io.b2mash.maestro.core.model.WorkflowStatus.FAILED);
        assertEquals(0, RecoveryCapacityFixtures.valueWithin(lock.delegate::heldCount, 0, Duration.ofSeconds(5)));
        var epochBefore = store.getInstance("wf-retry-late").orElseThrow().leaseEpoch();
        var gate = store.blockOnce(LaunchGatedStore.Step.AFTER_REOPEN);
        var thrown = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.retryWorkflow("wf-retry-late", new WorkflowRegistration("FailingWorkflow", "default",
                        () -> {
                            factoryCalls.incrementAndGet();
                            return new FailingWorkflow();
                        }, failingMethod()));
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "the reopen committed; it has not returned");

        executor.shutdown();
        gate.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(WorkflowNotLaunchedException.class, thrown.get(), String.valueOf(thrown.get()));
        var row = store.getInstance("wf-retry-late").orElseThrow();
        assertEquals(io.b2mash.maestro.core.model.WorkflowStatus.RUNNING, row.status(), "reopened, left for recovery");
        assertEquals(epochBefore, row.leaseEpoch(), "no adoption after the give-up");
        assertEquals(0, factoryCalls.get(), "no run started");
        assertEquals(0, lock.delegate.heldCount());
    }

    /** A Retry still taking its lock at the deadline writes nothing: the row stays FAILED. */
    @Test
    void aRetryStillTakingItsLockAtTheDeadlineWritesNothing() throws Exception {
        lock.open.countDown();
        executor.startWorkflow("wf-retry-lock", "FailingWorkflow", "default", null, FailingWorkflow::new,
                failingMethod());
        assertEquals(io.b2mash.maestro.core.model.WorkflowStatus.FAILED, RecoveryCapacityFixtures.valueWithin(
                () -> store.getInstance("wf-retry-lock").orElseThrow().status(),
                io.b2mash.maestro.core.model.WorkflowStatus.FAILED, Duration.ofSeconds(5)));
        assertEquals(0, RecoveryCapacityFixtures.valueWithin(lock.delegate::heldCount, 0, Duration.ofSeconds(5)));
        var failed = store.getInstance("wf-retry-lock").orElseThrow();
        lock.blockNextAcquire();
        var thrown = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.retryWorkflow("wf-retry-lock", new WorkflowRegistration("FailingWorkflow", "default",
                        FailingWorkflow::new, failingMethod()));
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        assertTrue(lock.inNextAcquire.await(10, TimeUnit.SECONDS));

        executor.shutdown();
        lock.nextOpen.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(IllegalStateException.class, thrown.get(), String.valueOf(thrown.get()));
        var row = store.getInstance("wf-retry-lock").orElseThrow();
        assertEquals(io.b2mash.maestro.core.model.WorkflowStatus.FAILED, row.status());
        assertEquals(failed.version(), row.version(), "nothing written");
        assertEquals(0, lock.delegate.heldCount());
    }

    /** A start on its own thread, and what it threw. */
    private record Launch(Thread thread, AtomicReference<@Nullable Throwable> thrown) {
        @Nullable Throwable awaitThrown() throws InterruptedException {
            assertTrue(thread.join(Duration.ofSeconds(10)), "the launch returned");
            return thrown.get();
        }
    }

    private Launch startInBackground(String workflowId) {
        var thrown = new AtomicReference<@Nullable Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                executor.startWorkflow(workflowId, "QuickWorkflow", "default", null, this::quickWorkflow,
                        quickMethod());
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        return new Launch(thread, thrown);
    }

    private Object quickWorkflow() {
        factoryCalls.incrementAndGet();
        return new QuickWorkflow();
    }

    private static Method failingMethod() {
        try {
            return FailingWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Method quickMethod() {
        try {
            return QuickWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fails at once. */
    @DurableWorkflow(name = "FailingWorkflow")
    public static final class FailingWorkflow {
        /** @return never */
        @WorkflowMethod
        public String run() {
            throw new IllegalStateException("the workflow fails (test)");
        }
    }

    /**
     * A {@link CountingStore} that counts orphan-signal adoptions and can hold
     * one launcher step — just after {@code createInstance} or
     * {@code reopenFailedInstance} committed, or inside the next
     * {@code getInstance} — until the test opens it.
     */
    static final class LaunchGatedStore extends CountingStore {
        enum Step { AFTER_CREATE, GET_INSTANCE, AFTER_REOPEN }

        record Gate(Step step, CountDownLatch reached, CountDownLatch open) {
            void pass() {
                reached.countDown();
                try {
                    open.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        final AtomicInteger adoptions = new AtomicInteger();
        private final AtomicReference<@Nullable Gate> gate = new AtomicReference<>();

        Gate blockOnce(Step step) {
            var armed = new Gate(step, new CountDownLatch(1), new CountDownLatch(1));
            gate.set(armed);
            return armed;
        }

        private void passIf(Step step) {
            var armed = gate.get();
            if (armed != null && armed.step() == step && gate.compareAndSet(armed, null)) {
                armed.pass();
            }
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var created = super.createInstance(instance);
            passIf(Step.AFTER_CREATE);
            return created;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            passIf(Step.GET_INSTANCE);
            return super.getInstance(workflowId);
        }

        @Override
        public synchronized void reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences,
                                                      Set<String> deleteTimerIds) {
            super.reopenFailedInstance(reopened, deleteEventSequences, deleteTimerIds);
            passIf(Step.AFTER_REOPEN);
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, java.util.UUID instanceId) {
            adoptions.incrementAndGet();
            super.adoptOrphanedSignals(workflowId, instanceId);
        }
    }

    /** Completes at once. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class QuickWorkflow {
        /** @return a constant */
        @WorkflowMethod
        public String run() {
            return "done";
        }
    }

    /** A {@link SharedLock} whose first {@code tryAcquire} blocks until {@link #open} is counted down. */
    static final class GatedAcquireLock implements DistributedLock {
        final SharedLock delegate = new SharedLock();
        final CountDownLatch inAcquire = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch inNextAcquire = new CountDownLatch(1);
        final CountDownLatch nextOpen = new CountDownLatch(1);
        private final AtomicBoolean blockNext = new AtomicBoolean();

        /** The next {@code tryAcquire} (after the first one's gate is open) blocks until {@link #nextOpen}. */
        void blockNextAcquire() {
            blockNext.set(true);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (blockNext.compareAndSet(true, false)) {
                inNextAcquire.countDown();
                try {
                    nextOpen.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (inAcquire.getCount() > 0) {
                inAcquire.countDown();
                try {
                    open.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
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
    }
}
