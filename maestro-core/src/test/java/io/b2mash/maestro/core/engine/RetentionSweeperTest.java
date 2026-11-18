package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RetentionSweeper}: leader gating, per-status cutoffs,
 * the batch loop, resilience to store failures, and the executor seams that
 * start and stop it.
 */
class RetentionSweeperTest {

    private static final String SERVICE = "retention-test";

    private static WorkflowExecutor.RetentionSettings settings(int batchSize, int maxBatches) {
        return new WorkflowExecutor.RetentionSettings(Duration.ofMinutes(10),
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), batchSize, maxBatches);
    }

    @Nested
    @DisplayName("settings validation")
    class SettingsValidation {

        @Test
        @DisplayName("rejects a non-positive duration or count")
        void rejectsNonPositiveSettings() {
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ZERO, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(-1), Duration.ofDays(1), Duration.ofDays(1), 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(1), Duration.ZERO, Duration.ofDays(1), 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1), null, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 1, 0));
        }

        @Test
        @DisplayName("rejects a batch size above MAX_BATCH_SIZE (the JDBC bind-parameter ceiling)")
        void rejectsBatchSizeAboveTheCap() {
            assertEquals(10_000, WorkflowExecutor.RetentionSettings.MAX_BATCH_SIZE);
            new WorkflowExecutor.RetentionSettings(Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1),
                    Duration.ofDays(1), WorkflowExecutor.RetentionSettings.MAX_BATCH_SIZE, 1);
            var ex = assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor.RetentionSettings(
                    Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1),
                    WorkflowExecutor.RetentionSettings.MAX_BATCH_SIZE + 1, 1));
            assertTrue(ex.getMessage().contains("10000"), ex.getMessage());
        }

        @Test
        @DisplayName("the sweeper rejects a null store, lock, settings or observer and a blank service name")
        void sweeperRejectsNulls() {
            var store = new RecordingStore();
            assertThrows(NullPointerException.class,
                    () -> new RetentionSweeper(null, new InMemoryDistributedLock(), SERVICE, settings(1, 1),
                            new RecordingEngineObserver()));
            assertThrows(NullPointerException.class,
                    () -> new RetentionSweeper(store, null, SERVICE, settings(1, 1), new RecordingEngineObserver()));
            assertThrows(NullPointerException.class,
                    () -> new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, null, new RecordingEngineObserver()));
            assertThrows(NullPointerException.class,
                    () -> new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(1, 1), null));
            assertThrows(IllegalArgumentException.class,
                    () -> new RetentionSweeper(store, new InMemoryDistributedLock(), " ", settings(1, 1), new RecordingEngineObserver()));
        }
    }

    @Nested
    @DisplayName("one sweep")
    class OneSweep {

        @Test
        @DisplayName("purges each terminal status with its own cutoff and sums the results")
        void sweepsEveryStatusWithItsOwnCutoff() {
            var store = new RecordingStore();
            store.enqueue(new PurgeResult(2, 10, 1, 0), PurgeResult.EMPTY, new PurgeResult(1, 4, 0, 2));
            var observer = new RecordingEngineObserver();
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(500, 100), observer);

            var before = Instant.now();
            var result = sweeper.sweepOnce();

            assertEquals(Optional.of(new PurgeResult(3, 14, 1, 2)), result);
            assertEquals(List.of(WorkflowStatus.COMPLETED, WorkflowStatus.TERMINATED, WorkflowStatus.FAILED),
                    store.calls.stream().map(Call::status).toList());
            assertCutoffNear(before.minus(Duration.ofDays(30)), store.calls.get(0).cutoff());
            assertCutoffNear(before.minus(Duration.ofDays(30)), store.calls.get(1).cutoff());
            assertCutoffNear(before.minus(Duration.ofDays(90)), store.calls.get(2).cutoff());
            assertTrue(store.calls.stream().allMatch(c -> c.batchSize() == 500));
            assertEquals(List.of(new PurgeResult(3, 14, 1, 2)),
                    observer.retentionPasses().stream().map(RecordingEngineObserver.RetentionPassCall::purged).toList(),
                    "one retentionPass per sweep, carrying the summed counts");
        }

        @Test
        @DisplayName("keeps taking batches while they come back full, and stops at the per-status budget")
        void batchLoopStopsOnAShortBatchOrTheBudget() {
            var store = new RecordingStore();
            // COMPLETED: 2 full batches then a short one → 3 calls.
            // TERMINATED: budget of 4 full batches, never short → exactly 4 calls.
            // FAILED: nothing → 1 call.
            store.enqueue(full(), full(), new PurgeResult(1, 1, 0, 0),
                    full(), full(), full(), full(),
                    PurgeResult.EMPTY);
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(2, 4), new RecordingEngineObserver());

            var result = sweeper.sweepOnce().orElseThrow();

            assertEquals(8, store.calls.size());
            assertEquals(3, store.calls.stream().filter(c -> c.status() == WorkflowStatus.COMPLETED).count());
            assertEquals(4, store.calls.stream().filter(c -> c.status() == WorkflowStatus.TERMINATED).count());
            assertEquals(1, store.calls.stream().filter(c -> c.status() == WorkflowStatus.FAILED).count());
            assertEquals(2 * 2 + 1 + 4 * 2, result.instances());
        }

        /**
         * P2: a store fault fails the sweep — it propagates to the supervisor,
         * which counts it — and what was purged so far is still reported.
         */
        @Test
        @DisplayName("a store failure fails the sweep, and what was purged so far is still reported")
        void storeFailureEndsTheSweepAndReportsThePartialTotal() {
            var store = new RecordingStore();
            store.enqueue(new PurgeResult(5, 20, 0, 0));
            store.failOnCall(2, new IllegalStateException("db down"));
            var observer = new RecordingEngineObserver();
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(500, 100), observer);

            assertThrows(IllegalStateException.class, sweeper::sweepOnce);

            assertEquals(2, store.calls.size(), "the sweep stops at the failing batch");
            assertEquals(List.of(new PurgeResult(5, 20, 0, 0)),
                    observer.retentionPasses().stream().map(RecordingEngineObserver.RetentionPassCall::purged).toList(),
                    "one retentionPass carrying what was purged before the fault");
        }

        @Test
        @DisplayName("an observer that throws does not break the sweep")
        void throwingObserverIsContained() {
            var store = new RecordingStore();
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(500, 100),
                    new io.b2mash.maestro.core.observe.EngineObserver() {
                        @Override
                        public void retentionPass(PurgeResult purged) {
                            throw new IllegalStateException("observer bug");
                        }
                    });
            assertEquals(Optional.of(PurgeResult.EMPTY), sweeper.sweepOnce());
        }

        private static PurgeResult full() {
            return new PurgeResult(2, 6, 1, 1);
        }

        private static void assertCutoffNear(Instant expected, Instant actual) {
            assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(5)) < 0,
                    "cutoff " + actual + " should be near " + expected);
        }
    }

    @Nested
    @DisplayName("leader gating")
    class LeaderGating {

        @Test
        @DisplayName("a node that is not the leader never calls the store and emits no retentionPass")
        void nonLeaderNeverCallsTheStore() {
            var store = new RecordingStore();
            var lock = new ScriptedLeaderLock(false);
            var observer = new RecordingEngineObserver();
            var sweeper = new RetentionSweeper(store, lock, SERVICE, settings(500, 100), observer);

            assertEquals(Optional.empty(), sweeper.sweepOnce());

            assertTrue(store.calls.isEmpty(), "a non-leader must not touch the store");
            assertTrue(observer.retentionPasses().isEmpty(), "a non-leader emits nothing");
            assertEquals(List.of(RetentionSweeper.LEADER_KEY_PREFIX + SERVICE), lock.keys,
                    "the election key is maestro:leader:retention:<service>");
            assertEquals(List.of(RetentionSweeper.LEADER_TTL), lock.ttls);
        }

        @Test
        @DisplayName("leadership is re-checked (and so renewed) before every batch")
        void leadershipIsRecheckedPerBatch() {
            var store = new RecordingStore();
            store.enqueue(full(), full(), PurgeResult.EMPTY, PurgeResult.EMPTY, PurgeResult.EMPTY);
            var lock = new ScriptedLeaderLock(true);
            var sweeper = new RetentionSweeper(store, lock, SERVICE, settings(2, 100), new RecordingEngineObserver());

            sweeper.sweepOnce();

            assertEquals(5, store.calls.size());
            assertEquals(5, lock.calls.get(), "one trySetLeader per batch");
            assertEquals(1, lock.candidateIds.size(), "the same candidate ID is used every time");
        }

        @Test
        @DisplayName("losing leadership mid-sweep stops the sweep and reports what was purged so far")
        void losingLeadershipMidSweepStops() {
            var store = new RecordingStore();
            store.enqueue(full(), full(), full());
            var lock = new ScriptedLeaderLock(true);
            lock.loseLeadershipAfter(2);
            var observer = new RecordingEngineObserver();
            var sweeper = new RetentionSweeper(store, lock, SERVICE, settings(2, 100), observer);

            var result = sweeper.sweepOnce();

            assertEquals(Optional.of(full().plus(full())), result);
            assertEquals(2, store.calls.size(), "the third batch must not run without leadership");
            assertEquals(1, observer.retentionPasses().size());
        }

        /** P2: a lock-backend fault on the election fails the sweep; it no longer counts as "not the leader". */
        @Test
        @DisplayName("a lock-backend failure fails the sweep before any batch")
        void lockBackendFailureIsNotTheLeader() {
            var store = new RecordingStore();
            var lock = new ScriptedLeaderLock(true);
            lock.failWith(new IllegalStateException("lock table unreachable"));
            var observer = new RecordingEngineObserver();
            var sweeper = new RetentionSweeper(store, lock, SERVICE, settings(2, 100), observer);

            assertThrows(IllegalStateException.class, sweeper::sweepOnce);
            assertTrue(store.calls.isEmpty());
            assertTrue(observer.retentionPasses().isEmpty(), "not the leader: nothing to report");
        }

        /**
         * P2: a lock-backend fault on the election is a failed sweep, on every
         * node. It used to be read as "not the leader" and swallowed, so the
         * loop's streak never moved (Issue 67).
         */
        @Test
        @DisplayName("a leader-election fault is a failed sweep: it extends the loop's streak")
        void aLeaderElectionFaultExtendsTheStreak() {
            var store = new RecordingStore();
            var lock = new ScriptedLeaderLock(true);
            lock.failWith(new IllegalStateException("lock table unreachable"));
            var sweeper = new RetentionSweeper(store, lock, SERVICE, new WorkflowExecutor.RetentionSettings(
                    Duration.ofMillis(20), Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), 2, 100),
                    new RecordingEngineObserver());
            var loop = LoopSupervisor.start(sweeper.spec(), sweeper, EngineObserver.NOOP);
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> loop.snapshot().consecutiveFailures() >= 2);
                assertEquals(IllegalStateException.class.getName(), loop.snapshot().lastFailureType());
                assertTrue(store.calls.isEmpty(), "no batch without a leader");
            } finally {
                loop.requestStop();
                loop.awaitStopped(Duration.ofSeconds(5));
            }
        }

        private static PurgeResult full() {
            return new PurgeResult(2, 6, 1, 1);
        }
    }

    @Nested
    @DisplayName("loop lifecycle")
    class LoopLifecycle {

        /** Runs the sweeper under a supervisor, as the executor does. */
        private LoopSupervisor supervise(RetentionSweeper sweeper) {
            return LoopSupervisor.start(sweeper.spec(), sweeper, EngineObserver.NOOP);
        }

        /** Stops it as shutdown does: request, then a bounded wait. */
        private void stop(LoopSupervisor loop) {
            loop.requestStop();
            loop.awaitStopped(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("the loop sweeps at once and then every interval; a stop ends the loop")
        void startSweepsThenStopEnds() {
            var store = new RecordingStore();
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE,
                    new WorkflowExecutor.RetentionSettings(Duration.ofMillis(20),
                            Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), 500, 100),
                    new RecordingEngineObserver());
            var loop = supervise(sweeper);
            await().atMost(Duration.ofSeconds(5)).until(() -> store.calls.size() >= 6);
            assertTrue(loop.snapshot().running());
            stop(loop);
            assertFalse(loop.snapshot().running());
            // "cannot be restarted" is the executor's rule now: ExecutorSeams below.
        }

        @Test
        @DisplayName("a store failure is a failed sweep and the loop keeps running")
        void storeFailureIsSwallowedAndTheLoopKeepsRunning() {
            var store = new RecordingStore();
            store.failOnCall(1, new IllegalStateException("db down"));
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE,
                    new WorkflowExecutor.RetentionSettings(Duration.ofMillis(20),
                            Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), 500, 100),
                    new RecordingEngineObserver());
            var loop = supervise(sweeper);
            await().atMost(Duration.ofSeconds(5)).until(() -> store.calls.size() >= 4);
            assertTrue(loop.snapshot().running());
            stop(loop);
        }

        @Test
        @DisplayName("a stop during a batch ends the sweep before the next batch")
        void stopMidSweepEndsBeforeTheNextBatch() throws Exception {
            var store = new RecordingStore();
            // Every batch comes back full, so without the stop check the sweep
            // would run on for the whole 100-batch budget after stop().
            var full = new PurgeResult(2, 2, 0, 0);
            store.enqueue(java.util.Collections.nCopies(300, full).toArray(PurgeResult[]::new));
            store.blockFirstCallUntilReleased(full);
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(2, 100), new RecordingEngineObserver());
            var loop = supervise(sweeper);
            assertTrue(store.sweepEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            var stopper = Thread.startVirtualThread(() -> stop(loop));
            await().atMost(Duration.ofSeconds(5)).until(() -> !loop.snapshot().running());
            store.release.countDown(); // the blocked batch now returns full
            stopper.join(Duration.ofSeconds(10).toMillis());
            assertFalse(stopper.isAlive(), "stop() must have returned");

            assertEquals(1, store.calls.size(), "no batch may start after stop()");
        }

        /**
         * A stop never interrupts the sweeper's thread (Issue 64): the batch in
         * progress finishes, and the loop ends as soon as it returns, without
         * sleeping an interval first. (It used to be stopped by an interrupt a
         * driver could swallow.)
         */
        @Test
        @DisplayName("a stop never interrupts a batch in progress; the loop ends once it returns")
        void stopWhileTheStoreSwallowsTheInterruptEndsPromptly() throws Exception {
            var store = new RecordingStore();
            store.blockFirstCallUntilReleased(PurgeResult.EMPTY);
            var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), SERVICE, settings(500, 100), new RecordingEngineObserver());
            var loop = supervise(sweeper);
            assertTrue(store.sweepEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            loop.requestStop();
            var stoppedWhileInTheBatch = loop.awaitStopped(Duration.ofMillis(100));
            store.release.countDown();
            var started = System.nanoTime();
            var stopped = loop.awaitStopped(Duration.ofSeconds(5));
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertFalse(stoppedWhileInTheBatch, "the batch is still in progress");
            assertTrue(stopped, "the loop ended once the batch returned");
            assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "it did not sleep an interval first: " + elapsed);
            assertFalse(store.interruptedInStoreCall.get(), "the batch was never interrupted");
        }
    }

    @Nested
    @DisplayName("executor seams")
    class ExecutorSeams {

        @Test
        @DisplayName("startRetentionSweeper starts it, exposes its settings, and shutdown stops it")
        void executorStartsExposesAndStops() {
            var store = new RecordingStore();
            var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new tools.jackson.databind.ObjectMapper()), SERVICE).distributedLock(new InMemoryDistributedLock())
                    .build();
            try {
                assertFalse(executor.hasRetentionSweeperStarted());
                assertFalse(executor.isRetentionSweeperRunning());
                assertNull(executor.retentionSettings());

                var settings = settings(500, 100);
                executor.startRetentionSweeper(settings);

                assertTrue(executor.hasRetentionSweeperStarted());
                assertTrue(executor.isRetentionSweeperRunning());
                assertEquals(settings, executor.retentionSettings());
                await().atMost(Duration.ofSeconds(5)).until(() -> store.calls.size() >= 3);
                assertThrows(IllegalStateException.class, () -> executor.startRetentionSweeper(settings),
                        "a second start is rejected");
            } finally {
                executor.shutdown();
            }
            assertFalse(executor.isRetentionSweeperRunning());
            assertTrue(executor.hasRetentionSweeperStarted(), "monotonic once started");
            assertThrows(IllegalStateException.class, () -> executor.startRetentionSweeper(settings(1, 1)),
                    "cannot start on a shut-down executor");
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private record Call(WorkflowStatus status, Instant cutoff, int batchSize) {
    }

    /**
     * A store that records every purge call and answers from a queue of
     * scripted results ({@link PurgeResult#EMPTY} once the queue is empty), or
     * throws on the n-th call. Every other SPI method is out of scope and throws.
     */
    private static final class RecordingStore implements WorkflowStore {

        final List<Call> calls = new CopyOnWriteArrayList<>();
        private final Deque<PurgeResult> scripted = new ArrayDeque<>();
        private int failOnCall = -1;
        private @Nullable RuntimeException failure;
        final java.util.concurrent.CountDownLatch sweepEntered = new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean interruptedInStoreCall = new AtomicBoolean();

        synchronized void enqueue(PurgeResult... results) {
            scripted.addAll(List.of(results));
        }

        synchronized void failOnCall(int oneBasedCall, RuntimeException failure) {
            this.failOnCall = oneBasedCall;
            this.failure = failure;
        }

        private volatile @Nullable PurgeResult blockedResult;
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        /** The first call blocks (recording and swallowing any interrupt) until {@link #release} is counted down, then returns {@code result}. */
        void blockFirstCallUntilReleased(PurgeResult result) {
            blockedResult = result;
        }

        @Override
        public synchronized PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff,
                                                                     int batchSize) {
            calls.add(new Call(status, cutoff, batchSize));
            var blocked = blockedResult;
            if (blocked != null && calls.size() == 1) {
                sweepEntered.countDown();
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException swallowed) {
                        // swallowed — keep waiting, like a driver that ignores interrupts
                        interruptedInStoreCall.set(true);
                    }
                }
                return blocked;
            }
            if (calls.size() == failOnCall && failure != null) {
                throw failure;
            }
            var next = scripted.pollFirst();
            return next != null ? next : PurgeResult.EMPTY;
        }

        // ── Out of scope ────────────────────────────────────────────────

        @Override public WorkflowInstance createInstance(WorkflowInstance instance) { throw unsupported(); }
        @Override public Optional<WorkflowInstance> getInstance(String workflowId) { throw unsupported(); }
        @Override public List<WorkflowInstance> getRecoverableInstances() { throw unsupported(); }
        @Override public void updateInstance(WorkflowInstance instance) { throw unsupported(); }
        @Override public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            throw unsupported();
        }
        @Override public void appendEvent(WorkflowEvent event) { throw unsupported(); }
        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            throw unsupported();
        }
        @Override public List<WorkflowEvent> getEvents(UUID instanceId) { throw unsupported(); }
        @Override public int deleteFailureEvents(UUID instanceId) { throw unsupported(); }
        @Override public void saveSignal(WorkflowSignal signal) { throw unsupported(); }
        @Override public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            throw unsupported();
        }
        @Override public boolean markSignalConsumed(UUID signalId) { throw unsupported(); }
        @Override public void adoptOrphanedSignals(String workflowId, UUID instanceId) { throw unsupported(); }
        @Override public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) { throw unsupported(); }
        @Override public void saveTimer(WorkflowTimer timer) { throw unsupported(); }
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { throw unsupported(); }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            throw unsupported();
        }
        @Override public boolean markTimerFired(UUID timerId) { throw unsupported(); }
        @Override public boolean markTimerCancelled(UUID timerId) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not exercised by RetentionSweeperTest");
        }
    }

    /** A lock whose only job is {@code trySetLeader}; scripted to be (or stop being) the leader, or to fail. */
    private static final class ScriptedLeaderLock implements DistributedLock {

        private final AtomicBoolean leader;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<Duration> ttls = new CopyOnWriteArrayList<>();
        final java.util.Set<String> candidateIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile int loseAfter = Integer.MAX_VALUE;
        private volatile @Nullable RuntimeException failure;

        ScriptedLeaderLock(boolean leader) {
            this.leader = new AtomicBoolean(leader);
        }

        void loseLeadershipAfter(int successfulChecks) {
            this.loseAfter = successfulChecks;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            var n = calls.incrementAndGet();
            keys.add(electionKey);
            ttls.add(ttl);
            candidateIds.add(candidateId);
            if (failure != null) {
                throw failure;
            }
            return leader.get() && n <= loseAfter;
        }

        @Override public Optional<LockHandle> tryAcquire(String key, Duration ttl) { throw new UnsupportedOperationException(); }
        @Override public void release(LockHandle handle) { throw new UnsupportedOperationException(); }
        @Override public boolean renew(LockHandle handle, Duration ttl) { throw new UnsupportedOperationException(); }
    }
}
