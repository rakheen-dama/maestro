/**
 * The loop supervisor: one owner for every background loop's thread, failure
 * streak, last success, wait and stop (pilot readiness P2).
 *
 * <p>A loop is a {@link io.b2mash.maestro.core.loop.LoopBody} — one iteration
 * at a time — run under a {@link io.b2mash.maestro.core.loop.LoopSupervisor}
 * according to a {@link io.b2mash.maestro.core.loop.LoopSpec}. The supervisor
 * rethrows a {@code MaestroControlFlowError} and contains every other
 * {@code Throwable} an iteration throws, logs a failing loop at one cadence,
 * reports a contained {@code Error} through
 * {@code EngineObserver.backgroundLoopError}, and stops by a latch: it never
 * interrupts a loop's thread, so a store call in progress is never
 * interrupted. Health and meters read its
 * {@link io.b2mash.maestro.core.loop.LoopSnapshot}.
 *
 * <p><b>Internal until 1.0.</b> The package is public only because
 * {@code maestro-store-postgres} declares loops through it; hosts are not
 * meant to implement or call it, and it may change in any release before 1.0
 * (as the {@code WorkflowStore} SPI, see {@code docs/support-matrix.md}). A
 * loop is declared through {@code WorkflowExecutor.supervise(LoopSpec, LoopBody)},
 * so it gets the executor's observer and is stopped by the executor's
 * shutdown; {@code WorkflowExecutor.loops()} lists every supervised loop.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.b2mash.maestro.core.loop;

import org.jspecify.annotations.NullMarked;
