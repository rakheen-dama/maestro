package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * An entry in the workflow memoization log.
 *
 * <p>Every activity invocation, signal receipt, timer event, and lifecycle
 * transition is recorded as a {@code WorkflowEvent}. During recovery,
 * events are replayed in {@link #sequenceNumber()} order to restore
 * workflow state without re-executing completed activities.
 *
 * <p>The combination of {@code (workflowInstanceId, sequenceNumber)} is
 * unique — enforced by a database constraint. This guarantees that each
 * checkpoint in the workflow is recorded exactly once.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param id                  primary key
 * @param workflowInstanceId  owning workflow instance UUID
 * @param sequenceNumber      position in the memoization log (1-based)
 * @param eventType           the type of event
 * @param stepName            name of the activity or step, {@code null} for lifecycle events
 * @param payload             event data (activity result, signal payload, etc.), may be {@code null}
 * @param createdAt           when the event was persisted
 * @param argsHash            SHA-256 fingerprint (64 lowercase hex characters) of the
 *                            arguments the activity was invoked with, written on
 *                            {@code ACTIVITY_COMPLETED} and {@code ACTIVITY_FAILED}
 *                            events so a replay can detect an activity being called
 *                            with different inputs than the recorded run
 *                            ({@code io.b2mash.maestro.core.engine.ArgumentFingerprint}).
 *                            {@code null} for every other event type, for rows written
 *                            before the column existed or by a node that predates it,
 *                            and when the arguments could not be fingerprinted — a
 *                            {@code null} is <em>unverified</em>, never a mismatch
 * @see EventType
 * @see io.b2mash.maestro.core.spi.WorkflowStore#appendEvent(WorkflowEvent)
 */
public record WorkflowEvent(
        UUID id,
        UUID workflowInstanceId,
        int sequenceNumber,
        EventType eventType,
        @Nullable String stepName,
        @Nullable JsonNode payload,
        Instant createdAt,
        @Nullable String argsHash
) {

    /**
     * Creates an event without an argument fingerprint — the form every event
     * other than an activity outcome takes.
     *
     * @param id                 primary key
     * @param workflowInstanceId owning workflow instance UUID
     * @param sequenceNumber     position in the memoization log (1-based)
     * @param eventType          the type of event
     * @param stepName           name of the activity or step, {@code null} for lifecycle events
     * @param payload            event data, may be {@code null}
     * @param createdAt          when the event was persisted
     */
    public WorkflowEvent(
            UUID id,
            UUID workflowInstanceId,
            int sequenceNumber,
            EventType eventType,
            @Nullable String stepName,
            @Nullable JsonNode payload,
            Instant createdAt
    ) {
        this(id, workflowInstanceId, sequenceNumber, eventType, stepName, payload, createdAt, null);
    }
}
