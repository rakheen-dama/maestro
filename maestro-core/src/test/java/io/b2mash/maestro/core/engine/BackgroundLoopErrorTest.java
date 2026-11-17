package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @AfterEach
    void tearDown() {
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
