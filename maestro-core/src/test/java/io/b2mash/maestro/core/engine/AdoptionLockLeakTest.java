package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code Error} thrown while a launch holds a freshly acquired instance
 * lock — but before the run that would own it exists — must release the lock.
 * Otherwise the handle stays in the lock manager, the renewer renews it for
 * ever, every later {@code tryAcquire} answers {@code HELD_ELSEWHERE}, and the
 * workflow cannot be recovered by any node until this one restarts. Now that
 * the recovery poller's guard contains the {@code Error}, that is a live
 * poller that never adopts the workflow again.
 *
 * <p>Two windows: in {@code WorkflowExecutor.launchWorkflow}, between the
 * adoption and the run's thread start; and in
 * {@code WorkflowInstanceLockManager.tryAcquire}, after the handle is recorded
 * (the {@code instanceLockAcquired} callback; the renewer starts with the
 * manager since P2 task 4, so its start is no longer a window).
 */
class AdoptionLockLeakTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final VersionedInMemoryStore store = new VersionedInMemoryStore();
    private final GrantingLock lock = new GrantingLock();
    private final CountDownLatch ran = new CountDownLatch(1);
    private WorkflowExecutor executor;
    private WorkflowInstanceLockManager manager;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /** Window (a): an Error between admission and the run's thread start. */
    @Test
    void anErrorAfterAdmission_releasesTheLock_andALaterRecoveryPassAdoptsAndRunsTheWorkflow() throws Exception {
        executor = executor(EngineObserver.NOOP);
        var thrown = new AtomicBoolean();
        executor.afterLaunchAdmission = () -> {
            if (thrown.compareAndSet(false, true)) {
                throw new AssertionError("injected after admission");
            }
        };
        seedRecoverable("wf-1");

        executor.startRecoveryPoller(registrations(), Duration.ofMillis(50));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "no recovery pass after the Error adopted and ran the workflow");
        assertTrue(thrown.get(), "the first adoption hit the injected Error");
    }

    /** Window (c), W9: a Throwable on the run's thread before executeWorkflow is entered. */
    @Test
    void aThrowableBeforeTheRunBodyReleasesTheLockTheSlotAndTheEntry() throws Exception {
        executor = boundedExecutor(new TaskQueueLimits(1, 1));
        var thrown = new CountDownLatch(1);
        executor.beforeRunBody = () -> {
            thrown.countDown();
            throw new OutOfMemoryError("injected before the run body");
        };
        seedRecoverable("wf-1");
        executor.recoveryPass(registrations());
        assertTrue(thrown.await(5, TimeUnit.SECONDS));
        assertAll(
                () -> assertEquals(List.of("maestro:lock:workflow:wf-1"), valueWithin(() -> List.copyOf(lock.released),
                        List.of("maestro:lock:workflow:wf-1"), Duration.ofSeconds(5)), "the lock is released"),
                () -> assertFalse(executor.isRunning("wf-1"), "the running entry is gone"),
                () -> assertEquals(2, executor.taskQueueGovernor().recoveryHeadroom("default")));
    }

    /** Window (a), one pass at a time: the failed launch gave the lock back to the backend. */
    @Test
    void anErrorAfterAdmission_givesTheLockBackBeforeThePassThrows() {
        executor = executor(EngineObserver.NOOP);
        executor.afterLaunchAdmission = () -> {
            throw new AssertionError("injected after admission");
        };
        seedRecoverable("wf-1");

        assertThrows(AssertionError.class, () -> executor.recoverWorkflows(registrations()));

        assertEquals(List.of("maestro:lock:workflow:wf-1"), lock.released,
                "the handle acquired for the failed launch is released");
    }

    /**
     * Window (b): an Error from the instanceLockAcquired callback, after the
     * handle is recorded. A VirtualMachineError: the observer's containment
     * absorbs every other Error (Issue 81), and this one still reaches the
     * release.
     */
    @Test
    void anErrorFromTheLockAcquiredCallback_releasesTheLock_andALaterRecoveryPassAdoptsAndRunsTheWorkflow()
            throws Exception {
        var thrown = new AtomicBoolean();
        executor = executor(new EngineObserver() {
            @Override
            public void instanceLockAcquired(String workflowId) {
                if (thrown.compareAndSet(false, true)) {
                    throw new InternalError("injected in instanceLockAcquired");
                }
            }
        });
        seedRecoverable("wf-1");

        executor.startRecoveryPoller(registrations(), Duration.ofMillis(50));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "no recovery pass after the Error adopted and ran the workflow");
        assertTrue(thrown.get(), "the first acquisition hit the injected Error");
    }

    /**
     * Review d1: {@code startWorkflow} acquires the lock, then creates the row
     * and adopts orphaned signals. An {@code Error} from the adoption, after
     * the {@code RUNNING} row committed, must release the lock, or no later
     * recovery pass can adopt the workflow.
     */
    @Test
    void startWorkflow_anErrorAdoptingOrphanedSignals_releasesTheLockAndALaterRecoveryPassRunsTheWorkflow()
            throws Exception {
        var failingStore = new AdoptFailsOnceStore();
        executor = WorkflowExecutor.builder(failingStore, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .build();

        var thrown = assertThrows(AssertionError.class, () -> executor.startWorkflow("wf-d1", "LeakWorkflow",
                "default", null, () -> new LeakWorkflow(ran), workflowMethod()));
        assertEquals("injected in adoptOrphanedSignals", thrown.getMessage(), "the Error propagates unchanged");
        assertEquals(List.of("maestro:lock:workflow:wf-d1"), lock.released,
                "the handle acquired for the failed start is released");

        executor.startRecoveryPoller(registrations(), Duration.ofMillis(50));
        assertTrue(ran.await(5, TimeUnit.SECONDS), "no recovery pass adopted and ran the workflow");
    }

    /**
     * Fix wave e12, pinning {@code startWorkflow}'s {@code @throws} contract:
     * an {@code Error} after the {@code RUNNING} row is created leaves the row
     * {@code RUNNING}, releases the lock and propagates unchanged; a retry of
     * the same start is {@code WorkflowAlreadyExistsException}; the next
     * recovery pass adopts and runs the workflow. (A {@code RuntimeException}
     * from the adoption alone no longer fails the start: see
     * {@link #startWorkflow_anExceptionAdoptingOrphanedSignals_isContainedAndTheStartRunsTheWorkflow}.)
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Error"})
    void startWorkflow_aFailureAfterTheRowIsCreated_leavesItForRecoveryAndARetryOfTheStartAlreadyExists(String kind)
            throws Exception {
        var thrownOnce = new AtomicBoolean();
        var failingStore = new VersionedInMemoryStore() {
            @Override
            public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
                if (thrownOnce.compareAndSet(false, true)) {
                    if (kind.equals("Error")) {
                        throw new AssertionError("injected " + kind);
                    }
                    throw new IllegalStateException("injected " + kind);
                }
                super.adoptOrphanedSignals(workflowId, instanceId);
            }
        };
        executor = WorkflowExecutor.builder(failingStore, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .build();

        var thrown = assertThrows(Throwable.class, () -> executor.startWorkflow("wf-e12", "LeakWorkflow",
                "default", null, () -> new LeakWorkflow(ran), workflowMethod()));
        assertEquals("injected " + kind, thrown.getMessage(), "the failure propagates unchanged");
        assertEquals(WorkflowStatus.RUNNING, failingStore.getInstance("wf-e12").orElseThrow().status(),
                "the row created before the failure remains RUNNING");
        assertEquals(List.of("maestro:lock:workflow:wf-e12"), lock.released, "the lock is released");

        assertThrows(WorkflowAlreadyExistsException.class, () -> executor.startWorkflow("wf-e12", "LeakWorkflow",
                "default", null, () -> new LeakWorkflow(ran), workflowMethod()),
                "a retry of the same start finds the row");

        executor.startRecoveryPoller(registrations(), Duration.ofMillis(50));
        assertTrue(ran.await(5, TimeUnit.SECONDS), "no recovery pass adopted and ran the workflow");
    }

    /**
     * Issue 83 review: adoption is bookkeeping (an await finds a signal by
     * workflow id and name, and consumes it by row id), so an exception from
     * the adoption alone — here a store fault, {@code StoreUnavailableException},
     * after {@code createInstance} committed — is contained: one {@code WARN}
     * naming the workflow, and the start goes on to run the workflow with the
     * lock it took, instead of releasing it and failing a start whose
     * {@code RUNNING} row is already committed. The pre-delivered signal stays
     * unadopted and is still consumed.
     */
    @Test
    void startWorkflow_anExceptionAdoptingOrphanedSignals_isContainedAndTheStartRunsTheWorkflow() throws Exception {
        var failingStore = new VersionedInMemoryStore() {
            @Override
            public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
                throw new io.b2mash.maestro.core.exception.StoreUnavailableException(
                        "injected store fault", new java.sql.SQLException("connection reset"));
            }
        };
        executor = WorkflowExecutor.builder(failingStore, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .build();

        try (var logs = io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.startWorkflow("wf-adopt-fault", "LeakWorkflow", "default", null,
                    () -> new LeakWorkflow(ran), workflowMethod());
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the start itself ran the workflow, no recovery pass needed");
            var warnings = logs.records().stream()
                    .filter(r -> r.level() == org.slf4j.event.Level.WARN && r.message().contains("wf-adopt-fault"))
                    .toList();
            assertEquals(1, warnings.size(), "one WARN naming the workflow: " + logs.records());
        }
        await().atMost(Duration.ofSeconds(5)).until(() ->
                failingStore.getInstance("wf-adopt-fault").orElseThrow().status() == WorkflowStatus.COMPLETED);
    }

    /** A control-flow {@code Error} from the adoption is never contained: the start fails and releases its lock. */
    @Test
    void startWorkflow_aControlFlowErrorAdoptingOrphanedSignals_isNotContained() {
        var failingStore = new VersionedInMemoryStore() {
            @Override
            public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
                throw new io.b2mash.maestro.core.exception.ExecutorShutdownException("injected control flow");
            }
        };
        executor = WorkflowExecutor.builder(failingStore, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .build();

        assertThrows(io.b2mash.maestro.core.exception.ExecutorShutdownException.class,
                () -> executor.startWorkflow("wf-adopt-cf", "LeakWorkflow", "default", null,
                        () -> new LeakWorkflow(ran), workflowMethod()));
        assertEquals(List.of("maestro:lock:workflow:wf-adopt-cf"), lock.released, "the lock is released");
        assertEquals(1, ran.getCount(), "the workflow did not run");
    }

    /**
     * Review d6: when the release that follows a failed launch itself throws
     * an {@code Error}, the launch's original {@code Error} still reaches the
     * caller, with the release failure suppressed on it — at each of the three
     * release sites.
     */
    @Test
    void aReleaseThatThrowsDoesNotReplaceTheLaunchsError_afterAdmission() {
        var releaseFails = new ReleaseFailsLock();
        executor = WorkflowExecutor.builder(store, serializer, "svc")
                .distributedLock(releaseFails)
                .lifecycleEventsEnabled(false)
                .build();
        executor.afterLaunchAdmission = () -> {
            throw new AssertionError("injected after admission");
        };
        seedRecoverable("wf-1");

        var thrown = assertThrows(AssertionError.class, () -> executor.recoverWorkflows(registrations()));

        assertEquals("injected after admission", thrown.getMessage());
        assertSuppressedReleaseFailure(thrown);
    }

    @Test
    void aReleaseThatThrowsDoesNotReplaceTheLaunchsError_inStartWorkflow() {
        var releaseFails = new ReleaseFailsLock();
        executor = WorkflowExecutor.builder(new AdoptFailsOnceStore(), serializer, "svc")
                .distributedLock(releaseFails)
                .lifecycleEventsEnabled(false)
                .build();

        var thrown = assertThrows(AssertionError.class, () -> executor.startWorkflow("wf-d6", "LeakWorkflow",
                "default", null, () -> new LeakWorkflow(ran), workflowMethod()));

        assertEquals("injected in adoptOrphanedSignals", thrown.getMessage());
        assertSuppressedReleaseFailure(thrown);
    }

    /**
     * In {@code tryAcquire}, after the handle is recorded, only the
     * {@code instanceLockAcquired} callback can still throw (the renewer starts
     * with the manager since P2 task 4, and the observer is contained but for
     * a VirtualMachineError or a control-flow Error): its Error reaches the
     * caller, the handle is given back, and a release that throws is
     * suppressed on it, not put in its place.
     */
    @Test
    void aReleaseThatThrowsDoesNotReplaceTheLaunchsError_inTryAcquire() {
        manager = new WorkflowInstanceLockManager(new ReleaseFailsLock(), "svc", "maestro:lock:",
                Duration.ofSeconds(30), Duration.ofMillis(50), new EngineObserver() {
                    @Override
                    public void instanceLockAcquired(String workflowId) {
                        throw new OutOfMemoryError("observer allocation");
                    }
                });

        var thrown = assertThrows(OutOfMemoryError.class, () -> manager.tryAcquire("wf-1"));

        assertEquals("observer allocation", thrown.getMessage());
        assertSuppressedReleaseFailure(thrown);
    }

    private static void assertSuppressedReleaseFailure(Throwable thrown) {
        assertEquals(List.of("release bug"),
                java.util.Arrays.stream(thrown.getSuppressed()).map(Throwable::getMessage).toList(),
                "the release failure is suppressed on the original");
    }

    /** Grants every lock; {@code release} throws an {@code AssertionError}, as a broken backend might. */
    static final class ReleaseFailsLock implements DistributedLock {

        private final GrantingLock delegate = new GrantingLock();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
            throw new AssertionError("release bug");
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return delegate.renew(handle, ttl);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return Set.of();
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    /** Throws an {@code AssertionError} from the first {@code adoptOrphanedSignals}, after the row exists. */
    static final class AdoptFailsOnceStore extends VersionedInMemoryStore {

        private final AtomicBoolean thrown = new AtomicBoolean();

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            if (thrown.compareAndSet(false, true)) {
                throw new AssertionError("injected in adoptOrphanedSignals");
            }
            super.adoptOrphanedSignals(workflowId, instanceId);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private WorkflowExecutor executor(EngineObserver observer) {
        return WorkflowExecutor.builder(store, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .observer(observer)
                .build();
    }

    private WorkflowExecutor boundedExecutor(TaskQueueLimits defaults) {
        return WorkflowExecutor.builder(store, serializer, "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .defaultTaskQueueLimits(defaults)
                .build();
    }

    private Map<String, WorkflowRegistration> registrations() {
        return Map.of("LeakWorkflow", new WorkflowRegistration(
                "LeakWorkflow", "default", () -> new LeakWorkflow(ran), workflowMethod()));
    }

    private void seedRecoverable(String workflowId) {
        var startedAt = Instant.now().minusSeconds(60);
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("LeakWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(startedAt)
                .updatedAt(startedAt)
                .version(0)
                .build());
    }

    private static Method workflowMethod() {
        try {
            return LeakWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts down {@code ran} when it runs. */
    @DurableWorkflow(name = "LeakWorkflow")
    public static class LeakWorkflow {

        private final CountDownLatch ran;

        LeakWorkflow(CountDownLatch ran) {
            this.ran = ran;
        }

        /** @return a fixed result */
        @WorkflowMethod
        public String run() {
            ran.countDown();
            return "done";
        }
    }

    /** A lock backend with real per-key exclusion that records releases. */
    static final class GrantingLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> held = new ConcurrentHashMap<>();
        final List<String> released = new CopyOnWriteArrayList<>();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return held.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            held.remove(handle.key(), handle);
            released.add(handle.key());
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return handle.equals(held.get(handle.key()));
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return Set.of();
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }
}
