package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SignalJanitor} (C14b): the cutoff it passes to the
 * store, its resilience to store failures under the loop supervisor, its stop,
 * and its settings validation.
 */
class SignalJanitorTest {

    @Test
    @DisplayName("sweepOnce() passes now − retention as the cutoff and returns the store's count")
    void sweepOnce_passesNowMinusRetentionAsCutoff() {
        var store = new RecordingStore();                   // records the cutoff, returns 3
        var janitor = new SignalJanitor(store, "svc", Duration.ofHours(1), Duration.ofDays(7));
        var before = Instant.now();
        assertEquals(3, janitor.sweepOnce());
        assertTrue(Duration.between(before.minus(Duration.ofDays(7)), store.lastCutoff()).abs()
                .compareTo(Duration.ofSeconds(5)) < 0);
    }

    /**
     * A stop never interrupts the janitor's thread (Issue 64): a sweep's store
     * call in progress — JDBC, in production — finishes, and the loop ends as
     * soon as it returns, without sleeping an interval first. (It used to be
     * stopped by an interrupt that a driver could swallow; see item 17.)
     */
    @Test
    @DisplayName("a stop during a sweep never interrupts its store call; the loop ends once the call returns")
    void stop_duringAStoreCall_neverInterruptsIt_andTheLoopEndsOnceItReturns() throws Exception {
        var store = new RecordingStore();
        store.blockNextCallUntilReleased();
        var janitor = new SignalJanitor(store, "svc", Duration.ofMinutes(10), Duration.ofDays(7));
        var loop = LoopSupervisor.start(janitor.spec(), janitor, EngineObserver.NOOP);
        assertTrue(store.sweepEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

        loop.requestStop();
        var stoppedWhileInTheCall = loop.awaitStopped(Duration.ofMillis(100));
        store.release.countDown();
        var started = System.nanoTime();
        var stopped = loop.awaitStopped(Duration.ofSeconds(5));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertFalse(stoppedWhileInTheCall, "the store call is still in progress");
        assertTrue(stopped, "the loop ended once the call returned");
        assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "it did not sleep an interval first: " + elapsed);
        assertFalse(store.interruptedInStoreCall.get(), "the store call was never interrupted");
        var thread = store.sweepThread.get();
        assertTrue(thread != null && thread.join(Duration.ofSeconds(1)), "the janitor thread must have exited");
    }

    @Test
    @DisplayName("a store failure is a failed sweep and the loop keeps running")
    void storeFailure_isSwallowedAndTheLoopKeepsRunning() throws Exception {
        var store = new RecordingStore();
        store.failNextWith(new IllegalStateException("db down"));
        var janitor = new SignalJanitor(store, "svc", Duration.ofMillis(20), Duration.ofDays(7));
        var loop = LoopSupervisor.start(janitor.spec(), janitor, EngineObserver.NOOP);
        await().atMost(Duration.ofSeconds(5)).until(() -> store.calls() >= 3);
        assertTrue(loop.snapshot().running());
        loop.requestStop();
        assertTrue(loop.awaitStopped(Duration.ofSeconds(5)));
        assertFalse(loop.snapshot().running());
    }

    /**
     * P2: a store fault is a failed sweep. It used to be logged at WARN and
     * swallowed by the sweep, so it never reached the loop's streak and health
     * read the janitor healthy while every sweep failed (Issue 67).
     */
    @Test
    @DisplayName("a store fault is a failed sweep: it extends the loop's streak")
    void aStoreFaultExtendsTheStreak() {
        var store = new RecordingStore();
        store.failEveryCallWith(new IllegalStateException("db down"));
        var janitor = new SignalJanitor(store, "svc", Duration.ofMillis(20), Duration.ofDays(7));
        var loop = LoopSupervisor.start(janitor.spec(), janitor, EngineObserver.NOOP);
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> loop.snapshot().consecutiveFailures() >= 2);
            assertEquals(IllegalStateException.class.getName(), loop.snapshot().lastFailureType());
            assertTrue(loop.snapshot().running(), "the janitor keeps running");
        } finally {
            loop.requestStop();
            loop.awaitStopped(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("rejects a non-positive interval or retention")
    void rejectsNonPositiveSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignalJanitor(new RecordingStore(), "svc", Duration.ZERO, Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class,
                () -> new SignalJanitor(new RecordingStore(), "svc", Duration.ofHours(1), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new SignalJanitor(new RecordingStore(), "svc",
                Duration.ofHours(1), Duration.ofDays(7), Duration.ZERO, new BatchPacer()));
    }

    /**
     * A store that records the janitor's cutoff and returns {@code 3}, or
     * throws once when primed with {@link #failNextWith}. Every other SPI
     * method is out of scope and throws.
     */
    private static final class RecordingStore implements WorkflowStore {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<@Nullable Instant> lastCutoff = new AtomicReference<>();
        private final AtomicReference<@Nullable RuntimeException> nextFailure = new AtomicReference<>();

        void failNextWith(RuntimeException failure) {
            nextFailure.set(failure);
        }

        private volatile @Nullable RuntimeException everyCall;

        void failEveryCallWith(RuntimeException failure) {
            everyCall = failure;
        }

        int calls() {
            return calls.get();
        }

        Instant lastCutoff() {
            var cutoff = lastCutoff.get();
            if (cutoff == null) {
                throw new AssertionError("the janitor never called the store");
            }
            return cutoff;
        }

        private volatile boolean block;
        final java.util.concurrent.CountDownLatch sweepEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<@Nullable Thread> sweepThread = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean interruptedInStoreCall =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** The next call blocks until {@link #release}, recording (and swallowing) any interrupt. */
        void blockNextCallUntilReleased() {
            block = true;
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            calls.incrementAndGet();
            lastCutoff.set(cutoff);
            if (block) {
                block = false;
                sweepThread.set(Thread.currentThread());
                sweepEntered.countDown();
                while (true) {
                    try {
                        release.await();
                        return 0;
                    } catch (InterruptedException e) {
                        interruptedInStoreCall.set(true);   // swallowed, as some drivers do
                    }
                }
            }
            var failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            var always = everyCall;
            if (always != null) {
                throw always;
            }
            return 3;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            throw new UnsupportedOperationException();
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
        @Override public void saveTimer(WorkflowTimer timer) { throw unsupported(); }
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { throw unsupported(); }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            throw unsupported();
        }
        @Override public boolean markTimerFired(UUID timerId) { throw unsupported(); }
        @Override public boolean markTimerCancelled(UUID timerId) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not exercised by SignalJanitorTest");
        }
    }
}
