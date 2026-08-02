package io.b2mash.maestro.core.engine;

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

    // ── Scripted lock backend ─────────────────────────────────────────

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
