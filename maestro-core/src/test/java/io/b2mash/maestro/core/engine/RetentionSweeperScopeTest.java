package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The retention sweeper purges only its own service's workflows: every batch goes through the scoped purge. */
class RetentionSweeperScopeTest {

    private final ScopedStore store = new ScopedStore();

    @Test
    void everyBatchIsScopedToTheSweepersService() {
        store.script(new PurgeResult(2, 2, 0, 0));                                 // C full, then EMPTY
        new RetentionSweeper(store, null, "orders", settings(2, 100), EngineObserver.NOOP).sweepOnce();
        assertEquals(List.of("orders", "orders", "orders", "orders"), store.scopedServiceNames());   // C full, C empty, T, F
        assertEquals(0, store.unscopedCalls());
    }

    private static WorkflowExecutor.RetentionSettings settings(int batchSize, int maxBatches) {
        return new WorkflowExecutor.RetentionSettings(Duration.ofMinutes(10),
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), batchSize, maxBatches);
    }

    /**
     * Overrides the scoped purge, recording the service name of each call and
     * answering from a scripted queue ({@link PurgeResult#EMPTY} once empty);
     * the unscoped purge counts its calls and returns {@code EMPTY}. Every
     * other SPI method throws.
     */
    private static final class ScopedStore implements WorkflowStore {

        private final List<String> scopedServiceNames = new CopyOnWriteArrayList<>();
        private final AtomicInteger unscopedCalls = new AtomicInteger();
        private final Deque<PurgeResult> scripted = new ArrayDeque<>();

        synchronized void script(PurgeResult... results) {
            scripted.addAll(List.of(results));
        }

        List<String> scopedServiceNames() {
            return scopedServiceNames;
        }

        int unscopedCalls() {
            return unscopedCalls.get();
        }

        @Override
        public synchronized PurgeResult purgeTerminalInstancesBefore(String serviceName, WorkflowStatus status,
                                                                     Instant cutoff, int batchSize) {
            scopedServiceNames.add(serviceName);
            var next = scripted.pollFirst();
            return next != null ? next : PurgeResult.EMPTY;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            unscopedCalls.incrementAndGet();
            return PurgeResult.EMPTY;
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
            return new UnsupportedOperationException("not exercised by RetentionSweeperScopeTest");
        }
    }
}
