package io.b2mash.maestro.store.jdbc;

import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Configuration for {@link AbstractJdbcWorkflowStore}.
 *
 * <p>Bundles the table prefix and Jackson {@link ObjectMapper} needed by
 * the JDBC store. Immutable after construction.
 *
 * @param tablePrefix  prefix for all Maestro database tables (e.g., {@code "maestro_"})
 * @param objectMapper Jackson 3 {@link ObjectMapper} for JSON serialization
 */
public record JdbcStoreConfiguration(
        String tablePrefix,
        ObjectMapper objectMapper
) {

    /**
     * Validates that neither parameter is {@code null}.
     */
    public JdbcStoreConfiguration {
        Objects.requireNonNull(tablePrefix, "tablePrefix");
        Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Creates a configuration with the default {@code "maestro_"} table prefix.
     *
     * @param objectMapper Jackson 3 {@link ObjectMapper}
     * @return a new configuration with default prefix
     */
    public static JdbcStoreConfiguration withDefaults(ObjectMapper objectMapper) {
        return new JdbcStoreConfiguration("maestro_", objectMapper);
    }
}
