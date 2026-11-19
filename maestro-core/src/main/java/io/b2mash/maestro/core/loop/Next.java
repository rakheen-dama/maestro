package io.b2mash.maestro.core.loop;

import java.time.Duration;
import java.util.Objects;

/**
 * What follows a successful iteration of a {@link LoopBody}. Internal until
 * 1.0 (see the package Javadoc).
 *
 * <p><b>Thread safety:</b> immutable.
 */
public sealed interface Next {

    /**
     * @return wait the spec's {@link LoopSpec#period() period}
     */
    static Next afterPeriod() {
        return AfterPeriod.INSTANCE;
    }

    /**
     * @param wait how long to wait before the next iteration; zero or positive
     * @return wait {@code wait}
     * @throws IllegalArgumentException if {@code wait} is negative
     */
    static Next after(Duration wait) {
        return new After(wait);
    }

    /**
     * @return run the next iteration at once
     */
    static Next immediately() {
        return Immediately.INSTANCE;
    }

    /**
     * @param wait the wait to run before the next iteration
     * @return run {@code wait}, outside the iteration's timing
     */
    static Next await(LoopWait wait) {
        return new Await(wait);
    }

    /**
     * @return the loop's work is complete: it ends {@link LoopSnapshot.State#DONE},
     *         not {@link LoopSnapshot.State#DEAD}
     */
    static Next done() {
        return Done.INSTANCE;
    }

    /** Wait the spec's period. */
    record AfterPeriod() implements Next {
        static final AfterPeriod INSTANCE = new AfterPeriod();
    }

    /**
     * Wait a given time.
     *
     * @param delay the wait
     */
    record After(Duration delay) implements Next {
        /** @throws IllegalArgumentException if {@code delay} is negative */
        public After {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative, got " + delay);
            }
        }
    }

    /** Run the next iteration at once. */
    record Immediately() implements Next {
        static final Immediately INSTANCE = new Immediately();
    }

    /**
     * Run a custom wait.
     *
     * @param loopWait the wait
     */
    record Await(LoopWait loopWait) implements Next {
        /** @throws NullPointerException if {@code loopWait} is {@code null} */
        public Await {
            Objects.requireNonNull(loopWait, "loopWait");
        }
    }

    /** The loop is done. */
    record Done() implements Next {
        static final Done INSTANCE = new Done();
    }
}
