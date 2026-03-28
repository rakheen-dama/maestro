package io.b2mash.maestro.test;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryWorkflowStoreTest {

    private InMemoryWorkflowStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
    }

    // ── Instance operations ──────────────────────────────────────────────

    @Test
    void createAndGetInstance() {
        var instance = buildInstance("wf-1");
        store.createInstance(instance);

        var retrieved = store.getInstance("wf-1");
        assertTrue(retrieved.isPresent());
        assertEquals("wf-1", retrieved.get().workflowId());
        assertEquals(WorkflowStatus.RUNNING, retrieved.get().status());
    }

    @Test
    void createDuplicateThrows() {
        store.createInstance(buildInstance("wf-dup"));
        assertThrows(WorkflowAlreadyExistsException.class,
                () -> store.createInstance(buildInstance("wf-dup")));
    }

    @Test
    void getInstanceReturnsEmptyForUnknown() {
        assertTrue(store.getInstance("nonexistent").isEmpty());
    }

    @Test
    void updateInstanceWithCorrectVersion() {
        var instance = buildInstance("wf-update");
        store.createInstance(instance);

        var updated = instance.toBuilder()
                .status(WorkflowStatus.COMPLETED)
                .version(instance.version() + 1)
                .build();
        assertDoesNotThrow(() -> store.updateInstance(updated));

        var retrieved = store.getInstance("wf-update").orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, retrieved.status());
        assertEquals(1, retrieved.version());
    }

    @Test
    void updateInstanceWithStaleVersionThrows() {
        var instance = buildInstance("wf-stale");
        store.createInstance(instance);

        // First update succeeds (version 0 → 1)
        var updated1 = instance.toBuilder().version(1).build();
        store.updateInstance(updated1);

        // Second update with stale version 0 → 1 fails (current is already 1)
        var staleUpdate = instance.toBuilder().version(1).build();
        assertThrows(OptimisticLockException.class, () -> store.updateInstance(staleUpdate));
    }

    @Test
    void updateNonexistentInstanceThrows() {
        var instance = buildInstance("wf-ghost").toBuilder().version(1).build();
        assertThrows(WorkflowNotFoundException.class, () -> store.updateInstance(instance));
    }

    @Test
    void getRecoverableInstances() {
        store.createInstance(buildInstance("wf-running", WorkflowStatus.RUNNING));
        store.createInstance(buildInstance("wf-waiting", WorkflowStatus.WAITING_SIGNAL));
        store.createInstance(buildInstance("wf-completed", WorkflowStatus.COMPLETED));

        var recoverable = store.getRecoverableInstances();
        assertEquals(2, recoverable.size());
        assertTrue(recoverable.stream().anyMatch(i -> i.workflowId().equals("wf-running")));
        assertTrue(recoverable.stream().anyMatch(i -> i.workflowId().equals("wf-waiting")));
    }

    // ── Event operations ─────────────────────────────────────────────────

    @Test
    void appendAndGetEvent() {
        var instanceId = UUID.randomUUID();
        var event = new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1,
                EventType.ACTIVITY_COMPLETED, "step1",
                null, Instant.now());

        store.appendEvent(event);

        var retrieved = store.getEventBySequence(instanceId, 1);
        assertTrue(retrieved.isPresent());
        assertEquals(EventType.ACTIVITY_COMPLETED, retrieved.get().eventType());
    }

    @Test
    void appendDuplicateEventThrows() {
        var instanceId = UUID.randomUUID();
        var event1 = new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1,
                EventType.ACTIVITY_COMPLETED, "step1",
                null, Instant.now());
        var event2 = new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1,
                EventType.ACTIVITY_STARTED, "step1",
                null, Instant.now());

        store.appendEvent(event1);
        assertThrows(DuplicateEventException.class, () -> store.appendEvent(event2));
    }

    @Test
    void getEventBySequenceReturnsEmpty() {
        assertTrue(store.getEventBySequence(UUID.randomUUID(), 1).isEmpty());
    }

    @Test
    void getEventsOrderedBySequence() {
        var instanceId = UUID.randomUUID();
        // Insert out of order
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), instanceId, 3, EventType.ACTIVITY_COMPLETED,
                "step3", null, Instant.now()));
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1, EventType.ACTIVITY_STARTED,
                "step1", null, Instant.now()));
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), instanceId, 2, EventType.ACTIVITY_COMPLETED,
                "step2", null, Instant.now()));

        var events = store.getEvents(instanceId);
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).sequenceNumber());
        assertEquals(2, events.get(1).sequenceNumber());
        assertEquals(3, events.get(2).sequenceNumber());
    }

    @Test
    void getEventsForUnknownInstanceReturnsEmpty() {
        assertTrue(store.getEvents(UUID.randomUUID()).isEmpty());
    }

    // ── Signal operations ────────────────────────────────────────────────

    @Test
    void saveAndGetUnconsumedSignals() {
        var signal = new WorkflowSignal(
                UUID.randomUUID(), UUID.randomUUID(), "wf-sig",
                "payment.result", null, false, Instant.now());
        store.saveSignal(signal);

        var unconsumed = store.getUnconsumedSignals("wf-sig", "payment.result");
        assertEquals(1, unconsumed.size());
        assertEquals("payment.result", unconsumed.getFirst().signalName());
    }

    @Test
    void markSignalConsumed() {
        var signalId = UUID.randomUUID();
        var signal = new WorkflowSignal(
                signalId, UUID.randomUUID(), "wf-consume",
                "approved", null, false, Instant.now());
        store.saveSignal(signal);

        store.markSignalConsumed(signalId);

        var unconsumed = store.getUnconsumedSignals("wf-consume", "approved");
        assertTrue(unconsumed.isEmpty());
    }

    @Test
    void adoptOrphanedSignals() {
        // Signal arrives before workflow starts (instanceId = null)
        var signal = new WorkflowSignal(
                UUID.randomUUID(), null, "wf-orphan",
                "early.signal", null, false, Instant.now());
        store.saveSignal(signal);

        // Workflow starts and adopts orphans
        var instanceId = UUID.randomUUID();
        store.adoptOrphanedSignals("wf-orphan", instanceId);

        // Verify signal now has the instanceId
        var allSignals = store.getAllSignals();
        assertEquals(1, allSignals.size());
        assertEquals(instanceId, allSignals.getFirst().workflowInstanceId());
    }

    @Test
    void getUnconsumedSignalsFiltersByNameAndId() {
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), UUID.randomUUID(), "wf-filter",
                "signal-a", null, false, Instant.now()));
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), UUID.randomUUID(), "wf-filter",
                "signal-b", null, false, Instant.now()));
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), UUID.randomUUID(), "wf-other",
                "signal-a", null, false, Instant.now()));

        assertEquals(1, store.getUnconsumedSignals("wf-filter", "signal-a").size());
        assertEquals(1, store.getUnconsumedSignals("wf-filter", "signal-b").size());
        assertEquals(1, store.getUnconsumedSignals("wf-other", "signal-a").size());
    }

    // ── Timer operations ─────────────────────────────────────────────────

    @Test
    void saveAndGetDueTimers() {
        var now = Instant.now();
        var pastTimer = buildTimer("wf-timer", now.minusSeconds(10), TimerStatus.PENDING);
        var futureTimer = buildTimer("wf-timer", now.plusSeconds(60), TimerStatus.PENDING);

        store.saveTimer(pastTimer);
        store.saveTimer(futureTimer);

        var due = store.getDueTimers(now, 100);
        assertEquals(1, due.size());
        assertEquals(pastTimer.id(), due.getFirst().id());
    }

    @Test
    void getDueTimersSkipsFiredAndCancelled() {
        var now = Instant.now();
        store.saveTimer(buildTimer("wf-t1", now.minusSeconds(10), TimerStatus.PENDING));
        store.saveTimer(buildTimer("wf-t2", now.minusSeconds(10), TimerStatus.FIRED));
        store.saveTimer(buildTimer("wf-t3", now.minusSeconds(10), TimerStatus.CANCELLED));

        var due = store.getDueTimers(now, 100);
        assertEquals(1, due.size());
    }

    @Test
    void getDueTimersRespectsBatchSize() {
        var now = Instant.now();
        for (int i = 0; i < 5; i++) {
            store.saveTimer(buildTimer("wf-batch-" + i, now.minusSeconds(10), TimerStatus.PENDING));
        }

        var due = store.getDueTimers(now, 3);
        assertEquals(3, due.size());
    }

    @Test
    void markTimerFiredCAS() {
        var timer = buildTimer("wf-cas", Instant.now().minusSeconds(10), TimerStatus.PENDING);
        store.saveTimer(timer);

        // First fire succeeds
        assertTrue(store.markTimerFired(timer.id()));
        // Second fire returns false (already FIRED)
        assertFalse(store.markTimerFired(timer.id()));
    }

    @Test
    void markTimerCancelled() {
        var timer = buildTimer("wf-cancel", Instant.now().plusSeconds(60), TimerStatus.PENDING);
        store.saveTimer(timer);

        store.markTimerCancelled(timer.id());

        // Timer should no longer appear in due timers even if time passes
        var due = store.getDueTimers(Instant.now().plusSeconds(120), 100);
        assertTrue(due.isEmpty());
    }

    @Test
    void markTimerFiredReturnsFalseForCancelled() {
        var timer = buildTimer("wf-cancel-fire", Instant.now().minusSeconds(10), TimerStatus.PENDING);
        store.saveTimer(timer);

        store.markTimerCancelled(timer.id());
        assertFalse(store.markTimerFired(timer.id()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static WorkflowInstance buildInstance(String workflowId) {
        return buildInstance(workflowId, WorkflowStatus.RUNNING);
    }

    private static WorkflowInstance buildInstance(String workflowId, WorkflowStatus status) {
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-type")
                .taskQueue("test")
                .status(status)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0)
                .build();
    }

    private static WorkflowTimer buildTimer(String workflowId, Instant fireAt, TimerStatus status) {
        return new WorkflowTimer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                workflowId,
                "timer-" + UUID.randomUUID().toString().substring(0, 8),
                fireAt,
                status,
                Instant.now()
        );
    }
}
