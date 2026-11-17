package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP4 3.5b: an {@code Error} thrown inside one iteration of a background loop
 * no longer ends the loop — a later iteration runs and does its work — unless
 * it is a {@code MaestroControlFlowError}, which still ends it.
 *
 * <p>Each loop runs against a {@link PollFaultStore} whose first call of the
 * loop's store method throws the injected throwable and whose later calls
 * succeed; the assertions are on the positive fact that a later pass ran.
 */
class BackgroundLoopErrorTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final PollFaultStore store = new PollFaultStore();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, "svc")
            .lifecycleEventsEnabled(false)
            .observer(observer)
            .build();

    private final FaultyRenewLock lock = new FaultyRenewLock();
    private final WorkflowInstanceLockManager manager = new WorkflowInstanceLockManager(
            lock, "svc", "maestro:lock:", Duration.ofSeconds(30), Duration.ofMillis(50), observer);

    @AfterEach
    void tearDown() {
        manager.close();
        executor.shutdown();
    }

    static Stream<Named<Supplier<Error>>> survivableErrors() {
        return Stream.of(
                Named.of("OutOfMemoryError", OutOfMemoryError::new),
                Named.of("StackOverflowError", StackOverflowError::new),
                Named.of("NoClassDefFoundError", () -> new NoClassDefFoundError("x")),
                Named.of("AssertionError", () -> new AssertionError("x")));
    }

    // ── Timer poller ──────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void timerPoller_anErrorFromAPoll_isContainedAndTheNextPollFiresTheTimer(Supplier<Error> error) {
        store.failure = error;
        var row = dueTimer(store, "wf-timer", "sleep-1");                 // PENDING, fire_at = now - 1 s
        executor.startTimerPoller(Duration.ofMillis(50), 10);
        assertEquals(TimerStatus.FIRED, statusWithin(store, row, TimerStatus.FIRED, Duration.ofSeconds(5)));
        assertTrue(executor.isTimerPollerRunning());
    }

    @Test
    void timerPoller_aControlFlowErrorEndsTheLoop() {
        store.failure = () -> new ExecutorShutdownException("test");
        executor.startTimerPoller(Duration.ofMillis(50), 10);
        await("the timer poller thread to end").atMost(Duration.ofSeconds(5))
                .until(() -> !executor.isTimerPollerRunning());
        assertEquals(1, store.dueTimerPolls.get(), "the guard rethrew it; the loop did not poll again");
    }

    @Test
    void timerPoller_aLeaderElectionFailureGoesThroughTheGuardAndTheSkewCheckStillRuns() {
        var poller = new TimerPoller(store, executor, new ThrowingLeaderLock(), "svc", Duration.ofMillis(50), 10,
                observer);
        poller.start();
        try {
            await("two consecutive failed elections").atMost(Duration.ofSeconds(5))
                    .until(() -> poller.consecutiveFailures() >= 2);
            assertEquals(1, store.clockReads.get(), "the skew check ran on the first iteration although isLeader threw");
            assertTrue(poller.isRunning());
        } finally {
            poller.stop();
        }
    }

    /** Shutdown during a failing streak: stop() lands in the failure arm's wait and ends the loop at once. */
    @Test
    void timerPoller_stopDuringAFailingStreak_endsTheLoopPromptly() {
        var poller = new TimerPoller(store, executor, new ThrowingLeaderLock(), "svc", Duration.ofMinutes(10), 10,
                observer);
        poller.start();
        await("the first failed election").atMost(Duration.ofSeconds(5))
                .until(() -> poller.consecutiveFailures() >= 1);

        var started = System.nanoTime();
        poller.stop();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "stop() must not wait out its 5 s join (the loop ended): took " + elapsed);
        assertFalse(poller.isRunning());
    }

    /**
     * Shutdown while a store call swallows the interrupt and then throws: the
     * guard sees the loop stopping, does not count or report the failure, and
     * the loop ends instead of sleeping a full interval.
     */
    @Test
    void timerPoller_stopWhileAStoreCallSwallowsTheInterruptAndThrows_endsPromptlyAndUncounted() throws Exception {
        var entered = new CountDownLatch(1);
        var swallowing = new PollFaultStore() {
            @Override
            public List<WorkflowTimer> getDueTimers(int batchSize) {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();           // until stop() interrupts
                } catch (InterruptedException swallowed) {
                    // a driver that swallows the interrupt (flag cleared) and then fails
                }
                throw new AssertionError("interrupted store call");
            }
        };
        var poller = new TimerPoller(swallowing, executor, null, "svc", Duration.ofMinutes(10), 10, observer);
        poller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the poller never reached the store");

        var started = System.nanoTime();
        poller.stop();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "stop() must not wait out its 5 s join (the loop ended): took " + elapsed);
        assertEquals(0, poller.consecutiveFailures(), "a failure while stopping is not counted");
        assertEquals(List.of(), observer.loopErrors(), "a failure while stopping is not reported");
    }

    // ── Recovery poller ───────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void recoveryPoller_anErrorFromAPass_isContainedAndTheNextPassRuns(Supplier<Error> error) throws Exception {
        store.failure = error;
        executor.startRecoveryPoller(Map.of(), Duration.ofMillis(50));
        assertTrue(store.secondRecoveryPass.await(5, TimeUnit.SECONDS), "the recovery poller made no second pass");
        assertTrue(executor.isRecoveryPollerRunning());
    }

    // ── Signal janitor ────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void signalJanitor_anErrorFromASweep_isContainedAndTheNextSweepRuns(Supplier<Error> error) throws Exception {
        store.failure = error;
        executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(1));
        assertTrue(store.secondJanitorSweep.await(5, TimeUnit.SECONDS), "the janitor made no second sweep");
        assertTrue(executor.isSignalJanitorRunning());
    }

    @Test
    void anErrorIsCountedForItsLoop() throws Exception {
        store.failure = () -> new AssertionError("x");
        executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(1));
        assertTrue(store.secondJanitorSweep.await(5, TimeUnit.SECONDS));
        assertEquals(new RecordingEngineObserver.LoopErrorCall("signal-janitor", "java.lang.AssertionError"),
                observer.loopErrors().getFirst());
    }

    /** Shutdown during a failing streak: stop() lands in the janitor's wait and ends the loop at once. */
    @Test
    void signalJanitor_stopDuringAFailingStreak_endsTheLoopPromptly() {
        var failing = new PollFaultStore() {
            @Override
            public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
                throw new AssertionError("every sweep fails");
            }
        };
        var janitor = new SignalJanitor(failing, Duration.ofMinutes(10), Duration.ofDays(1), observer);
        janitor.start();
        await("the first failed sweep").atMost(Duration.ofSeconds(5))
                .until(() -> janitor.consecutiveFailures() >= 1);

        var started = System.nanoTime();
        janitor.stop();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "stop() must not wait out its 5 s join (the loop ended): took " + elapsed);
        assertFalse(janitor.isRunning());
    }

    // ── Retention sweeper ─────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void retentionSweeper_anErrorFromASweep_isContainedAndTheNextSweepRuns(Supplier<Error> error) throws Exception {
        store.failure = error;
        executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(Duration.ofMillis(50),
                Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1));
        assertTrue(store.secondRetentionSweep.await(5, TimeUnit.SECONDS), "the sweeper made no second sweep");
        assertTrue(executor.isRetentionSweeperRunning());
    }

    /** Shutdown during a failing streak: stop() lands in the sweeper's wait and ends the loop at once. */
    @Test
    void retentionSweeper_stopDuringAFailingStreak_endsTheLoopPromptly() {
        var failing = new PollFaultStore() {
            @Override
            public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
                throw new AssertionError("every sweep fails");
            }
        };
        var sweeper = new RetentionSweeper(failing, null, "svc", new WorkflowExecutor.RetentionSettings(
                Duration.ofMinutes(10), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1), observer);
        sweeper.start();
        await("the first failed sweep").atMost(Duration.ofSeconds(5))
                .until(() -> sweeper.consecutiveFailures() >= 1);

        var started = System.nanoTime();
        sweeper.stop();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "stop() must not wait out its 5 s join (the loop ended): took " + elapsed);
        assertFalse(sweeper.isRunning());
    }

    // ── Instance-lock renewer ─────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void renewer_anErrorFromRenewAll_isContainedAndTheNextPassRenews(Supplier<Error> error) throws Exception {
        lock.answer = () -> { throw error.get(); };
        assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));
        assertTrue(lock.renewCalls.await(5, TimeUnit.SECONDS), "the renewer made no second pass");
        assertTrue(manager.isHeld("wf-1"), "an Error loses nothing: the handle is kept");
        assertTrue(manager.isRenewerRunning());
    }

    @Test
    void aControlFlowErrorStopsTheRenewer() {
        lock.answer = () -> { throw new ExecutorShutdownException("test"); };
        manager.tryAcquire("wf-1");
        await("the renewer thread to end").atMost(Duration.ofSeconds(5)).until(() -> !manager.isRenewerRunning());
        assertTrue(manager.hasRenewerStarted());
        assertEquals(1, lock.calls.get(), "the control-flow Error ended the renewer; it made no second pass");
    }

    /**
     * After an {@code Error}, a later pass still acts on what the backend
     * reports: a handle reported lost is dropped and the run is told to stand
     * down, exactly as without the {@code Error}.
     */
    @Test
    void renewer_aLossReportedByAPassAfterAnError_standsTheRunDown() throws Exception {
        var lost = new CountDownLatch(1);
        manager.onLockLost(workflowId -> lost.countDown());
        lock.alwaysFail = true;                                        // keep the answer until replaced below
        lock.answer = () -> {
            lock.answer = () -> Set.copyOf(lock.acquired);             // the second pass: every handle lost
            throw new AssertionError("x");
        };
        manager.tryAcquire("wf-1");
        assertTrue(lost.await(5, TimeUnit.SECONDS), "no pass after the Error reported the loss");
        assertFalse(manager.isHeld("wf-1"), "the lost handle is dropped");
    }

    @Test
    void aRenewalBackendFailureKeepsEveryHandleAndGoesThroughTheGuard() {
        lock.alwaysFail = true;
        lock.answer = () -> { throw new IllegalStateException("db down"); };
        manager.tryAcquire("wf-1");
        await().atMost(Duration.ofSeconds(5)).until(() -> manager.renewFailureStreak() >= 3);
        assertTrue(manager.isHeld("wf-1"), "nothing known, nothing lost");
        assertTrue(observer.lockRenewFailed().size() >= 3, "still one instanceLockRenewFailed per lock per failed pass");
    }

    @Test
    void aSuccessfulPassAfterFailuresEndsTheStreak() {
        lock.answer = () -> { throw new IllegalStateException("db down"); };
        manager.tryAcquire("wf-1");
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.calls.get() >= 2 && manager.renewFailureStreak() == 0);
        assertTrue(manager.isHeld("wf-1"));
    }

    /** An Error is counted for the renewer and reported under its loop name. */
    @Test
    void renewer_anErrorIsCountedAndReportedForTheRenewer() {
        lock.alwaysFail = true;
        lock.answer = () -> { throw new AssertionError("x"); };
        manager.tryAcquire("wf-1");
        await("two consecutive failed passes").atMost(Duration.ofSeconds(5))
                .until(() -> manager.renewFailureStreak() >= 2);
        assertEquals(new RecordingEngineObserver.LoopErrorCall("instance-lock-renewer", "java.lang.AssertionError"),
                observer.loopErrors().getFirst());
        assertTrue(manager.isHeld("wf-1"));
        assertTrue(manager.isRenewerRunning());
    }

    /** close() during a failing streak ends the renewer thread. */
    @Test
    void renewer_closeDuringAFailingStreak_endsTheRenewer() throws Exception {
        var thread = captureRenewerThread();
        lock.alwaysFail = true;
        lock.answer = () -> { throw new AssertionError("every pass fails"); };
        manager.tryAcquire("wf-1");
        await("two consecutive failed passes").atMost(Duration.ofSeconds(5))
                .until(() -> manager.renewFailureStreak() >= 2);

        manager.close();

        assertTrue(thread.get().join(Duration.ofSeconds(4)), "the renewer thread outlived close()");
        assertFalse(manager.isRenewerRunning());
    }

    /**
     * close() while renewAll swallows the interrupt and then throws: the guard
     * sees the renewer stopping, does not count or report the failure, and the
     * thread ends.
     */
    @Test
    void renewer_closeWhileRenewAllSwallowsTheInterruptAndThrows_endsUncounted() throws Exception {
        var thread = captureRenewerThread();
        var entered = new CountDownLatch(1);
        lock.alwaysFail = true;
        lock.answer = () -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();                   // until close() interrupts
            } catch (InterruptedException swallowed) {
                // a driver that swallows the interrupt (flag cleared) and then fails
            }
            throw new AssertionError("interrupted renewal");
        };
        manager.tryAcquire("wf-1");
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the renewer never reached renewAll");

        manager.close();

        assertTrue(thread.get().join(Duration.ofSeconds(4)), "the renewer thread outlived close()");
        assertEquals(0, manager.renewFailureStreak(), "a failure while stopping is not counted");
        assertEquals(List.of(), observer.loopErrors(), "a failure while stopping is not reported");
    }

    /** Records the renewer thread the manager builds, so a test can join it. */
    private AtomicReference<Thread> captureRenewerThread() {
        var captured = new AtomicReference<Thread>();
        var starter = manager.renewerThreadStarter;
        manager.renewerThreadStarter = () -> {
            var thread = starter.get();
            captured.set(thread);
            return thread;
        };
        return captured;
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    /** Saves a {@code PENDING} timer that is already due (fire_at = now - 1 s). */
    private static WorkflowTimer dueTimer(VersionedInMemoryStore store, String workflowId, String timerId) {
        var now = Instant.now();
        var row = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), workflowId, timerId,
                now.minusSeconds(1), TimerStatus.PENDING, now);
        store.saveTimer(row);
        return row;
    }

    /**
     * Polls the timer row every 20 ms until it reaches {@code wanted} or
     * {@code within} elapses, and returns the last status read — so a test
     * fails on the status, not on a timeout.
     */
    private static @Nullable TimerStatus statusWithin(VersionedInMemoryStore store, WorkflowTimer row,
                                                      TimerStatus wanted, Duration within) {
        var deadline = System.nanoTime() + within.toNanos();
        TimerStatus last = null;
        while (true) {
            last = store.findTimer(row.workflowInstanceId(), row.timerId()).map(WorkflowTimer::status).orElse(null);
            if (last == wanted || System.nanoTime() >= deadline) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    /**
     * A store whose first call of each loop's store method throws
     * {@link #failure} (when set), and whose later calls succeed.
     */
    static class PollFaultStore extends VersionedInMemoryStore {

        /** What the first call throws; {@code null} = never throw. */
        volatile @Nullable Supplier<? extends Throwable> failure;

        final AtomicInteger dueTimerPolls = new AtomicInteger();
        final AtomicInteger clockReads = new AtomicInteger();
        final AtomicInteger recoveryPasses = new AtomicInteger();
        final CountDownLatch secondRecoveryPass = new CountDownLatch(1);
        final AtomicInteger janitorSweeps = new AtomicInteger();
        final CountDownLatch secondJanitorSweep = new CountDownLatch(1);
        final AtomicInteger retentionPurges = new AtomicInteger();
        final CountDownLatch secondRetentionSweep = new CountDownLatch(1);

        @Override
        public List<WorkflowTimer> getDueTimers(int batchSize) {
            if (dueTimerPolls.incrementAndGet() == 1) {
                throwFailure();
            }
            return super.getDueTimers(batchSize);
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after,
                                                              int limit) {
            var call = recoveryPasses.incrementAndGet();
            if (call == 1) {
                throwFailure();
            } else if (call == 2) {
                secondRecoveryPass.countDown();
            }
            return List.of();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            var call = janitorSweeps.incrementAndGet();
            if (call == 1) {
                throwFailure();
            } else if (call == 2) {
                secondJanitorSweep.countDown();
            }
            return 0;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            var call = retentionPurges.incrementAndGet();
            if (call == 1) {
                throwFailure();
            } else if (call == 2) {
                secondRetentionSweep.countDown();
            }
            return PurgeResult.EMPTY;
        }

        @Override
        public Optional<Instant> storeTime() {
            clockReads.incrementAndGet();
            return Optional.of(Instant.now());
        }

        private void throwFailure() {
            var f = failure;
            if (f != null) {
                throw BackgroundLoopErrorTest.<RuntimeException>sneaky(f.get());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * A lock backend that grants every lock and answers {@link #renewAll} from
     * {@link #answer}, which may throw. After its first use the answer becomes
     * "nothing lost", unless {@link #alwaysFail} is set.
     */
    static final class FaultyRenewLock implements DistributedLock {

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch renewCalls = new CountDownLatch(2);
        final List<LockHandle> acquired = new CopyOnWriteArrayList<>();
        volatile Supplier<Set<LockHandle>> answer = Set::of;
        volatile boolean alwaysFail;

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            acquired.add(handle);
            return Optional.of(handle);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            calls.incrementAndGet();
            renewCalls.countDown();
            var current = answer;
            if (!alwaysFail) {
                answer = Set::of;
            }
            return current.get();
        }

        @Override
        public void release(LockHandle handle) {
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return true;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    /** A lock backend whose leader election always throws; nothing else is expected to be called. */
    static final class ThrowingLeaderLock implements DistributedLock {
        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            throw new IllegalStateException("lock backend down");
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(LockHandle handle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            throw new UnsupportedOperationException();
        }
    }
}
