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
