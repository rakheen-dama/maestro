package io.maestro.admin.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Projected workflow state in the admin dashboard.
 * Upserted from lifecycle events consumed from Kafka.
 */
public record AdminWorkflow(
    UUID workflowInstanceId,
    String workflowId,
    String workflowType,
    String serviceName,
    String taskQueue,
    String status,
    @Nullable String lastStepName,
    Instant startedAt,
    @Nullable Instant completedAt,
    Instant updatedAt,
    int eventCount
) {}
