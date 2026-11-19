package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.event.Level;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 64, the renewer half (WP5 D-8): {@link WorkflowInstanceLockManager#close(Duration)}
 * never interrupts the renewer — a renewal in flight is a JDBC call, and an
 * interrupt on a virtual thread closes its socket and fails the pass — and a
 * pass that fails once the manager is closed counts no renewal failure, so a
 * routine shutdown fires no alert. A latch ends the wait between passes, and
 * the join is bounded.
 *
 * <p>Class-level timeout: a mutation that joins without a bound would
 * otherwise hang the class; the timeout interrupts the joining test thread.
 */
@DisplayName("WorkflowInstanceLockManager.close(Duration)")
@Timeout(10)
class WorkflowInstanceLockManagerCloseTest {

    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final BlockingRenewLock lock = new BlockingRenewLock();
    private WorkflowInstanceLockManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkflowInstanceLockManager(lock, "svc", "maestro:lock:",
                Duration.ofMillis(300), Duration.ofMillis(100), observer);
        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));
    }

    @AfterEach
    void tearDown() {
        lock.release.countDown();
        manager.close();
    }

    @Test
    void closeDoesNotInterruptARenewalInFlight() throws Exception {
        lock.blockNextRenew();
        assertTrue(lock.inRenew.await(5, SECONDS));
        manager.close(Duration.ofMillis(200));
        lock.release.countDown();
        lock.awaitBlockedRenewerEnded();
        assertFalse(lock.renewInterrupted.get(), "a renewal in flight is a JDBC call: it must not be interrupted");
    }

    @Test
    void aRenewalThatFailsAfterCloseCountsNoRenewalFailure() throws Exception {
        lock.blockNextRenew();
        lock.failAfterRelease = true;
        assertTrue(lock.inRenew.await(5, SECONDS));
        manager.close(Duration.ofMillis(200));
        lock.release.countDown();
        // Joins the renewer thread itself: isRenewerRunning() reads false as
        // soon as close() runs, before the released renewal has failed.
        lock.awaitBlockedRenewerEnded();
        assertFalse(manager.isRenewerRunning());
        assertEquals(List.of(), observer.lockRenewFailed(), "a routine shutdown must not fire renewal-failure alerts");
    }

    /**
     * Review c2 (coordinator ruling): a loss the renewer learns of after close
     * — a renewal stuck past the join that later returns "lost" — still
     * stands the run down (a run still executing must be fenced), but a
     * routine deploy fires no alert: INFO, not ERROR, and no
     * {@code instanceLockLost} to the observer.
     */
    @Test
    void aLockLostAfterCloseStandsTheRunDownWithoutAlerting() throws Exception {
        var stoodDown = new CopyOnWriteArrayList<String>();
        manager.onLockLost((workflowId, _) -> stoodDown.add(workflowId));
        lock.blockNextRenew();
        lock.lostAfterRelease = true;
        assertTrue(lock.inRenew.await(5, SECONDS));
        try (var logs = CapturingSlf4jProvider.watch(WorkflowInstanceLockManager.class)) {
            manager.close(Duration.ofMillis(200));
            lock.release.countDown();
            lock.awaitBlockedRenewerEnded();

            assertEquals(List.of("wf-1"), stoodDown, "the run is still stood down");
            assertEquals(List.of(), observer.lockLost(), "a routine shutdown must not fire lost-lock alerts");
            assertTrue(logs.records().stream().noneMatch(r -> r.level() == Level.ERROR), logs.records().toString());
            assertTrue(logs.records().stream().anyMatch(r -> r.level() == Level.INFO
                    && r.message().startsWith("Instance lock for workflow 'wf-1' was lost after shutdown began")),
                    logs.records().toString());
        }
    }

    @Test
    void closeReturnsWithinItsBoundWhileARenewalIsStuck() throws Exception {
        lock.blockNextRenew();
        assertTrue(lock.inRenew.await(5, SECONDS));
        var started = System.nanoTime();
        manager.close(Duration.ofMillis(200));
        var took = Duration.ofNanos(System.nanoTime() - started);
        lock.release.countDown();
        assertTrue(took.compareTo(Duration.ofSeconds(1)) < 0, "close(200 ms) took " + took.toMillis() + " ms");
    }

    @Test
    void closeEndsTheWaitBetweenPassesAtOnce() {
        var slow = new WorkflowInstanceLockManager(lock, "svc", "maestro:lock:",
                Duration.ofSeconds(30), Duration.ofSeconds(10), observer);
        try {
            slow.tryAcquire("wf-slow");
            await().atMost(5, SECONDS).until(slow::isRenewerRunning);
            var started = System.nanoTime();
            slow.close(Duration.ofSeconds(2));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);
            assertFalse(slow.isRenewerRunning());
        } finally {
            slow.close();
        }
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** Minimal in-memory {@link DistributedLock}; thread-safe. */
    static class MapLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        boolean isHeld(String key) {
            return locks.containsKey(key);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    /**
     * A map lock whose next {@code renewAll}, once armed by
     * {@link #blockNextRenew()}, counts down {@link #inRenew} and waits on
     * {@link #release} the way a JDBC call waits on its socket: an interrupt
     * ends it with a {@link LockBackendException} and sets
     * {@link #renewInterrupted}. With {@link #failAfterRelease} the released
     * call throws too. The thread that made the blocked call is kept, so a
     * test can join it.
     */
    static final class BlockingRenewLock extends MapLock {
        final CountDownLatch inRenew = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean renewInterrupted = new AtomicBoolean();
        volatile boolean failAfterRelease;
        /** With this set, the released call reports every handle lost. */
        volatile boolean lostAfterRelease;
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile @Nullable Thread blockedRenewer;

        void blockNextRenew() {
            armed.set(true);
        }

        /** Waits (bounded) until the thread whose renewal was blocked has ended. */
        void awaitBlockedRenewerEnded() throws InterruptedException {
            var thread = blockedRenewer;
            assertNotNull(thread, "no renewal was blocked");
            assertTrue(thread.join(Duration.ofSeconds(5)), "the renewer ended once its renewal returned");
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            if (armed.compareAndSet(true, false)) {
                blockedRenewer = Thread.currentThread();
                inRenew.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    renewInterrupted.set(true);
                    throw new LockBackendException("renewAll interrupted", "renewAll", e);
                }
                if (failAfterRelease) {
                    throw new LockBackendException("renewAll failed", "renewAll",
                            new IllegalStateException("the backend failed after the release"));
                }
                if (lostAfterRelease) {
                    return Set.copyOf(handles);
                }
            }
            return super.renewAll(handles, ttl);
        }
    }
}
