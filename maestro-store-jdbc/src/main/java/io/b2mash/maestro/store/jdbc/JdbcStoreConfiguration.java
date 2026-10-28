package io.b2mash.maestro.store.jdbc;

import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Configuration for {@link AbstractJdbcWorkflowStore}.
 *
 * <p>Bundles the Jackson {@link ObjectMapper} needed by the JDBC store.
 * Immutable after construction. Maestro tables are always named
 * {@value #TABLE_PREFIX}{@code *}; to isolate services sharing a database,
 * give each its own Postgres schema (JDBC {@code currentSchema}).
 *
 * @param objectMapper Jackson 3 {@link ObjectMapper} for JSON serialization
 */
public record JdbcStoreConfiguration(
        ObjectMapper objectMapper
) {

    /** Fixed prefix of every Maestro table name. */
    public static final String TABLE_PREFIX = "maestro_";

    /**
     * Validates that the mapper is not {@code null}.
     */
    public JdbcStoreConfiguration {
        Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Creates a configuration for the given mapper.
     *
     * @param objectMapper Jackson 3 {@link ObjectMapper}
     * @return a new configuration
     */
    public static JdbcStoreConfiguration withDefaults(ObjectMapper objectMapper) {
        return new JdbcStoreConfiguration(objectMapper);
    }
}
