package io.b2mash.maestro.core.loop;

/**
 * A wait between iterations that the supervisor does not own — the recovery
 * poller's wait for task-queue capacity — returned through
 * {@link Next#await(LoopWait)}. Internal until 1.0 (see the package Javadoc).
 *
 * <p>The wait must end promptly once a stop is requested. The loop's
 * {@link LoopSpec#onStop()} hooks are how a stop reaches it (for example by
 * signalling the condition it waits on), and they run once, inside
 * {@link LoopSupervisor#requestStop()} — possibly before this wait is armed.
 * The supervisor does not enter the wait if the stop came during the
 * iteration, but a stop can still land between that check and the moment the
 * wait arms itself. So the wake must be <b>sticky</b> (a latch, a permit that
 * stays set), or the wait must re-check {@link LoopContext#stopRequested()}
 * after arming and before blocking. It is never interrupted.
 */
@FunctionalInterface
public interface LoopWait {

    /**
     * Waits; runs on the loop's thread, outside the iteration's timing.
     *
     * @param ctx the loop's context
     * @return {@code false} if the loop should stop
     * @throws InterruptedException if the thread was interrupted — the
     *         supervisor clears it and runs the next iteration
     */
    boolean await(LoopContext ctx) throws InterruptedException;
}
