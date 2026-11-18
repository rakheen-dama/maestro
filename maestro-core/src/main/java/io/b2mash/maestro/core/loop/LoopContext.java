package io.b2mash.maestro.core.loop;

import java.time.Duration;

/**
 * What a {@link LoopBody} sees of its supervisor. Internal until 1.0 (see the
 * package Javadoc).
 *
 * <p><b>Thread safety:</b> for the loop's own thread, except
 * {@link #stopRequested()}, which any thread may read.
 */
public interface LoopContext {

    /**
     * @return {@code true} once {@link LoopSupervisor#requestStop()} was called
     */
    boolean stopRequested();

    /**
     * Waits {@code duration} on the loop's stop latch: a stop ends the wait at
     * once. Never interrupted; a stray interrupt of the thread is cleared and
     * the wait goes on.
     *
     * @param duration how long to wait; zero or negative returns at once
     * @return {@code false} if a stop was requested, {@code true} otherwise
     */
    boolean sleep(Duration duration);

    /**
     * Records a small informational detail for the loop's
     * {@link LoopSnapshot#notes()}, such as {@code "leader" -> true}; a later
     * note under the same key replaces it. Never use it for anything
     * unbounded.
     *
     * @param key   the detail's name
     * @param value its value
     */
    void note(String key, Object value);
}
