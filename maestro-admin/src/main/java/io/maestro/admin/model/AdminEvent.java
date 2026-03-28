package io.maestro.admin.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A lifecycle event stored in the admin timeline.
 * Each event is appended as-is from the Kafka consumer.
 */
public record AdminEvent(
    long id,
    UUID workflowInstanceId,
    String workflowId,
    String eventType,
    @Nullable String stepName,
    @Nullable String detail,
    Instant eventTimestamp,
    Instant receivedAt
) {}
