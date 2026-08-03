package io.b2mash.maestro.core;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link TestTerminalWait#isFinalised} — the predicate every engine
 * suite's terminal wait now routes through. It exists to close a real
 * two-write window, so its own edge cases are worth pinning directly rather
 * than only through the suites that consume it.
 */
@DisplayName("A run is finalised only when the event matching its status is in the log")
class TestTerminalWaitTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();

    @Test
    @DisplayName("a terminal status with no terminal event yet is NOT finalised — this is the window")
    void terminalStatusWithoutItsEvent_isNotFinalised() {
        var store = new FixedStore(List.of(event(1, EventType.ACTIVITY_COMPLETED)));

        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.COMPLETED)),
                "the instance row is written before the event is appended; waiting on the "
                        + "status alone returns while the log is still one event short");
        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.FAILED)));
    }

    @Test
    @DisplayName("a COMPLETED run is not satisfied by a leftover WORKFLOW_FAILED from an earlier attempt")
    void completedRun_isNotSatisfiedByAStaleFailureEvent() {
        // The shape a retried workflow would have if its store did NOT strip
        // failure memos: attempt 1 failed, attempt 2 has just flipped the row to
        // COMPLETED and has not appended WORKFLOW_COMPLETED yet.
        var store = new FixedStore(List.of(
                event(1, EventType.ACTIVITY_COMPLETED),
                event(2, EventType.WORKFLOW_FAILED)));

        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.COMPLETED)),
                "accepting EITHER terminal event would let the stale WORKFLOW_FAILED satisfy "
                        + "the wait, and the caller would read a log with no WORKFLOW_COMPLETED. "
                        + "The predicate must not lean on deleteFailureEvents having run first.");
    }

    @Test
    @DisplayName("the matching terminal event finalises the run")
    void matchingTerminalEvent_finalisesTheRun() {
        assertTrue(TestTerminalWait.isFinalised(
                new FixedStore(List.of(event(1, EventType.WORKFLOW_COMPLETED))),
                instance(WorkflowStatus.COMPLETED)));
        assertTrue(TestTerminalWait.isFinalised(
                new FixedStore(List.of(event(1, EventType.WORKFLOW_FAILED))),
                instance(WorkflowStatus.FAILED)));
    }

    @Test
    @DisplayName("TERMINATED is exempt — terminating appends no event, so requiring one would hang")
    void terminated_isExemptBecauseItAppendsNoEvent() {
        assertTrue(TestTerminalWait.isFinalised(new FixedStore(List.of()),
                instance(WorkflowStatus.TERMINATED)));
    }

    @Test
    @DisplayName("a non-terminal status is never finalised, whatever the log holds")
    void nonTerminalStatus_isNeverFinalised() {
        var store = new FixedStore(List.of(event(1, EventType.WORKFLOW_COMPLETED)));

        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.RUNNING)));
        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.WAITING_SIGNAL)));
        assertFalse(TestTerminalWait.isFinalised(store, instance(WorkflowStatus.COMPENSATING)));
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static WorkflowEvent event(int seq, EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, seq, type, null, null, Instant.now());
    }

    private static WorkflowInstance instance(WorkflowStatus status) {
        return WorkflowInstance.builder()
                .id(INSTANCE_ID)
                .workflowId("wf-1")
                .runId(UUID.randomUUID())
                .workflowType("Trivial")
                .taskQueue("default")
                .status(status)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * A store whose only real behaviour is {@code getEvents} — the single call
     * the predicate makes. Everything else is out of scope and fails loudly if
     * the predicate ever grows a dependency on it.
     *
     * <h2>Thread Safety</h2>
     * <p>Immutable; safe for any thread.
     */
    private record FixedStore(List<WorkflowEvent> events) implements WorkflowStore {

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return new ArrayList<>(events);
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteFailureEvents(UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            throw new UnsupportedOperationException();
        }
    }
}
