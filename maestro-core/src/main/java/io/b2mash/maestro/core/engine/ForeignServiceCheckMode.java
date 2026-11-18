package io.b2mash.maestro.core.engine;

/**
 * What {@link WorkflowExecutor#checkForeignServices(ForeignServiceCheckMode)}
 * does when this service's Maestro tables hold active workflow instances
 * recorded under another {@code maestro.service-name}. Bound from
 * {@code maestro.store.foreign-service-check} in the Spring Boot starter
 * ({@code fail | warn | off}, default {@code fail}).
 *
 * <p>Services never share Maestro tables (pilot readiness P3,
 * {@code docs/support-matrix.md}); {@link #WARN} exists for a service rename
 * in progress, while the old name's instances drain or are moved.
 *
 * <p><b>Thread safety:</b> an enum; immutable.
 */
public enum ForeignServiceCheckMode {

    /** Rows found fail startup with {@code ForeignServiceRowsException}. The default. */
    FAIL,

    /** Rows found are logged, one {@code WARN} per other service name; startup continues. */
    WARN,

    /** Nothing is read. */
    OFF
}
