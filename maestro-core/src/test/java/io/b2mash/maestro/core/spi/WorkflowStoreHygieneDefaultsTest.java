package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The housekeeping defaults of {@link WorkflowStore}: a third-party store that
 * overrides none of them behaves exactly as before — the unscoped, unbounded
 * janitor delete.
 */
class WorkflowStoreHygieneDefaultsTest {

    private final Instant cutoff = Instant.now().minus(Duration.ofDays(7));
    private final MinimalStore store = new MinimalStore();

    @Test
    void theJanitorOverloadsDefaultIsTheUnscopedUnboundedDelete() {
        store.returns(7);
        assertEquals(7, store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2));
        assertEquals(List.of(cutoff), store.cutoffs());
    }

    @Test
    void theJanitorOverloadsDefaultValidatesItsArguments() {
        assertThrows(NullPointerException.class,
                () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore(null, cutoff, 2));
        assertThrows(NullPointerException.class,
                () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", null, 2));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 0));
        assertEquals(List.of(), store.cutoffs(), "an invalid call never reaches the delegate");
    }

    /**
     * Implements only the abstract methods. The one-argument janitor delete
     * records its cutoff and returns a scripted count; every other method
     * throws.
     */
    private static final class MinimalStore implements WorkflowStore {

        private final List<Instant> cutoffs = new CopyOnWriteArrayList<>();
        private volatile int returns;

        void returns(int deleted) {
            this.returns = deleted;
        }

        List<Instant> cutoffs() {
            return cutoffs;
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            cutoffs.add(cutoff);
            return returns;
        }

        // ── Out of scope ────────────────────────────────────────────────

        @Override public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff,
                                                                  int batchSize) { throw unsupported(); }
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
            return new UnsupportedOperationException("not exercised by WorkflowStoreHygieneDefaultsTest");
        }
    }
}
