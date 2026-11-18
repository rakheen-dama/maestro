package io.b2mash.maestro.core.loop;

/**
 * One background loop's work, one iteration at a time; the
 * {@link LoopSupervisor} owns the thread, the waits and the stop. Internal
 * until 1.0 (see the package Javadoc).
 *
 * <p><b>Thread safety:</b> the supervisor calls {@link #iterate} and
 * {@link #onExit} on the loop's own thread only, one call at a time.
 */
@FunctionalInterface
public interface LoopBody {

    /**
     * Runs one iteration. Throw to report a failure — never swallow one: the
     * supervisor counts it, logs it at its cadence and waits the spec's
     * back-off. A {@code MaestroControlFlowError} ends the loop.
     *
     * <p>An iteration should check {@link LoopContext#stopRequested()} between
     * units of work (a batch, an instance) so a stop ends it promptly. The
     * supervisor never interrupts it.
     *
     * @param ctx the loop's context: its stop flag, a stop-aware sleep and
     *            informational notes
     * @return what follows a successful iteration
     * @throws Exception to report a failed iteration
     */
    Next iterate(LoopContext ctx) throws Exception;

    /**
     * Runs once on the loop's thread as the loop ends — stopped, done or dead
     * — before the loop is reported ended: release what the body holds. A
     * throwable from it is logged and contained (a control-flow
     * {@code Error} still propagates).
     */
    default void onExit() {
    }
}
