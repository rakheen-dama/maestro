package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
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
 * store, its resilience to store failures, and its settings validation.
 */
class SignalJanitorTest {

    @Test
    @DisplayName("sweepOnce() passes now − retention as the cutoff and returns the store's count")
    void sweepOnce_passesNowMinusRetentionAsCutoff() {
        var store = new RecordingStore();                   // records the cutoff, returns 3
        var janitor = new SignalJanitor(store, Duration.ofHours(1), Duration.ofDays(7));
        var before = Instant.now();
        assertEquals(3, janitor.sweepOnce());
        assertTrue(Duration.between(before.minus(Duration.ofDays(7)), store.lastCutoff()).abs()
                .compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    @DisplayName("a store failure is logged and the loop keeps running")
    void storeFailure_isLoggedAndTheLoopKeepsRunning() throws Exception {
        var store = new RecordingStore();
        store.failNextWith(new IllegalStateException("db down"));
        var janitor = new SignalJanitor(store, Duration.ofMillis(20), Duration.ofDays(7));
        janitor.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> store.calls() >= 3);
        assertTrue(janitor.isRunning());
        janitor.stop();
        assertFalse(janitor.isRunning());
    }

    @Test
    @DisplayName("rejects a non-positive interval or retention")
    void rejectsNonPositiveSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignalJanitor(new RecordingStore(), Duration.ZERO, Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class,
                () -> new SignalJanitor(new RecordingStore(), Duration.ofHours(1), Duration.ofSeconds(-1)));
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

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            calls.incrementAndGet();
            lastCutoff.set(cutoff);
            var failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return 3;
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
