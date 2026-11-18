package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention sweeper's pacing: before every batch but a sweep's first it
 * pauses as long as the batch before it took, at most five seconds, and
 * re-checks leadership after the pause. Batch durations come from a fake clock
 * the store advances; pauses are recorded, never slept.
 */
class RetentionSweeperPacingTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private final TimedStore store = new TimedStore(clock, events);
    private final RecordingLock lock = new RecordingLock(events);
    private final BatchPacer.Wait recording = (stop, d) -> {
        pauses.add(d);
        events.add("pause:" + d.toMillis());
        return stop.getCount() == 0;
    };
    private final BatchPacer pacer = new BatchPacer(clock::get, recording);

    private final PurgeResult full = new PurgeResult(2, 2, 0, 0);
    private final PurgeResult one = new PurgeResult(1, 1, 0, 0);

    @Test
    void everyBatchAfterTheFirstWaitsAsLongAsTheBatchBeforeIt() {
        store.script(full, ms(40)).script(full, ms(70)).script(one, ms(10))     // COMPLETED: full, full, short
             .script(PurgeResult.EMPTY, ms(3))                                   // TERMINATED: empty
             .script(PurgeResult.EMPTY, ms(2));                                  // FAILED: empty
        new RetentionSweeper(store, new InMemoryDistributedLock(), "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
        assertEquals(List.of(ms(40), ms(70), ms(10), ms(3)), pauses,
                "one pause before every batch but the first, each as long as the batch before it; none after the last");
    }

    @Test
    void aSlowBatchPausesFiveSecondsNotItsOwnLength() {
        store.script(full, Duration.ofSeconds(40)).script(one, Duration.ofSeconds(40));   // T and F: unscripted, 0 ms
        new RetentionSweeper(store, new InMemoryDistributedLock(), "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5)), pauses,
                "capped at a third of the leader TTL; T's 0 ms batch costs F no pause");
    }

    @Test
    void theLeaderIsCheckedAfterThePauseRightBeforeTheBatch() {
        store.script(full, ms(40)).script(one, ms(10));                                   // T and F: unscripted, 0 ms
        new RetentionSweeper(store, lock, "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
        assertEquals(List.of("leader", "batch:COMPLETED", "pause:40", "leader", "batch:COMPLETED",
                             "pause:10", "leader", "batch:TERMINATED", "leader", "batch:FAILED"),
                     events);
    }

    @Test
    void requestStopEndsAPauseAtOnceWithoutAnInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var pacer = new BatchPacer(clock::get, (stop, d) -> { entered.countDown(); return BatchPacer.LATCH.await(stop, d); });
        store.scriptForever(full, Duration.ofHours(1));                  // every pause is the 5 s cap
        var sweeper = new RetentionSweeper(store, new InMemoryDistributedLock(), "orders", settings(2, 100), EngineObserver.NOOP, pacer);
        var done = new CompletableFuture<Optional<PurgeResult>>();
        var interruptedAfter = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            var swept = sweeper.sweepOnce();
            interruptedAfter.set(Thread.currentThread().isInterrupted());   // read before the result is published
            done.complete(swept);
        });
        assertTrue(entered.await(5, SECONDS), "the sweep reached its first pause");
        sweeper.spec().onStop().forEach(Runnable::run);                  // what the supervisor's requestStop runs
        var result = done.completeOnTimeout(null, 1, SECONDS).get();
        assertNotNull(result, "sweepOnce was still paused 1 s after requestStop");
        assertEquals(1, store.purgeCalls(), "no batch after the stop");
        assertEquals(2, result.orElseThrow().instances());
        assertFalse(interruptedAfter.get(), "nothing interrupted the sweeping thread");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    private static WorkflowExecutor.RetentionSettings settings(int batchSize, int maxBatches) {
        return new WorkflowExecutor.RetentionSettings(Duration.ofMinutes(10),
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), batchSize, maxBatches);
    }

    private record Step(PurgeResult result, Duration took) {
    }

    /**
     * Plays a scripted queue of {@code (result, duration)} purge batches: each
     * call records {@code batch:<STATUS>}, advances the shared clock by the
     * duration and returns the result. An unscripted call returns
     * {@link PurgeResult#EMPTY} in 0 ms, or the "forever" step if one is set.
     * Every other SPI method is out of scope and throws.
     */
    static final class TimedStore implements WorkflowStore {

        private final AtomicLong clock;
        final List<String> events;
        private final Deque<Step> scripted = new ArrayDeque<>();
        private volatile @Nullable Step forever;
        private final AtomicInteger purgeCalls = new AtomicInteger();

        TimedStore(AtomicLong clock, List<String> events) {
            this.clock = clock;
            this.events = events;
        }

        synchronized TimedStore script(PurgeResult result, Duration took) {
            scripted.addLast(new Step(result, took));
            return this;
        }

        void scriptForever(PurgeResult result, Duration took) {
            forever = new Step(result, took);
        }

        int purgeCalls() {
            return purgeCalls.get();
        }

        @Override
        public synchronized PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff,
                                                                     int batchSize) {
            purgeCalls.incrementAndGet();
            events.add("batch:" + status);
            var step = scripted.pollFirst();
            if (step == null) {
                step = forever;
            }
            if (step == null) {
                return PurgeResult.EMPTY;
            }
            clock.addAndGet(step.took().toNanos());
            return step.result();
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
            return new UnsupportedOperationException("not exercised by RetentionSweeperPacingTest");
        }
    }

    /** A lock that is always the leader and records {@code leader} for every check. */
    private static final class RecordingLock implements DistributedLock {

        private final List<String> events;

        RecordingLock(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            events.add("leader");
            return true;
        }

        @Override public Optional<LockHandle> tryAcquire(String key, Duration ttl) { throw new UnsupportedOperationException(); }
        @Override public void release(LockHandle handle) { throw new UnsupportedOperationException(); }
        @Override public boolean renew(LockHandle handle, Duration ttl) { throw new UnsupportedOperationException(); }
    }
}
