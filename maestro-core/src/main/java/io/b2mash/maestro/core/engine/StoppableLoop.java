package io.b2mash.maestro.core.engine;

import java.time.Duration;

/**
 * A background loop the executor stops in two halves: {@link #requestStop()}
 * never blocks, and {@link #awaitStopped(Duration) awaitStopped(bound)} waits
 * at most {@code bound}. So a shutdown can ask every loop to stop at once and
 * then join them all against one deadline, instead of giving each loop its
 * own timeout.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: both halves may be called from any thread, in any order and
 * more than once.
 */
interface StoppableLoop {

    /**
     * The loop's name, as {@link BackgroundLoopGuard} names it in logs and on
     * {@code maestro.loop.errors}.
     *
     * @return one of the {@link BackgroundLoopGuard} loop-name constants
     */
    String loopName();

    /**
     * Asks the loop to stop and returns at once: the loop starts no new
     * iteration. An iteration already inside a store call finishes it first.
     * Idempotent.
     */
    void requestStop();

    /**
     * Waits at most {@code bound} for the loop's thread to end. If the calling
     * thread is interrupted while it waits, its interrupt status is restored
     * and the wait ends as if the bound had passed.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the loop has ended (or never started),
     *         {@code false} if it is still running when the wait ends
     */
    boolean awaitStopped(Duration bound);
}
