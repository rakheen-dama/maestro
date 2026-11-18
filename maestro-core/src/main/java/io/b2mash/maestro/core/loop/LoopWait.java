package io.b2mash.maestro.core.loop;

/**
 * A wait between iterations that the supervisor does not own — the recovery
 * poller's wait for task-queue capacity — returned through
 * {@link Next#await(LoopWait)}. Internal until 1.0 (see the package Javadoc).
 *
 * <p>The wait must end promptly once a stop is requested: the loop's
 * {@link LoopSpec#onStop()} hooks are how a stop reaches it (for example by
 * signalling the condition it waits on). It is never interrupted.
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
