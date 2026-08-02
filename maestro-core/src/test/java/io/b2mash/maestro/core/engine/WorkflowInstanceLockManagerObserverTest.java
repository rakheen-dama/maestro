package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code EngineObserver} lock callbacks (design doc §1.3):
 * {@code instanceLockAcquired} on a winning acquire,
 * {@code instanceLockRenewFailed} on a transient renew error (handle kept),
 * {@code instanceLockLost} when renewal reports lost ownership (handle dropped).
 */
@DisplayName("WorkflowInstanceLockManager emits lock observer callbacks")
class WorkflowInstanceLockManagerObserverTest {

    private WorkflowInstanceLockManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("a winning acquire emits instanceLockAcquired; HELD_ELSEWHERE and NO_BACKEND emit nothing")
    void acquireEmitsOnlyWhenAcquired() {
        var observer = new RecordingEngineObserver();
        var lock = new ScriptedLock();
        manager = new WorkflowInstanceLockManager(lock, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofSeconds(10), observer);

        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-a"));
        assertEquals(List.of("wf-a"), observer.lockAcquired());

        lock.refuseAcquire = true;
        assertEquals(WorkflowInstanceLockManager.Acquisition.HELD_ELSEWHERE, manager.tryAcquire("wf-b"));
        assertEquals(List.of("wf-a"), observer.lockAcquired(),
                "a lock held elsewhere must not emit instanceLockAcquired");

        lock.throwOnAcquire = true;
        assertEquals(WorkflowInstanceLockManager.Acquisition.NO_BACKEND, manager.tryAcquire("wf-c"));
        assertEquals(List.of("wf-a"), observer.lockAcquired(),
                "an unavailable backend must not emit instanceLockAcquired");
    }

    @Test
    @DisplayName("a transient renew error emits instanceLockRenewFailed and keeps the handle")
    void transientRenewErrorEmitsRenewFailed() {
        var observer = new RecordingEngineObserver();
        var lock = new ScriptedLock();
        manager = new WorkflowInstanceLockManager(lock, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofMillis(50), observer);

        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-renew"));
        lock.renewMode.set(RenewMode.THROW);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(observer.lockRenewFailed().isEmpty(),
                        "a throwing renew must emit instanceLockRenewFailed"));
        assertEquals("wf-renew", observer.lockRenewFailed().getFirst());
        assertTrue(manager.isHeld("wf-renew"),
                "a transient renew error keeps the handle for the next cycle");
        assertEquals(0, observer.lockLost().size());
    }

    @Test
    @DisplayName("lost ownership emits instanceLockLost and drops the handle")
    void lostOwnershipEmitsLockLost() {
        var observer = new RecordingEngineObserver();
        var lock = new ScriptedLock();
        manager = new WorkflowInstanceLockManager(lock, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofMillis(50), observer);

        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-lost"));
        lock.renewMode.set(RenewMode.LOST);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(observer.lockLost().isEmpty(),
                        "a renew reporting lost ownership must emit instanceLockLost"));
        assertEquals("wf-lost", observer.lockLost().getFirst());
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertFalse(manager.isHeld("wf-lost"), "a lost lock's handle must be dropped"));
    }

    // ── A throwing observer must never be mistaken for a backend failure ──

    @Test
    @DisplayName("a throwing instanceLockAcquired does not report NO_BACKEND and does not leak the lock")
    void throwingObserverOnAcquireIsContained() {
        var observer = new ThrowingObserver();
        var lock = new ScriptedLock();
        manager = new WorkflowInstanceLockManager(lock, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofSeconds(10), observer);

        // The lock IS held once tryAcquire put the handle in heldLocks. Were a
        // throwing adapter (e.g. a Micrometer meter-name conflict) reported as
        // NO_BACKEND, WorkflowExecutor would skip release() for this workflow
        // and the renewer would keep the lock alive forever — the workflowId
        // becomes permanently unacquirable on this node and blocked elsewhere.
        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-throw"),
                "a throwing observer must not be reported as a lock-backend failure");
        assertEquals(List.of("wf-throw"), observer.acquired,
                "the callback must still have been attempted");
        assertTrue(manager.isHeld("wf-throw"), "the acquired lock must still be tracked");

        manager.release("wf-throw");
        assertFalse(manager.isHeld("wf-throw"), "the caller's release must drop the handle");
    }

    @Test
    @DisplayName("a throwing instanceLockLost does not kill the renewer — every held lock is still processed")
    void throwingObserverOnLockLostKeepsRenewerAlive() {
        var observer = new ThrowingObserver();
        var lock = new ScriptedLock();
        manager = new WorkflowInstanceLockManager(lock, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofMillis(50), observer);

        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));
        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-2"));
        lock.renewMode.set(RenewMode.LOST);

        // An escaping RuntimeException would unwind renewOne, the for-loop and
        // renewLoop itself: the single renewer thread dies and every other
        // held lock silently stops being renewed.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertFalse(manager.isHeld("wf-1"), "wf-1's lost lock must be dropped");
            assertFalse(manager.isHeld("wf-2"),
                    "wf-2 must still be renewed after the observer threw for wf-1");
        });
    }

    // ── Scripted lock backend ─────────────────────────────────────────

    /** Models an adapter that throws on every lock callback (Task 4's risk). */
    private static final class ThrowingObserver implements EngineObserver {

        final List<String> acquired = new CopyOnWriteArrayList<>();
        final List<String> lost = new CopyOnWriteArrayList<>();
        final List<String> renewFailed = new CopyOnWriteArrayList<>();

        @Override
        public void instanceLockAcquired(String workflowId) {
            acquired.add(workflowId);
            throw new IllegalStateException("adapter blew up (e.g. meter name conflict)");
        }

        @Override
        public void instanceLockLost(String workflowId) {
            lost.add(workflowId);
            throw new IllegalStateException("adapter blew up (e.g. meter name conflict)");
        }

        @Override
        public void instanceLockRenewFailed(String workflowId) {
            renewFailed.add(workflowId);
            throw new IllegalStateException("adapter blew up (e.g. meter name conflict)");
        }
    }

    private enum RenewMode { OK, THROW, LOST }

    private static final class ScriptedLock implements DistributedLock {

        volatile boolean refuseAcquire;
        volatile boolean throwOnAcquire;
        final AtomicReference<RenewMode> renewMode = new AtomicReference<>(RenewMode.OK);

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (throwOnAcquire) {
                throw new RuntimeException("backend unavailable");
            }
            if (refuseAcquire) {
                return Optional.empty();
            }
            return Optional.of(new LockHandle(key, "token", Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
            // no-op
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return switch (renewMode.get()) {
                case OK -> true;
                case THROW -> throw new RuntimeException("transient renew error");
                case LOST -> false;
            };
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
