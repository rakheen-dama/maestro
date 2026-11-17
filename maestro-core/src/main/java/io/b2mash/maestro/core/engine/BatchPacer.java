package io.b2mash.maestro.core.engine;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Paces a housekeeping sweep that runs many store batches back to back: before
 * every batch but a sweep's first, the sweep waits as long as the batch before
 * it took, at most {@link #MAX_PAUSE}. The database is then busy with the
 * sweep about half the time the sweep runs, instead of all of it.
 *
 * <p>A batch's duration is measured on a monotonic clock ({@link System#nanoTime()}
 * in production) around the store call only. A pause of zero does not wait.
 * The cap is a third of the retention leader's TTL, so a pause on its own never
 * makes the retention sweeper lose its leadership while its batches take under
 * ten seconds.
 *
 * <p>A pause waits on a stop latch, never by sleeping: {@link #requestStop()}
 * counts the latch down and every pause in progress, and every later one, ends
 * at once, without interrupting the waiting thread. An interrupt also ends a
 * pause; the interrupt status is then restored.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #requestStop()} may be called from any thread, any
 * number of times; two threads pausing at once both end on the same stop.
 */
final class BatchPacer {

    /** The longest pause between two batches. */
    static final Duration MAX_PAUSE = Duration.ofSeconds(5);

    /**
     * How a pause waits: until {@code stop} is counted down or {@code pause}
     * has passed, whichever comes first.
     */
    @FunctionalInterface
    interface Wait {

        /**
         * Waits for the stop latch or the pause.
         *
         * @param stop  counted down once a stop is requested
         * @param pause how long to wait at most; always positive
         * @return {@code true} if the wait ended because a stop was requested,
         *         {@code false} if the pause passed
         * @throws InterruptedException if the waiting thread is interrupted
         */
        boolean await(CountDownLatch stop, Duration pause) throws InterruptedException;
    }

    /** Waits on the stop latch itself; the production {@link Wait}. */
    static final Wait LATCH = (stop, pause) -> stop.await(pause.toNanos(), TimeUnit.NANOSECONDS);

    private final CountDownLatch stop = new CountDownLatch(1);
    private final LongSupplier nanoClock;
    private final Wait wait;

    /** A pacer on {@link System#nanoTime()} that waits on its stop latch. */
    BatchPacer() {
        this(System::nanoTime, LATCH);
    }

    /**
     * A pacer on the given clock and wait (a test seam).
     *
     * @param nanoClock a monotonic clock in nanoseconds
     * @param wait      how a pause waits
     * @throws NullPointerException if either argument is {@code null}
     */
    BatchPacer(LongSupplier nanoClock, Wait wait) {
        if (nanoClock == null) throw new NullPointerException("nanoClock must not be null");
        if (wait == null) throw new NullPointerException("wait must not be null");
        this.nanoClock = nanoClock;
        this.wait = wait;
    }

    /**
     * The current reading of this pacer's clock, for timing a batch.
     *
     * @return the clock's value in nanoseconds
     */
    long now() {
        return nanoClock.getAsLong();
    }

    /**
     * The pause that follows a batch of the given duration.
     *
     * @param batchNanos how long the batch took; negative counts as zero
     * @return {@code min(batch, MAX_PAUSE)}, never negative
     */
    static Duration pauseFor(long batchNanos) {
        if (batchNanos <= 0) {
            return Duration.ZERO;
        }
        return batchNanos >= MAX_PAUSE.toNanos() ? MAX_PAUSE : Duration.ofNanos(batchNanos);
    }

    /**
     * Pauses before the next batch for {@link #pauseFor(long) pauseFor(batchNanos)}.
     *
     * @param batchNanos how long the previous batch took
     * @return {@code true} if the sweep may run its next batch; {@code false}
     *         if a stop was requested before or during the pause, or the
     *         waiting thread was interrupted (its interrupt status is restored)
     */
    boolean pauseAfter(long batchNanos) {
        if (stopRequested()) {
            return false;
        }
        var pause = pauseFor(batchNanos);
        if (pause.isZero()) {
            return true;
        }
        try {
            return !wait.await(stop, pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Ends every pause in progress and every later one at once. Never interrupts. Idempotent. */
    void requestStop() {
        stop.countDown();
    }

    /**
     * Whether {@link #requestStop()} has been called.
     *
     * @return {@code true} once a stop was requested
     */
    boolean stopRequested() {
        return stop.getCount() == 0;
    }
}
