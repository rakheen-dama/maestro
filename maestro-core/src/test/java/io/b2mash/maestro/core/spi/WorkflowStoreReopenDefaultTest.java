package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default {@link WorkflowStore#reopenFailedInstance}: a store that does not
 * override it keeps exactly the pre-WP3 two-step retry.
 */
class WorkflowStoreReopenDefaultTest {

    @Test
    void theDefaultIsThePreWp3TwoStepRetry() {
        var store = new RecordingStore();
        var now = Instant.now();
        var reopened = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("w")
                .runId(UUID.randomUUID())
                .workflowType("t")
                .taskQueue("q")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(4)
                .build();

        store.reopenFailedInstance(reopened, Set.of(9), Set.of("x"));

        assertEquals(List.of("deleteFailureEvents", "updateInstance"), store.calls);
    }

    /** Implements only the abstract methods, and records the name of each call. */
    private static final class RecordingStore implements WorkflowStore {

        final List<String> calls = new ArrayList<>();

        @Override public WorkflowInstance createInstance(WorkflowInstance instance) {
            calls.add("createInstance");
            return instance;
        }
        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            calls.add("getInstance");
            return Optional.empty();
        }
        @Override public List<WorkflowInstance> getRecoverableInstances() {
            calls.add("getRecoverableInstances");
            return List.of();
        }
        @Override public void updateInstance(WorkflowInstance instance) {
            calls.add("updateInstance");
        }
        @Override public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            calls.add("completeInstance");
            return true;
        }
        @Override public void appendEvent(WorkflowEvent event) {
            calls.add("appendEvent");
        }
        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            calls.add("getEventBySequence");
            return Optional.empty();
        }
        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            calls.add("getEvents");
            return List.of();
        }
        @Override public int deleteFailureEvents(UUID instanceId) {
            calls.add("deleteFailureEvents");
            return 0;
        }
        @Override public void saveSignal(WorkflowSignal signal) {
            calls.add("saveSignal");
        }
        @Override public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            calls.add("getUnconsumedSignals");
            return List.of();
        }
        @Override public boolean markSignalConsumed(UUID signalId) {
            calls.add("markSignalConsumed");
            return true;
        }
        @Override public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            calls.add("adoptOrphanedSignals");
        }
        @Override public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            calls.add("deleteUnconsumedSignalsForTerminalInstancesBefore");
            return 0;
        }
        @Override public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff,
                                                                  int batchSize) {
            calls.add("purgeTerminalInstancesBefore");
            return PurgeResult.EMPTY;
        }
        @Override public void saveTimer(WorkflowTimer timer) {
            calls.add("saveTimer");
        }
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            calls.add("getDueTimers");
            return List.of();
        }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            calls.add("findTimer");
            return Optional.empty();
        }
        @Override public boolean markTimerFired(UUID timerId) {
            calls.add("markTimerFired");
            return true;
        }
        @Override public boolean markTimerCancelled(UUID timerId) {
            calls.add("markTimerCancelled");
            return true;
        }
    }
}
