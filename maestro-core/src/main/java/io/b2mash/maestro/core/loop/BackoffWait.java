package io.b2mash.maestro.core.loop;

import java.time.Duration;

/**
 * How a supervised loop waits out the back-off after a failed iteration.
 * Internal until 1.0 (see the package Javadoc). The default,
 * {@link #SLEEP}, sleeps the whole back-off on the stop latch; a body that has
 * a reason to retry sooner gives its spec its own
 * ({@link LoopSpec#withBackoffWait}) — the recovery poller ends the back-off
 * for a prompt pass (P2b review m2).
 *
 * <p>The wait must end promptly once a stop is requested, with the same
 * sticky-wake rule as {@link LoopWait}: the spec's {@link LoopSpec#onStop()}
 * hooks are how a stop reaches it. It must not end sooner than
 * {@code backoff} for anything but a stop or the body's own, bounded reason,
 * or a failing loop spins. The failure streak and its logging are the
 * supervisor's either way.
 */
@FunctionalInterface
public interface BackoffWait {

    /** Sleeps the whole back-off on the stop latch ({@link LoopContext#sleep}). */
    BackoffWait SLEEP = LoopContext::sleep;

    /**
     * Waits out (at most) {@code backoff}; runs on the loop's thread.
     *
     * @param ctx     the loop's context
     * @param backoff the back-off the spec's {@link Backoff} gives for the streak
     * @return {@code false} if the loop should stop
     * @throws InterruptedException if the thread was interrupted — the
     *         supervisor clears it and runs the next iteration
     */
    boolean await(LoopContext ctx, Duration backoff) throws InterruptedException;
}
