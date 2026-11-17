package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signal janitor's sweep: this service's stale signals, in batches of
 * {@link SignalJanitor#BATCH_SIZE} until a short batch, paced as the retention
 * sweeper is. Batch durations come from a fake clock the store advances;
 * pauses are recorded, never slept.
 */
class SignalJanitorBatchingTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private final BatchPacer.Wait recording = (stop, d) -> {
        pauses.add(d);
        return stop.getCount() == 0;
    };
    private final BatchPacer pacer = new BatchPacer(clock::get, recording);
    private final ScopedJanitorStore store = new ScopedJanitorStore(clock);

    @Test
    void aSweepDeletesThisServicesSignalsInBatchesUntilAShortBatch() {
        store.script(1000, ms(40)).script(1000, ms(70)).script(37, ms(10));
        var janitor = new SignalJanitor(store, "orders", Duration.ofHours(1), Duration.ofDays(7), EngineObserver.NOOP, pacer);
        assertEquals(2037, janitor.sweepOnce());
        assertEquals(List.of("orders", "orders", "orders"), store.calls().stream().map(Call::serviceName).toList());
        assertTrue(store.calls().stream().allMatch(c -> c.limit() == SignalJanitor.BATCH_SIZE));
        assertEquals(1, store.calls().stream().map(Call::cutoff).distinct().count(), "one cutoff per sweep");
        assertEquals(0, store.unscopedCalls(), "the unscoped, unbounded delete is never issued");
    }

    @Test
    void betweenTwoBatchesTheJanitorPausesAsLongAsTheBatchBeforeIt() {
        store.script(1000, ms(40)).script(1000, ms(70)).script(5, ms(10));
        janitor(pacer).sweepOnce();
        assertEquals(List.of(ms(40), ms(70)), pauses);
    }

    @Test
    void requestStopDuringAPauseEndsTheJanitorsSweep() throws Exception {
        var entered = new CountDownLatch(1);
        var pacer = new BatchPacer(clock::get, (stop, d) -> { entered.countDown(); return BatchPacer.LATCH.await(stop, d); });
        store.scriptForever(1000, Duration.ofHours(1));                  // every pause is the 5 s cap
        var janitor = janitor(pacer);
        var done = new CompletableFuture<Integer>();
        var interruptedAfter = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            var swept = janitor.sweepOnce();
            interruptedAfter.set(Thread.currentThread().isInterrupted());   // read before the result is published
            done.complete(swept);
        });
        assertTrue(entered.await(5, SECONDS), "the sweep reached its first pause");
        janitor.requestStop();                                            // never started: no loop thread to interrupt
        var result = done.completeOnTimeout(null, 1, SECONDS).get();
        assertNotNull(result, "sweepOnce was still paused 1 s after requestStop");
        assertEquals(1000, result);
        assertEquals(1, store.calls().size(), "no batch after the stop");
        assertFalse(interruptedAfter.get(), "nothing interrupted the sweeping thread");
    }

    @Test
    void aStoreFaultMidSweepReportsWhatWasDeletedSoFar() {
        store.script(1000, ms(5)).failOnCall(2, new StoreUnavailableException("down", new SQLException()));
        assertEquals(1000, janitor(pacer).sweepOnce());
    }

    @Test
    void aStoreWithoutTheOverloadIsSweptByItsUnscopedDelete() {
        var unscoped = new UnscopedStore(3);
        var janitor = new SignalJanitor(unscoped, "orders", Duration.ofHours(1), Duration.ofDays(7), EngineObserver.NOOP, pacer);
        assertEquals(3, janitor.sweepOnce());
        assertEquals(1, unscoped.calls.get(), "one call, through the overload's default");
    }

    /**
     * A store whose delete keeps reporting a full batch (one that reports
     * matched rather than removed rows, say) cannot turn a sweep into a hot
     * loop: the sweep ends after {@link SignalJanitor#MAX_BATCHES_PER_SWEEP}
     * batches. The fake would stop on its own one batch later.
     */
    @Test
    void aSweepEndsAfterItsBatchCapEvenIfEveryBatchIsFull() {
        for (int i = 0; i <= SignalJanitor.MAX_BATCHES_PER_SWEEP; i++) {
            store.script(SignalJanitor.BATCH_SIZE, ms(1));
        }
        var swept = janitor(pacer).sweepOnce();
        assertEquals(List.of(1_000, 1_000_000), List.of(store.calls().size(), swept));
    }

    /**
     * Guard: over a store without the overload, an unscoped delete of 1,000
     * rows or more is followed by exactly one more call, which deletes none
     * and ends the sweep (the overload's documented default path).
     */
    @Test
    void anUnscopedDeleteOfAFullBatchOrMoreIsCalledOnceMore() {
        var unscoped = new UnscopedStore(1_500);
        var janitor = new SignalJanitor(unscoped, "orders", Duration.ofHours(1), Duration.ofDays(7), EngineObserver.NOOP, pacer);
        assertEquals(1_500, janitor.sweepOnce());
        assertEquals(2, unscoped.calls.get(), "one more call, which deleted nothing");
    }

    @Test
    void aBlankServiceNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignalJanitor(store, " ", Duration.ofHours(1), Duration.ofDays(7)));
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private SignalJanitor janitor(BatchPacer pacer) {
        return new SignalJanitor(store, "orders", Duration.ofHours(1), Duration.ofDays(7), EngineObserver.NOOP, pacer);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    record Call(String serviceName, Instant cutoff, int limit) {
    }

    private record Step(int deleted, Duration took) {
    }

    /**
     * Overrides the scoped, bounded delete: records each call and plays a
     * scripted queue of {@code (deleted, took)} batches, advancing the shared
     * clock; an unscripted call deletes 0 in 0 ms, or plays the "forever"
     * step if one is set. It can throw on the n-th call. The unscoped delete
     * counts its calls and returns 0.
     */
    private static final class ScopedJanitorStore extends OutOfScopeStore {

        private final AtomicLong clock;
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final Deque<Step> scripted = new ArrayDeque<>();
        private volatile @Nullable Step forever;
        private volatile int failOnCall = -1;
        private volatile @Nullable RuntimeException failure;
        private final AtomicInteger unscopedCalls = new AtomicInteger();

        ScopedJanitorStore(AtomicLong clock) {
            this.clock = clock;
        }

        synchronized ScopedJanitorStore script(int deleted, Duration took) {
            scripted.addLast(new Step(deleted, took));
            return this;
        }

        void scriptForever(int deleted, Duration took) {
            forever = new Step(deleted, took);
        }

        ScopedJanitorStore failOnCall(int oneBasedCall, RuntimeException failure) {
            this.failOnCall = oneBasedCall;
            this.failure = failure;
            return this;
        }

        List<Call> calls() {
            return calls;
        }

        int unscopedCalls() {
            return unscopedCalls.get();
        }

        @Override
        public synchronized int deleteUnconsumedSignalsForTerminalInstancesBefore(String serviceName, Instant cutoff,
                                                                               int limit) {
            calls.add(new Call(serviceName, cutoff, limit));
            if (calls.size() == failOnCall && failure != null) {
                throw failure;
            }
            var step = scripted.pollFirst();
            if (step == null) {
                step = forever;
            }
            if (step == null) {
                return 0;
            }
            clock.addAndGet(step.took().toNanos());
            return step.deleted();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            unscopedCalls.incrementAndGet();
            return 0;
        }
    }

    /**
     * Implements only the unscoped delete, as a third-party store written
     * before the overload would: the first call deletes {@code deleted} rows,
     * and, the delete being idempotent, every later call deletes none.
     */
    private static final class UnscopedStore extends OutOfScopeStore {

        private final int deleted;
        final AtomicInteger calls = new AtomicInteger();

        UnscopedStore(int deleted) {
            this.deleted = deleted;
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            return calls.incrementAndGet() == 1 ? deleted : 0;
        }
    }

    /** Every abstract SPI method but the unscoped janitor delete throws. */
    private abstract static class OutOfScopeStore implements WorkflowStore {

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
        @Override public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff,
                                                                  int batchSize) { throw unsupported(); }
        @Override public void saveTimer(WorkflowTimer timer) { throw unsupported(); }
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { throw unsupported(); }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            throw unsupported();
        }
        @Override public boolean markTimerFired(UUID timerId) { throw unsupported(); }
        @Override public boolean markTimerCancelled(UUID timerId) { throw unsupported(); }

        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not exercised by SignalJanitorBatchingTest");
        }
    }
}
