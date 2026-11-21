package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.WorkflowInstanceLockManager.Acquisition;
import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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

    /**
     * P2 task 4 (Issue 68, part 1): the renewer starts with the manager — the
     * executor's build — not with the first acquisition, so there is no
     * "never started" state for held locks to fall into.
     */
    @Test
    @DisplayName("the renewer runs before the first acquisition")
    void theRenewerRunsBeforeTheFirstAcquisition() {
        manager = new WorkflowInstanceLockManager(new RecordingLock(), "svc", SHORT_TTL, SHORT_RENEW);
        assertTrue(manager.isRenewerRunning(), "started with the manager, before any acquisition");
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
    @DisplayName("a null backend is rejected: a DistributedLock is mandatory (P3)")
    @SuppressWarnings("DataFlowIssue")
    void nullBackendRejected() {
        assertThrows(NullPointerException.class,
                () -> new WorkflowInstanceLockManager(null, "svc", SHORT_TTL, SHORT_RENEW));
    }

    @Test
    @DisplayName("release is handle-scoped: a stale handle releases nothing of a newer acquisition (L1)")
    void releaseByAStaleHandleLeavesTheNewerLockAlone() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        var first = manager.acquire("order-1");
        assertEquals(Acquisition.ACQUIRED, first.outcome());
        manager.release("order-1", first.handle());
        var second = manager.acquire("order-1").handle();

        manager.release("order-1", first.handle()); // the first run, ending late
        assertTrue(manager.holds("order-1", second), "the newer acquisition is still held");
        assertEquals(List.of(first.handle()), lock.released, "only the first handle was ever released to the backend");

        manager.release("order-1", null); // a launch that never acquired
        assertTrue(manager.holds("order-1", second));
    }

    @Test
    @DisplayName("backend exception reports UNAVAILABLE and holds nothing")
    void backendThrowsUnavailable() {
        var lock = new RecordingLock();
        lock.throwOnAcquire.set(true);
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);

        assertEquals(Acquisition.UNAVAILABLE, manager.tryAcquire("order-1"));
        assertFalse(manager.isHeld("order-1"));
    }

    @Test
    @DisplayName("after close, tryAcquire reports UNAVAILABLE without asking the backend")
    void afterCloseUnavailable() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        manager.close();

        assertEquals(Acquisition.UNAVAILABLE, manager.tryAcquire("order-1"));
        assertEquals(List.of(), lock.acquiredKeys, "a closed manager must not take a lock nobody would renew");
    }

    @Test
    @DisplayName("release removes the handle and releases at the backend")
    void releaseRemovesAndCallsBackend() {
        var lock = new RecordingLock();
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        var handle = manager.acquire("order-1").handle();

        manager.release("order-1", handle);

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
    void closeStopsRenewer() {
        var lock = new RecordingLock();
        var renewer = new AtomicReference<LoopSupervisor>();
        manager = new WorkflowInstanceLockManager(lock, "svc", WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                SHORT_TTL, SHORT_RENEW, EngineObserver.NOOP, (spec, body, observer) -> {
                    var started = LoopStarter.DEFAULT.start(spec, body, observer);
                    renewer.set(started);
                    return started;
                });
        manager.tryAcquire("order-1");
        await().atMost(Duration.ofSeconds(2)).until(() -> lock.renewAttempts.get() >= 1);

        manager.close();
        var countAtClose = lock.renewAttempts.get();

        // Wait on the renewer's thread itself, not on a sleep (Issue 92): once
        // it has ended no further pass can start, so the count below is final.
        assertTrue(renewer.get().awaitStopped(Duration.ofSeconds(5)),
                "the renewer's thread must end after close");
        assertTrue(lock.renewAttempts.get() <= countAtClose + 1,
                "renewer must stop after close (at most one in-flight cycle)");
    }

    // ── Batched renewal (renewAll) ─────────────────────────────────────

    private static final Duration BATCH_TTL = Duration.ofSeconds(5);
    private static final Duration BATCH_RENEW = Duration.ofMillis(200);

    private BatchRecordingLock holdFifty(BatchRecordingLock lock) {
        manager = new WorkflowInstanceLockManager(lock, "svc", BATCH_TTL, BATCH_RENEW);
        for (int i = 0; i < 50; i++) {
            assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("wf-" + i));
        }
        return lock;
    }

    private int heldCount() {
        var held = 0;
        for (int i = 0; i < 50; i++) {
            if (manager.isHeld("wf-" + i)) {
                held++;
            }
        }
        return held;
    }

    @Test
    @DisplayName("a renewal pass makes one renewAll call for every held lock, not one renew per lock")
    void oneRenewAllCallPerPassNotOnePerLock() {
        var lock = holdFifty(new BatchRecordingLock());

        // Any backend call means the first pass has begun.
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.backendCalls() >= 1);

        assertEquals(1, lock.renewAllCalls.get(), "one pass must be exactly one renewAll call");
        assertEquals(0, lock.renewCalls.get(), "the per-lock renew must not be used by the pass");
        assertEquals(50, lock.renewAllSizes.getFirst(), "the one call must carry every held handle");
    }

    @Test
    @DisplayName("a handle renewAll reports lost stands only its own workflow down")
    void lostHandleStandsOnlyThatWorkflowDown() {
        var lock = new BatchRecordingLock();
        lock.lostKey.set("maestro:lock:workflow:wf-7");
        holdFifty(lock);
        var lost = new CopyOnWriteArrayList<String>();
        manager.onLockLost((workflowId, _) -> lost.add(workflowId));

        // The renewer is one thread: a second backend call means the first
        // pass's result has been fully processed.
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.backendCalls() >= 2);

        assertEquals(List.of("wf-7"), List.copyOf(lost));
        assertFalse(manager.isHeld("wf-7"));
        assertEquals(49, heldCount());
    }

    @Test
    @DisplayName("a renewAll backend failure loses nothing and the next pass tries again")
    void backendFailureLosesNothing() {
        var lock = new BatchRecordingLock();
        lock.throwOnRenewAll.set(true);
        holdFifty(lock);
        var lost = new CopyOnWriteArrayList<String>();
        manager.onLockLost((workflowId, _) -> lost.add(workflowId));

        await().atMost(Duration.ofSeconds(5)).until(() -> lock.backendCalls() >= 2);

        assertTrue(lost.isEmpty(), "a backend failure must not be read as lost ownership: " + lost);
        assertEquals(50, heldCount());
        assertTrue(lock.renewAllCalls.get() >= 2, "the next pass must call renewAll again, calls="
                + lock.renewAllCalls.get());
        assertEquals(0, lock.renewCalls.get());
    }

    /**
     * Issue 68, part 2: an observer that throws an {@code Error} from
     * {@code instanceLockLost} is contained per lost handle, so every handle a
     * pass reports lost is dropped and its run stood down in that same pass,
     * not one renew interval apart.
     */
    @Test
    @DisplayName("an observer Error on one lost lock does not delay the others")
    void anObserverErrorOnOneLostLockDoesNotDelayTheOthers() {
        var lock = new BatchRecordingLock();
        lock.lostKeys.addAll(Set.of("maestro:lock:workflow:wf-3", "maestro:lock:workflow:wf-7",
                "maestro:lock:workflow:wf-9"));
        var observer = new io.b2mash.maestro.core.observe.EngineObserver() {
            @Override
            public void instanceLockLost(String workflowId) {
                throw new AssertionError("observer bug");
            }
        };
        // The renewer's passes are held back before they take their snapshot
        // until setup is done (PR #74 review), and the callback is installed
        // before any lock is held: pass 1 then sees all 50 handles and the
        // callback, however slowly this thread sets them up.
        var setupDone = new CountDownLatch(1);
        manager = new WorkflowInstanceLockManager(lock, "svc", DEFAULT_PREFIX, BATCH_TTL, BATCH_RENEW, observer,
                (spec, body, obs) -> io.b2mash.maestro.core.loop.LoopSupervisor.start(spec, ctx -> {
                    assertTrue(setupDone.await(10, TimeUnit.SECONDS), "setup finished");
                    return body.iterate(ctx);
                }, obs));
        var passOfEachLoss = new ConcurrentHashMap<String, Integer>();
        manager.onLockLost((workflowId, _) -> passOfEachLoss.put(workflowId, lock.renewAllCalls.get()));
        for (int i = 0; i < 50; i++) {
            assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("wf-" + i));
        }
        setupDone.countDown();

        // The renewer is one thread: a second renewAll means the first pass's
        // result has been fully processed.
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.renewAllCalls.get() >= 2);

        assertEquals(Map.of("wf-3", 1, "wf-7", 1, "wf-9", 1), Map.copyOf(passOfEachLoss),
                "every lost handle was handled by the first pass");
        assertEquals(47, heldCount());
    }

    /**
     * The stand-down is the safety action, the observer only a report: by the
     * time an observer hears of a lost lock, the run that held it has already
     * been told to stand down. (Seen as a 1-in-20 flake of
     * {@code HandleScopedLockRaceTest}: a run that waited for the observer's
     * report could start before its stand-down was requested.)
     */
    @Test
    @DisplayName("a lost lock stands its run down before the observer is told")
    void theRunIsStoodDownBeforeTheObserverHearsOfTheLoss() {
        var lock = new BatchRecordingLock();
        lock.lostKey.set("maestro:lock:workflow:wf-1");
        var stoodDown = new java.util.concurrent.atomic.AtomicBoolean();
        var stoodDownWhenObserved = new AtomicReference<Boolean>();
        var observer = new io.b2mash.maestro.core.observe.EngineObserver() {
            @Override
            public void instanceLockLost(String workflowId) {
                stoodDownWhenObserved.compareAndSet(null, stoodDown.get());
            }
        };
        manager = new WorkflowInstanceLockManager(lock, "svc", WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                BATCH_TTL, BATCH_RENEW, observer);
        manager.onLockLost((workflowId, _) -> stoodDown.set(true));   // before the renewer can report
        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));

        await().atMost(Duration.ofSeconds(5)).until(() -> stoodDownWhenObserved.get() != null);

        assertEquals(Boolean.TRUE, stoodDownWhenObserved.get(), "the observer heard of the loss before the stand-down");
    }

    private static final String DEFAULT_PREFIX = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX;

    // ── heldElsewhere ──────────────────────────────────────────────────

    @Test
    @DisplayName("heldElsewhere asks heldKeys with the acquire key format and maps held keys back to workflow ids")
    void heldElsewhereMapsHeldKeysBackToWorkflowIds() {
        var lock = new RecordingLock();
        lock.heldKeysAnswer = keys -> Set.of("acme:locks:workflow:wf-b");
        manager = new WorkflowInstanceLockManager(lock, "svc", "acme:locks:", SHORT_TTL, SHORT_RENEW);

        var held = manager.heldElsewhere(List.of("wf-a", "wf-b"));

        assertEquals(Set.of("wf-b"), held);
        assertEquals(List.of(Set.of("acme:locks:workflow:wf-a", "acme:locks:workflow:wf-b")),
                lock.heldKeysAsked, "one heldKeys call, keyed exactly as tryAcquire keys the lock");
        // The same derivation tryAcquire uses — not a re-derived format.
        manager.tryAcquire("wf-a");
        assertEquals("acme:locks:workflow:wf-a", lock.acquiredKeys.getFirst());
    }

    @Test
    @DisplayName("heldElsewhere never reports a lock this node holds")
    void heldElsewhereExcludesLocksThisNodeHolds() {
        var lock = new RecordingLock();
        lock.heldKeysAnswer = keys -> Set.copyOf(keys); // the backend sees every key as held
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        assertEquals(Acquisition.ACQUIRED, manager.tryAcquire("own-1"));

        var held = manager.heldElsewhere(List.of("own-1", "other-1"));

        assertEquals(Set.of("other-1"), held, "own-1 is held HERE, not elsewhere");
    }

    @Test
    @DisplayName("heldElsewhere is empty — 'unknown, try them all' — when the backend throws")
    void heldElsewhereIsEmptyWhenTheBackendThrows() {
        var lock = new RecordingLock();
        lock.heldKeysAnswer = keys -> {
            throw new LockBackendException("Simulated backend failure", "batch", new RuntimeException());
        };
        manager = new WorkflowInstanceLockManager(lock, "svc", SHORT_TTL, SHORT_RENEW);
        assertEquals(Set.of(), manager.heldElsewhere(List.of("wf-a")));
        assertEquals(1, lock.heldKeysAsked.size(), "the backend was asked, and its failure contained");
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
        final CopyOnWriteArrayList<Set<String>> heldKeysAsked = new CopyOnWriteArrayList<>();
        volatile Function<Collection<String>, Set<String>> heldKeysAnswer = _ -> Set.of();

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            heldKeysAsked.add(Set.copyOf(keys));
            return heldKeysAnswer.apply(keys);
        }

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

    /**
     * Records renew and renewAll calls separately; renewAll reports the handle
     * whose key is {@link #lostKey} as lost, or throws a
     * {@link LockBackendException} while {@link #throwOnRenewAll} is set.
     */
    private static final class BatchRecordingLock implements DistributedLock {

        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger renewAllCalls = new AtomicInteger();
        final CopyOnWriteArrayList<Integer> renewAllSizes = new CopyOnWriteArrayList<>();
        final AtomicReference<String> lostKey = new AtomicReference<>("");
        /** Further keys renewAll reports lost, every pass. */
        final Set<String> lostKeys = ConcurrentHashMap.newKeySet();
        final AtomicBoolean throwOnRenewAll = new AtomicBoolean(false);

        int backendCalls() {
            return renewCalls.get() + renewAllCalls.get();
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            renewCalls.incrementAndGet();
            return true;
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            renewAllCalls.incrementAndGet();
            renewAllSizes.add(handles.size());
            if (throwOnRenewAll.get()) {
                throw new LockBackendException("Simulated backend failure", "batch", new RuntimeException());
            }
            var lost = new HashSet<LockHandle>();
            for (var handle : handles) {
                if (handle.key().equals(lostKey.get()) || lostKeys.contains(handle.key())) {
                    lost.add(handle);
                }
            }
            return lost;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
