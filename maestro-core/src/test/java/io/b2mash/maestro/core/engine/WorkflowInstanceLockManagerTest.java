package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.WorkflowInstanceLockManager.Acquisition;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowInstanceLockManager}.
 */
class WorkflowInstanceLockManagerTest {

    private static final Duration SHORT_TTL = Duration.ofMillis(200);
    private static final Duration SHORT_RENEW = Duration.ofMillis(50);

    private WorkflowInstanceLockManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("non-positive ttl or renew interval is rejected")
    void nonPositiveTtlOrRenewIntervalRejected() {
        var lock = new RecordingLock();
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowInstanceLockManager(lock, "svc", Duration.ZERO, SHORT_RENEW));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowInstanceLockManager(lock, "svc", Duration.ofSeconds(-1), SHORT_RENEW));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, Duration.ofMillis(-10)));
    }

    @Test
    @DisplayName("acquires with the documented key format")
    void acquireUsesDocumentedKeyFormat() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("order-1"));
        assertEquals("maestro:lock:workflow:order-1", lock.acquiredKeys.getFirst());
        assertTrue(manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("custom key prefix is honoured")
    void customKeyPrefixIsHonoured() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", "acme:locks:", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("order-1"));
        assertEquals("acme:locks:workflow:order-1", lock.acquiredKeys.getFirst());
    }

    @Test
    @DisplayName("second local acquire for the same workflow reports HELD_ELSEWHERE")
    void secondLocalAcquireHeldElsewhere() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("order-1"));
        assertEquals(Acquisition.HELD_ELSEWHERE, manager.tryAcquire("order-1"));
        assertEquals(1, lock.acquiredKeys.size(), "backend must not be hit twice for a locally held lock");
    }

    @Test
    @DisplayName("backend contention reports HELD_ELSEWHERE")
    void backendContentionHeldElsewhere() {
        var lock = new RecordingLock();
        lock.grantAcquire.set(false);
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.HELD_ELSEWHERE, manager.tryAcquire("order-1"));
        assertFalse(manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("null backend reports NO_BACKEND")
    void nullBackendNoBackend() {
        manager = new WorkflowInstanceLockManager(null, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.NO_BACKEND, manager.tryAcquire("order-1"));
        assertFalse(manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("backend exception degrades to NO_BACKEND")
    void backendThrowsNoBackend() {
        var lock = new RecordingLock();
        lock.throwOnAcquire.set(true);
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.NO_BACKEND, manager.tryAcquire("order-1"));
        assertFalse(manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("renewer-start failure does not misreport ACQUIRED as NO_BACKEND — lock is already held")
    void renewerStartFailureStillReportsAcquired() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.renewerThreadStarter = () -> {
            throw new RuntimeException("simulated renewer-start failure");
        };

        var result = manager.tryAcquire("order-1");

        assertTrue(manager.isHeld("order-1"),
                "the lock IS held locally regardless of what tryAcquire reports — "
                        + "reporting NO_BACKEND here would make the caller skip release()");
        assertEquals(Acquisition.ACQUIRED, result,
                "a renewer-start failure must not be reported as NO_BACKEND while the lock is held");

        // release must still work end-to-end after a renewer-start failure.
        manager.release("order-1");
        assertFalse(manager.isHeld("order-1"));
        assertEquals(1, lock.released.size());
    }

    @Test
    @DisplayName("renewer-start failure resets the latch so the next acquisition retries starting the renewer")
    void renewerStartFailureResetsLatchForNextAcquisition() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        // Capture the real (production) starter before overwriting it, so it
        // can be swapped back in below.
        var workingStarter = manager.renewerThreadStarter;

        manager.renewerThreadStarter = () -> {
            throw new RuntimeException("simulated renewer-start failure");
        };
        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("order-1"));
        assertTrue(manager.isHeld("order-1"));

        // Swap back to a working starter and acquire a second, independent
        // lock. If startRenewerIfNeeded() left its latch burned after the
        // first failure, compareAndSet(false, true) here would still see
        // renewerStarted == true, the working starter would never even run,
        // and no lock on this node would ever be renewed again for the
        // life of the process.
        manager.renewerThreadStarter = workingStarter;
        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("order-2"));

        await().atMost(Duration.ofSeconds(2)).until(() ->
                lock.renewCounts.getOrDefault("maestro:lock:workflow:order-2", new AtomicInteger()).get() >= 1);
    }

    @Test
    @DisplayName("release removes the handle and releases at the backend")
    void releaseRemovesAndCallsBackend() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.tryAcquire("order-1");

        manager.release("order-1");

        assertFalse(manager.isHeld("order-1"));
        assertEquals(1, lock.released.size());
        assertEquals("maestro:lock:workflow:order-1", lock.released.getFirst().key());
    }

    @Test
    @DisplayName("renewer periodically renews all held locks")
    void renewerRenewsHeldLocks() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.tryAcquire("order-1");
        manager.tryAcquire("order-2");

        await().atMost(Duration.ofSeconds(2)).until(() ->
                lock.renewCounts.getOrDefault("maestro:lock:workflow:order-1", new AtomicInteger()).get() >= 2
                        && lock.renewCounts.getOrDefault("maestro:lock:workflow:order-2", new AtomicInteger()).get() >= 2);
    }

    @Test
    @DisplayName("lost lock (renew=false) is dropped without aborting anything")
    void renewFalseDropsHandle() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.tryAcquire("order-1");
        assertTrue(manager.isHeld("order-1"));

        lock.grantRenew.set(false);

        await().atMost(Duration.ofSeconds(2)).until(() -> !manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("renew exception keeps the handle and retries next cycle")
    void renewThrowsKeepsHandle() {
        var lock = new RecordingLock();
        lock.throwOnRenew.set(true);
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.tryAcquire("order-1");

        await().atMost(Duration.ofSeconds(2)).until(() -> lock.renewAttempts.get() >= 3);
        assertTrue(manager.isHeld("order-1"), "transient renew failure must not drop the lock");
    }

    @Test
    @DisplayName("close stops the renewer")
    void closeStopsRenewer() throws Exception {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.tryAcquire("order-1");
        await().atMost(Duration.ofSeconds(2)).until(() -> lock.renewAttempts.get() >= 1);

        manager.close();

        var countAtClose = lock.renewAttempts.get();
        Thread.sleep(300);
        assertTrue(lock.renewAttempts.get() <= countAtClose + 1,
                "renewer must stop after close (at most one in-flight cycle)");
    }

    // ── Recording DistributedLock ──────────────────────────────────────

    private static class RecordingLock implements DistributedLock {

        final CopyOnWriteArrayList<String> acquiredKeys = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<LockHandle> released = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<String, AtomicInteger> renewCounts = new ConcurrentHashMap<>();
        final AtomicInteger renewAttempts = new AtomicInteger();
        final AtomicBoolean grantAcquire = new AtomicBoolean(true);
        final AtomicBoolean grantRenew = new AtomicBoolean(true);
        final AtomicBoolean throwOnAcquire = new AtomicBoolean(false);
        final AtomicBoolean throwOnRenew = new AtomicBoolean(false);

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (throwOnAcquire.get()) {
                throw new RuntimeException("Simulated backend failure");
            }
            acquiredKeys.add(key);
            if (!grantAcquire.get()) {
                return Optional.empty();
            }
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
            released.add(handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            renewAttempts.incrementAndGet();
            if (throwOnRenew.get()) {
                throw new RuntimeException("Simulated backend failure");
            }
            renewCounts.computeIfAbsent(handle.key(), _ -> new AtomicInteger()).incrementAndGet();
            return grantRenew.get();
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
