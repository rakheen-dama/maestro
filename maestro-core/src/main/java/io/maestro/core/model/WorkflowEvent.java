package io.maestro.core.model;

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
 * @see EventType
 * @see io.maestro.core.spi.WorkflowStore#appendEvent(WorkflowEvent)
 */
public record WorkflowEvent(
        UUID id,
        UUID workflowInstanceId,
        int sequenceNumber,
        EventType eventType,
        @Nullable String stepName,
        @Nullable JsonNode payload,
        Instant createdAt
) {}
