package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimerManager}.
 */
class TimerManagerTest {

    private InMemoryWorkflowStore store;
    private TimerManager timerManager;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        timerManager = new TimerManager(store);
    }

    // ── cancelTimer ───────────────────────────────────────────────────

    @Test
    @DisplayName("cancelTimer transitions PENDING → CANCELLED")
    void cancelTimerPendingToCancelled() {
        var timerId = UUID.randomUUID();
        var timer = new WorkflowTimer(
                timerId, UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().plusSeconds(60), TimerStatus.PENDING, Instant.now());
        store.saveTimer(timer);

        timerManager.cancelTimer(timerId);

        var timers = store.getDueTimers(Instant.now().plusSeconds(120), 10);
        assertTrue(timers.isEmpty(), "Cancelled timer should not appear in due timers");
    }

    @Test
    @DisplayName("cancelTimer is idempotent on already-fired timer")
    void cancelTimerAlreadyFired() {
        var timerId = UUID.randomUUID();
        var timer = new WorkflowTimer(
                timerId, UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().plusSeconds(60), TimerStatus.PENDING, Instant.now());
        store.saveTimer(timer);
        store.markTimerFired(timerId);

        // Should not throw — idempotent
        timerManager.cancelTimer(timerId);
    }

    // ── getDueTimers ─────────────────────────────────────────────────

    @Test
    @DisplayName("getDueTimers returns only due PENDING timers")
    void getDueTimersReturnsPendingOnly() {
        var now = Instant.now();
        var instanceId = UUID.randomUUID();

        // Due and PENDING — should be returned
        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), instanceId, "wf-1", "sleep-1",
                now.minusSeconds(10), TimerStatus.PENDING, now));

        // Future PENDING — should NOT be returned
        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), instanceId, "wf-1", "sleep-2",
                now.plusSeconds(60), TimerStatus.PENDING, now));

        // Due but already FIRED — should NOT be returned
        var firedId = UUID.randomUUID();
        store.saveTimer(new WorkflowTimer(
                firedId, instanceId, "wf-1", "sleep-3",
                now.minusSeconds(5), TimerStatus.PENDING, now));
        store.markTimerFired(firedId);

        var dueTimers = timerManager.getDueTimers(10);
        assertEquals(1, dueTimers.size());
        assertEquals("sleep-1", dueTimers.getFirst().timerId());
    }

    @Test
    @DisplayName("getDueTimers respects batch size")
    void getDueTimersRespectsBatchSize() {
        var now = Instant.now();
        var instanceId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            store.saveTimer(new WorkflowTimer(
                    UUID.randomUUID(), instanceId, "wf-1", "sleep-" + i,
                    now.minusSeconds(10 + i), TimerStatus.PENDING, now));
        }

        var dueTimers = timerManager.getDueTimers(3);
        assertEquals(3, dueTimers.size());
    }

    // ── In-memory WorkflowStore ──────────────────────────────────────

    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, WorkflowInstance> instancesById = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            instancesById.put(instance.id(), instance);
            return instance;
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
            instancesById.put(instance.id(), instance);
        }

        @Override public void appendEvent(WorkflowEvent event) { events.add(event); }

        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == seq)
                    .findFirst();
        }

        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override public void saveSignal(WorkflowSignal signal) { signals.add(signal); }

        @Override public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId) && s.signalName().equals(signalName) && !s.consumed())
                    .toList();
        }

        @Override public void markSignalConsumed(UUID signalId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId)) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return;
                }
            }
        }

        @Override public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.workflowId().equals(workflowId) && s.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }

        @Override public void saveTimer(WorkflowTimer timer) { timers.add(timer); }

        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING && !t.fireAt().isAfter(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override public boolean markTimerFired(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.FIRED, t.createdAt()));
                    return true;
                }
            }
            return false;
        }

        @Override public void markTimerCancelled(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return;
                }
            }
        }
    }
}
