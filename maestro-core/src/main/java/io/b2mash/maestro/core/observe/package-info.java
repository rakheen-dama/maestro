/**
 * Engine observation seam ({@link io.b2mash.maestro.core.observe.EngineObserver}):
 * synchronous, in-process callbacks the engine fires at execution boundaries.
 *
 * <p>maestro-core carries no metrics or tracing dependency; adapters live in
 * the Spring Boot starter. Callbacks that can fire during replay carry an
 * explicit {@code replayed} flag so counting/tracing adapters can skip them —
 * the engine always emits, keeping replay traffic visible to audit observers.
 *
 * <p>This seam is deliberately separate from the cross-process
 * {@link io.b2mash.maestro.core.spi.WorkflowLifecycleEvent} admin feed: the
 * two differ in consumers, gating, transport semantics and payload rules.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.NullMarked;
