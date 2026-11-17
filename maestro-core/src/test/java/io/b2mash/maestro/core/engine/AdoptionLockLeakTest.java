package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.awaitility.Awaitility.await;
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
 * (the renewer start and the {@code instanceLockAcquired} callback).
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

    /** Window (b): an Error from the instanceLockAcquired callback, after the handle is recorded. */
    @Test
    void anErrorFromTheLockAcquiredCallback_releasesTheLock_andALaterRecoveryPassAdoptsAndRunsTheWorkflow()
            throws Exception {
        var thrown = new AtomicBoolean();
        executor = executor(new EngineObserver() {
            @Override
            public void instanceLockAcquired(String workflowId) {
                if (thrown.compareAndSet(false, true)) {
                    throw new AssertionError("injected in instanceLockAcquired");
                }
            }
        });
        seedRecoverable("wf-1");

        executor.startRecoveryPoller(registrations(), Duration.ofMillis(50));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "no recovery pass after the Error adopted and ran the workflow");
        assertTrue(thrown.get(), "the first acquisition hit the injected Error");
    }

    /**
     * Window (b): an Error starting the renewer. The handle is released, the
     * start is un-latched, and the next acquisition starts the renewer.
     */
    @Test
    void anErrorStartingTheRenewer_releasesTheHandleAndTheNextAcquisitionStartsTheRenewer() {
        manager = new WorkflowInstanceLockManager(lock, "svc", "maestro:lock:", Duration.ofSeconds(30),
                Duration.ofMillis(50), EngineObserver.NOOP);
        var working = manager.renewerThreadStarter;
        manager.renewerThreadStarter = () -> {
            throw new OutOfMemoryError("unable to create native thread");
        };

        assertThrows(OutOfMemoryError.class, () -> manager.tryAcquire("wf-1"));

        assertFalse(manager.isHeld("wf-1"), "no run owns the handle, so it is not kept");
        assertEquals(List.of("maestro:lock:workflow:wf-1"), lock.released, "the backend lock is given back");
        assertFalse(manager.hasRenewerStarted(), "the failed start is un-latched");

        manager.renewerThreadStarter = working;
        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));
        await("the renewer to start on the next acquisition").atMost(Duration.ofSeconds(5))
                .until(manager::isRenewerRunning);
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

    @Test
    void aReleaseThatThrowsDoesNotReplaceTheLaunchsError_inTryAcquire() {
        manager = new WorkflowInstanceLockManager(new ReleaseFailsLock(), "svc", "maestro:lock:",
                Duration.ofSeconds(30), Duration.ofMillis(50), EngineObserver.NOOP);
        manager.renewerThreadStarter = () -> {
            throw new OutOfMemoryError("unable to create native thread");
        };

        var thrown = assertThrows(OutOfMemoryError.class, () -> manager.tryAcquire("wf-1"));

        assertEquals("unable to create native thread", thrown.getMessage());
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
