package io.maestro.admin.model;

import java.time.Instant;

/**
 * Represents a discovered service in the admin dashboard.
 * Projected from lifecycle events — services are auto-discovered
 * from the {@code serviceName} field.
 */
public record AdminService(
    String name,
    Instant firstSeenAt,
    Instant lastSeenAt
) {}
