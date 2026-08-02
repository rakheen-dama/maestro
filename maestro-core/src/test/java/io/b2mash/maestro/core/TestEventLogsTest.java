package io.b2mash.maestro.core;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link TestEventLogs#removeFailureEvents} — the shared delete logic
 * every in-core store fake delegates to. The fakes stand in for the shipped
 * stores across the engine suites, so the fake's contract must track the
 * {@code WorkflowStore#deleteFailureEvents} SPI contract exactly.
 */
class TestEventLogsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String json) {
        return MAPPER.readTree(json);
    }

    private static WorkflowEvent event(UUID instanceId, int seq, EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, null, null, Instant.now());
    }

    @Test
    void removesFailingTimeoutMemoEvenWhenCompensationEventsFollowIt() {
        // Regression pin (CodeRabbit wave, PR #30): an UNCAUGHT
        // SignalTimeoutException in a saga appends COMPENSATION_* events
        // between the failing SIGNAL_TIMEOUT memo and WORKFLOW_FAILED. The
        // failing memo is the highest-sequenced SIGNAL_TIMEOUT — NOT
        // necessarily the last memo before the terminal — and must still be
        // removed; the compensation memos must survive.
        var instanceId = UUID.randomUUID();
        var events = new CopyOnWriteArrayList<WorkflowEvent>(List.of(
                event(instanceId, 1, EventType.ACTIVITY_COMPLETED),
                event(instanceId, 2, EventType.SIGNAL_TIMEOUT),
                event(instanceId, 3, EventType.COMPENSATION_STARTED),
                event(instanceId, 4, EventType.COMPENSATION_STEP_COMPLETED),
                event(instanceId, 5, EventType.COMPENSATION_COMPLETED),
                new WorkflowEvent(UUID.randomUUID(), instanceId, 6, EventType.WORKFLOW_FAILED, null,
                        json("{\"exceptionType\":"
                                + "\"io.b2mash.maestro.core.exception.SignalTimeoutException\","
                                + "\"message\":\"signal 'failing-await' timed out\"}"),
                        Instant.now())));

        assertEquals(2, TestEventLogs.removeFailureEvents(events, instanceId),
                "WORKFLOW_FAILED plus the failing SIGNAL_TIMEOUT memo — even with "
                        + "compensation events between them");
        var remaining = events.stream().map(WorkflowEvent::eventType).toList();
        assertEquals(List.of(
                        EventType.ACTIVITY_COMPLETED,
                        EventType.COMPENSATION_STARTED,
                        EventType.COMPENSATION_STEP_COMPLETED,
                        EventType.COMPENSATION_COMPLETED),
                remaining, "compensation memos must survive; the failing timeout memo must not");
    }

    @Test
    void keepsCaughtGateTimeoutMemosWhenFailureCauseIsNotSignalTimeout() {
        // The discriminator side of the same contract: when the failure cause
        // is anything else, EVERY SIGNAL_TIMEOUT memo is a caught gate and
        // must survive — even the highest-sequenced one.
        var instanceId = UUID.randomUUID();
        var events = new CopyOnWriteArrayList<WorkflowEvent>(List.of(
                event(instanceId, 1, EventType.SIGNAL_TIMEOUT),
                event(instanceId, 2, EventType.ACTIVITY_COMPLETED),
                new WorkflowEvent(UUID.randomUUID(), instanceId, 3, EventType.WORKFLOW_FAILED, null,
                        json("{\"exceptionType\":\"java.lang.IllegalStateException\","
                                + "\"message\":\"escalation failed: "
                                + "io.b2mash.maestro.core.exception.SignalTimeoutException:"
                                + " signal 'approval' timed out\"}"),
                        Instant.now())));

        assertEquals(1, TestEventLogs.removeFailureEvents(events, instanceId),
                "only the WORKFLOW_FAILED terminal may be removed");
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.SIGNAL_TIMEOUT),
                "the caught-gate SIGNAL_TIMEOUT memo must survive — the failure cause "
                        + "(exceptionType) was not a SignalTimeoutException");
        assertFalse(events.stream().anyMatch(e -> e.eventType() == EventType.WORKFLOW_FAILED));
    }
}
