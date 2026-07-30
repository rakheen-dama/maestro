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
     * events from a fake's event collection, leaving every other event intact.
     *
     * @param events     the fake's event collection, mutated in place
     * @param instanceId the workflow instance whose failure memos to drop
     * @return the number of events removed
     */
    public static int removeFailureEvents(Collection<WorkflowEvent> events, UUID instanceId) {
        var before = events.size();
        events.removeIf(event -> event.workflowInstanceId().equals(instanceId)
                && (event.eventType() == EventType.ACTIVITY_FAILED
                || event.eventType() == EventType.WORKFLOW_FAILED));
        return before - events.size();
    }
}
