package io.b2mash.maestro.core;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;

import java.util.Collection;
import java.util.UUID;

/**
 * Helpers shared by the in-memory {@code WorkflowStore} fakes across this
 * module's test suites.
 *
 * <p>Those fakes are deliberately per-test (each suite fakes only what it
 * needs), but
 * {@link io.b2mash.maestro.core.spi.WorkflowStore#deleteFailureEvents(UUID)}
 * has a contract subtle enough that nine independent copies would be nine
 * chances to get it wrong — the whole point is that it deletes the two failure
 * types and <em>nothing else</em>, because success and compensation memos are
 * what stop a retry re-running work that already happened.
 *
 * <p><b>Thread safety:</b> stateless. The collection passed in must itself be
 * safe for the calling pattern; the fakes use copy-on-write lists.
 */
public final class TestEventLogs {

    private TestEventLogs() {
    }

    /**
     * Removes an instance's {@code ACTIVITY_FAILED} and {@code WORKFLOW_FAILED}
     * events — plus, when the failure cause recorded in {@code WORKFLOW_FAILED}
     * was a {@code SignalTimeoutException}, the <em>failing</em>
     * {@code SIGNAL_TIMEOUT} memo (the highest-sequenced one — Issue 19). For
     * any other failure cause every {@code SIGNAL_TIMEOUT} memo is a caught
     * gate that must survive, preserving pre-failure replay determinism. All
     * other events stay intact.
     *
     * @param events     the fake's event collection, mutated in place
     * @param instanceId the workflow instance whose failure memos to drop
     * @return the number of events removed
     */
    public static int removeFailureEvents(Collection<WorkflowEvent> events, UUID instanceId) {
        var before = events.size();
        var failedByTimeout = events.stream()
                .filter(e -> e.workflowInstanceId().equals(instanceId)
                        && e.eventType() == EventType.WORKFLOW_FAILED
                        && e.payload() != null)
                .anyMatch(e -> e.payload().toString()
                        .contains("io.b2mash.maestro.core.exception.SignalTimeoutException"));
        var failingTimeoutSeq = events.stream()
                .filter(e -> e.workflowInstanceId().equals(instanceId)
                        && e.eventType() == EventType.SIGNAL_TIMEOUT)
                .mapToInt(WorkflowEvent::sequenceNumber)
                .max();
        events.removeIf(event -> event.workflowInstanceId().equals(instanceId)
                && (event.eventType() == EventType.ACTIVITY_FAILED
                || event.eventType() == EventType.WORKFLOW_FAILED
                || (failedByTimeout
                        && event.eventType() == EventType.SIGNAL_TIMEOUT
                        && failingTimeoutSeq.isPresent()
                        && event.sequenceNumber() == failingTimeoutSeq.getAsInt())));
        return before - events.size();
    }
}
