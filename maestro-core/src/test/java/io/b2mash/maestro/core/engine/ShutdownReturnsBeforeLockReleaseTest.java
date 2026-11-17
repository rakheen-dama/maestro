package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.event.Level;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 87: {@code WorkflowExecutor.shutdown()} must not return while a run it
 * was draining still holds its instance lock.
 *
 * <p>A run's {@code finally} removes it from {@code runningWorkflows} and only
 * then releases its instance lock. A drain that looked only at
 * {@code runningWorkflows} missed a run already removed, so when it started
 * inside that gap {@code shutdown()} returned with the lock still held, and a
 * peer's recovery pass that ran at once skipped the workflow as "locked by
 * another node". Production re-polls; the test kit's {@code recover()} is one
 * ungated pass, so the workflow was never adopted (the CI failure of
 * {@code DeterminismCheckerRecoverableTest} on PR #64: "did not finish within
 * PT10S ... (status: COMPENSATING)").
 *
 * <p>Deterministic: the lock's release blocks until the test opens it, and
 * the drain starts only once the run is inside that release, so past its
 * removal from the map.
 */
@Timeout(60)
class ShutdownReturnsBeforeLockReleaseTest {

    private final RecoveryCapacityFixtures.CountingStore store = new RecoveryCapacityFixtures.CountingStore();
    private final GatedReleaseLock lock = new GatedReleaseLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final WorkflowExecutor a = executor(lock, Duration.ofSeconds(5));
    private final WorkflowExecutor b = executor(lock, Duration.ofSeconds(5));

    @AfterEach
    void tearDown() {
        lock.open.countDown();
        a.shutdown();
        b.shutdown();
    }

    @Test
    void shutdownReturnsOnlyOnceEveryDrainedRunHasReleasedItsInstanceLock() throws Exception {
        a.startWorkflow("wf-1", "ParkWorkflow", "default", null, ParkWorkflow::new, parkMethod());
        awaitParked("wf-1");

        // The parked run is abandoned at shutdown's step 3; it then removes
        // itself from runningWorkflows and blocks in its lock release. The
        // drain starts only once it is there; the release is let go 300 ms later.
        lock.gateNextRelease();
        a.beforeShutdownDrain = () -> {
            awaitOrFail(lock.inRelease, "the run reached its lock release");
            openLater(lock.open);
        };

        a.shutdown();

        assertEquals(0, lock.heldCount(),
                "shutdown() returned while a run it drained still held its instance lock");
        var registrations = Map.of("ParkWorkflow",
                new WorkflowRegistration("ParkWorkflow", "default", ParkWorkflow::new, parkMethod()));
        assertEquals(1, b.recoverWorkflows(registrations), "a peer's recovery pass adopts the abandoned run");
    }

    /**
     * A launch that fails between registering its run and starting its thread
     * releases the lock on the launching thread, after it has unregistered the
     * run. A drain that starts inside that release still waits for it.
     */
    @Test
    void aFailedLaunchStillInsideItsLockReleaseIsWaitedFor() throws Exception {
        a.beforeRunThreadStart = () -> {
            throw new IllegalStateException("launch fails before its thread starts");
        };
        lock.gateNextRelease();
        var launchFailure = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                a.startWorkflow("wf-launch", "ParkWorkflow", "default", null, ParkWorkflow::new, parkMethod());
            } catch (RuntimeException | Error e) {
                launchFailure.set(e);
            }
        });
        awaitOrFail(lock.inRelease, "the failed launch reached its lock release");
        a.beforeShutdownDrain = () -> openLater(lock.open);

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            a.shutdown();

            assertEquals(0, lock.heldCount(),
                    "shutdown() returned while a failed launch still held its instance lock");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("1 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(IllegalStateException.class, launchFailure.get());
    }

    /**
     * A failed launch whose cleanup step throws an {@code Error} still
     * releases the lock, stops being tracked and lets a drain waiting for it
     * go: a later shutdown sees nothing in flight and does not wait out its
     * deadline. The caller still sees the launch's own failure.
     */
    @Test
    void aFailedLaunchWhoseCleanupThrowsLeavesNoGhostRun() throws Exception {
        var launchFailure = new IllegalStateException("launch fails before its thread starts");
        a.beforeRunThreadStart = () -> {
            throw launchFailure;
        };
        var cleanupFailure = new ReleaseFailedError();
        a.duringFailedLaunchCleanup = () -> {
            throw cleanupFailure;
        };

        var thrown = assertThrows(Throwable.class, () -> a.startWorkflow("wf-ghost", "ParkWorkflow", "default", null,
                ParkWorkflow::new, parkMethod()));

        assertSame(launchFailure, thrown, "the caller sees the launch's own failure");
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure), List.of(thrown.getSuppressed()).toString());
        assertEquals(0, lock.heldCount(), "a throwing cleanup step must not skip the lock release");
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var start = System.nanoTime();
            a.shutdown();
            var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(tookMs < 2_000, "the drain waited " + tookMs + " ms for a launch that already failed");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("0 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
    }

    /**
     * A run whose thread dies before its body unregisters itself and releases
     * its lock on that thread; a drain that starts inside that release still
     * waits for it.
     */
    @Test
    void aRunThreadThatDiedBeforeItsBodyStillInsideItsLockReleaseIsWaitedFor() throws Exception {
        a.beforeRunBody = () -> {
            throw new IllegalStateException("run thread dies before its body");
        };
        lock.gateNextRelease();
        a.startWorkflow("wf-died", "ParkWorkflow", "default", null, ParkWorkflow::new, parkMethod());
        awaitOrFail(lock.inRelease, "the dying run thread reached its lock release");
        a.beforeShutdownDrain = () -> openLater(lock.open);

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            a.shutdown();

            assertEquals(0, lock.heldCount(),
                    "shutdown() returned while a run thread that died before its body still held its lock");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("1 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
    }

    /**
     * A lock release that throws an {@code Error} kills the run's thread in
     * its {@code finally}; the run is still no longer tracked, so a later
     * shutdown counts it neither in flight nor still running, and the drain
     * completes at once for a run whose release throws during it.
     */
    @Test
    void aLockReleaseThatThrowsLeavesNoRunTrackedAndTheDrainCompletes() throws Exception {
        lock.failReleases = true;
        a.startWorkflow("wf-done", "QuickWorkflow", "default", null, QuickWorkflow::new, quickMethod());
        assertEquals(WorkflowStatus.COMPLETED,
                valueWithin(() -> store.getInstance("wf-done").map(i -> i.status()).orElse(null),
                        WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        // wf-done's release follows its COMPLETED write: wait until it has thrown.
        assertEquals(1, valueWithin(lock.releaseFailures::get, 1, Duration.ofSeconds(5)));
        a.startWorkflow("wf-parked", "ParkWorkflow", "default", null, ParkWorkflow::new, parkMethod());
        awaitParked("wf-parked");

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var start = System.nanoTime();
            a.shutdown();
            var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(tookMs < 4_000, "the drain waited " + tookMs + " ms for a run whose release threw");
            var summary = logs.records().stream()
                    .filter(r -> r.message().startsWith("Maestro shutdown ")
                            && !r.message().startsWith("Maestro shutdown started"))
                    .toList();
            assertEquals(1, summary.size(), logs.records().toString());
            assertEquals(Level.INFO, summary.getFirst().level(), summary.getFirst().message());
            assertTrue(summary.getFirst().message().contains("1 run(s) in flight at start, 0 still running"),
                    summary.getFirst().message());
        }
        assertEquals(2, lock.releaseFailures.get(), "the parked run's release threw too");
    }

    /**
     * A run stuck in its lock release at the deadline: shutdown returns at the
     * deadline, not before, and its {@code WARN} names the run.
     */
    @Test
    void aRunStuckInItsLockReleaseAtTheDeadlineIsNamedInTheWarn() throws Exception {
        var shortDeadline = executor(lock, Duration.ofMillis(300));
        try {
            shortDeadline.startWorkflow("wf-stuck", "ParkWorkflow", "default", null, ParkWorkflow::new,
                    parkMethod());
            awaitParked("wf-stuck");
            lock.gateNextRelease();
            shortDeadline.beforeShutdownDrain = () -> awaitOrFail(lock.inRelease, "the run reached its lock release");

            try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
                var start = System.nanoTime();
                shortDeadline.shutdown();
                var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                assertTrue(tookMs >= 250 && tookMs < 5_000, "shutdown took " + tookMs + " ms");
                var warn = logs.records().stream()
                        .filter(r -> r.message().startsWith("Maestro shutdown hit its 300 ms deadline")).toList();
                assertEquals(1, warn.size(), logs.records().toString());
                assertTrue(warn.getFirst().message().contains("1 of 1 run(s) still running [wf-stuck]"),
                        warn.getFirst().message());
            }
            assertEquals(1, lock.heldCount(), "the release is still blocked: nothing let it go");
        } finally {
            lock.open.countDown();
            shortDeadline.shutdown();
        }
    }

    /**
     * Many runs leaving at once while shutdown drains: in round {@code k} the
     * drain starts only once {@code k} of them are inside their lock release
     * (so out of {@code runningWorkflows}), the rest still on their way, and
     * every release is let go together. Every round, {@code shutdown()}
     * returns only once every lock is released and reports nothing still running.
     */
    @Test
    void manyRunsFinishingConcurrentlyWithTheDrainAllReleaseBeforeShutdownReturns() throws Exception {
        var runs = 12;
        for (var inReleaseBeforeDrain = 0; inReleaseBeforeDrain <= runs; inReleaseBeforeDrain++) {
            var roundLock = new HeldReleaseLock();
            var executor = executor(roundLock, Duration.ofSeconds(10));
            try {
                for (var i = 0; i < runs; i++) {
                    executor.startWorkflow("wf-%d-%d".formatted(inReleaseBeforeDrain, i), "ParkWorkflow", "default",
                            null, ParkWorkflow::new, parkMethod());
                }
                for (var i = 0; i < runs; i++) {
                    awaitParked("wf-%d-%d".formatted(inReleaseBeforeDrain, i));
                }
                var threshold = inReleaseBeforeDrain;
                executor.beforeShutdownDrain = () -> {
                    assertEquals(threshold, valueWithin(
                            () -> Math.min(roundLock.inRelease.get(), threshold), threshold, Duration.ofSeconds(5)));
                    Thread.ofVirtual().start(roundLock.open::countDown);
                };

                try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
                    executor.shutdown();

                    assertEquals(0, roundLock.heldCount(), "round " + threshold
                            + ": shutdown() returned while a run still held its instance lock");
                    assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith(
                                    "Maestro shutdown finished in") && r.message().contains("0 still running")),
                            "round " + threshold + ": " + logs.records());
                }
            } finally {
                roundLock.open.countDown();
                executor.shutdown();
            }
        }
    }

    private void awaitParked(String workflowId) {
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                valueWithin(() -> store.getInstance(workflowId).map(i -> i.status()).orElse(null),
                        WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5)));
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opens {@code gate} 300 ms from now, letting {@code shutdown()} run ahead of it as on a loaded runner. */
    private static void openLater(CountDownLatch gate) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
    }

    private WorkflowExecutor executor(DistributedLock distributedLock, Duration shutdownTimeout) {
        return WorkflowExecutor.builder(store, serializer, SERVICE)
                .lifecycleEventsEnabled(false)
                .distributedLock(distributedLock)
                .shutdownTimeout(shutdownTimeout)
                .build();
    }

    private static Method parkMethod() {
        try {
            return ParkWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method quickMethod() {
        try {
            return QuickWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parks on a signal that never comes. */
    @DurableWorkflow(name = "ParkWorkflow")
    public static final class ParkWorkflow {
        /** @return the signal's payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
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

    /** Thrown by {@link GatedReleaseLock#release} while {@link GatedReleaseLock#failReleases} is set. */
    static final class ReleaseFailedError extends Error {
        ReleaseFailedError() {
            super("lock backend failed in release (test)");
        }
    }

    /**
     * A map-backed lock whose next release blocks until {@link #open} is
     * counted down, and whose releases throw an {@code Error} (the lock
     * manager contains only {@code Exception}s) while {@link #failReleases}
     * is set.
     */
    static final class GatedReleaseLock implements DistributedLock {
        private final ConcurrentHashMap<String, LockHandle> held = new ConcurrentHashMap<>();
        private volatile boolean gated;
        volatile boolean failReleases;
        final AtomicInteger releaseFailures = new AtomicInteger();
        final CountDownLatch inRelease = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);

        void gateNextRelease() {
            gated = true;
        }

        int heldCount() {
            return held.size();
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return held.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            if (failReleases) {
                releaseFailures.incrementAndGet();
                throw new ReleaseFailedError();
            }
            if (gated) {
                gated = false;
                inRelease.countDown();
                try {
                    open.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            held.remove(handle.key(), handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return handle.equals(held.get(handle.key()));
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return keys.stream().filter(held::containsKey).collect(Collectors.toSet());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    /** A map-backed lock whose every release counts itself in and blocks until {@link #open} is counted down. */
    static final class HeldReleaseLock implements DistributedLock {
        private final ConcurrentHashMap<String, LockHandle> held = new ConcurrentHashMap<>();
        final AtomicInteger inRelease = new AtomicInteger();
        final CountDownLatch open = new CountDownLatch(1);

        int heldCount() {
            return held.size();
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return held.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            inRelease.incrementAndGet();
            try {
                open.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            held.remove(handle.key(), handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return handle.equals(held.get(handle.key()));
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return keys.stream().filter(held::containsKey).collect(Collectors.toSet());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }
}
