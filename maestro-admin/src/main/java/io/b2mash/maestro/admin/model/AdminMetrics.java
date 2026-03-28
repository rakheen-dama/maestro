package io.b2mash.maestro.admin.model;

import java.time.Instant;

/**
 * Pre-computed workflow count by service and status.
 * Updated transactionally by the event projector.
 */
public record AdminMetrics(
    String serviceName,
    String status,
    long workflowCount,
    Instant lastUpdatedAt
) {}
