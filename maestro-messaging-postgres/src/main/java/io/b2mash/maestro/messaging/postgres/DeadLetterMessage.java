package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A queue row that exhausted its redelivery budget and was parked in
 * {@code DEAD_LETTER} status.
 *
 * <p>Returned by {@link PostgresWorkflowMessaging#listDeadLetterSignals} and
 * {@link PostgresWorkflowMessaging#listDeadLetterTasks} so a parked message can
 * be inspected and, once the underlying fault is fixed, replayed with
 * {@link PostgresWorkflowMessaging#replaySignal} /
 * {@link PostgresWorkflowMessaging#replayTask}.
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param id         the queue row ID, and the handle for replay
 * @param workflowId the workflow the message belongs to
 * @param name       the signal name for signals, the workflow type for tasks
 * @param payload    the message payload, or {@code null} if it had none
 * @param attempts   how many delivery attempts were spent
 * @param lastError  the failure that exhausted the budget, or {@code null}
 * @param createdAt  when the message was published
 */
@NullMarked
public record DeadLetterMessage(
        UUID id,
        String workflowId,
        String name,
        @Nullable JsonNode payload,
        int attempts,
        @Nullable String lastError,
        Instant createdAt
) {}
