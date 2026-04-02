package io.b2mash.maestro.test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deterministic clock for workflow tests.
 *
 * <p>Starts at {@link Instant#now()} and only advances when
 * {@link #advance(Duration)} is called. Used by
 * {@link TestWorkflowEnvironment#advanceTime(Duration)} to fire
 * due timers without relying on wall-clock time.
 *
 * <p><b>Thread safety:</b> All operations are atomic via {@link AtomicReference}.
 */
public final class ControllableClock {

    private final AtomicReference<Instant> now;

    /**
     * Creates a clock starting at the current wall-clock time.
     */
    public ControllableClock() {
        this.now = new AtomicReference<>(Instant.now());
    }

    /**
     * Creates a clock starting at the given instant.
     *
     * @param startTime the initial time
     */
    public ControllableClock(Instant startTime) {
        this.now = new AtomicReference<>(startTime);
    }

    /**
     * Returns the current clock time.
     *
     * @return the current instant
     */
    public Instant now() {
        return now.get();
    }

    /**
     * Advances the clock by the given duration.
     *
     * @param duration the amount to advance (must be non-negative)
     * @throws IllegalArgumentException if duration is negative
     */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot advance clock by negative duration: " + duration);
        }
        now.updateAndGet(current -> current.plus(duration));
    }

    /**
     * Sets the clock to a specific instant.
     *
     * <p>Use sparingly — {@link #advance(Duration)} is preferred for
     * most test scenarios as it's more readable.
     *
     * @param instant the new clock time
     */
    public void setTime(Instant instant) {
        now.set(instant);
    }
}
