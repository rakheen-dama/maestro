package io.b2mash.maestro.store.postgres.schema;

/**
 * What {@link MaestroSchemaInitializer} does with Maestro's own tables at
 * startup. Bound from {@code maestro.store.schema.mode}
 * ({@code flyway | validate | none}, default {@code flyway}).
 *
 * <p>Whatever the mode, the host application's own Flyway (its
 * {@code spring.flyway.*} settings and its {@code flyway_schema_history}) is
 * never touched.
 */
public enum SchemaMode {

    /**
     * Apply every pending Maestro migration, recorded in
     * {@link MaestroSchemaInitializer#HISTORY_TABLE}. The default.
     */
    FLYWAY,

    /**
     * Apply nothing; fail startup if any Maestro migration is pending or an
     * applied one no longer matches the shipped SQL. For deployments whose DDL
     * is applied out of band, but still recorded in
     * {@link MaestroSchemaInitializer#HISTORY_TABLE}.
     */
    VALIDATE,

    /**
     * Do nothing at all: the operator owns Maestro's DDL entirely and Maestro
     * neither reads nor writes any Flyway history.
     */
    NONE;

    /**
     * Parses a {@code maestro.store.schema.mode} value, ignoring case and
     * surrounding whitespace.
     *
     * @param value the configured value, e.g. {@code "flyway"}
     * @return the mode
     * @throws IllegalArgumentException if the value names no mode
     */
    public static SchemaMode fromProperty(String value) {
        for (var mode : values()) {
            if (mode.name().equalsIgnoreCase(value.strip())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid maestro.store.schema.mode '" + value
                + "': expected one of flyway, validate, none");
    }
}
