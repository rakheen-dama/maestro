package io.b2mash.maestro.core.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One shutdown's time budget, measured on the monotonic clock.
 *
 * <p>{@link WorkflowExecutor#shutdown()} creates one first thing and gives
 * every bounded step what is left of it ({@link #remaining()}, or
 * {@link #remainingAtMost(Duration)} for a step with a cap of its own), so no
 * step owns a timeout of its own and the whole shutdown ends within the
 * budget plus bookkeeping.
 *
 * <p>The clock is {@link System#nanoTime()}, never the executor's injected
 * {@code InstantSource}: a frozen or controllable test clock must not stop a
 * deadline from passing, and a wall-clock jump must neither stretch nor cut
 * it.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable apart from the clock it reads: confined to the shutting-down
 * thread in use, and safe to read from any thread (a second
 * {@code shutdown()} caller reads {@link #remaining()}).
 */
final class ShutdownDeadline {

    private final Duration budget;
    private final LongSupplier nanoTime;
    private final long startNanos;

    private ShutdownDeadline(Duration budget, LongSupplier nanoTime) {
        this.budget = budget;
        this.nanoTime = nanoTime;
        this.startNanos = nanoTime.getAsLong();
    }

    /**
     * Starts a deadline {@code budget} from now on {@link System#nanoTime()}.
     *
     * @param budget the time the whole shutdown may take; positive
     * @return the started deadline
     * @throws IllegalArgumentException if {@code budget} is zero or negative
     */
    static ShutdownDeadline start(Duration budget) {
        return start(budget, System::nanoTime);
    }

    /**
     * Test seam: starts a deadline on the given monotonic clock.
     *
     * @param budget   the time the whole shutdown may take; positive
     * @param nanoTime a monotonic nanosecond clock; only differences are used
     * @return the started deadline
     * @throws IllegalArgumentException if {@code budget} is zero or negative
     */
    static ShutdownDeadline start(Duration budget, LongSupplier nanoTime) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(nanoTime, "nanoTime");
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("shutdown budget must be positive, got " + budget);
        }
        return new ShutdownDeadline(budget, nanoTime);
    }

    /** @return the whole budget this deadline was started with */
    Duration budget() {
        return budget;
    }

    /** @return the time since the deadline was started */
    Duration elapsed() {
        return Duration.ofNanos(nanoTime.getAsLong() - startNanos);
    }

    /** @return what is left of the budget; never negative, zero once passed */
    Duration remaining() {
        var left = budget.minus(elapsed());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * What is left of the budget, capped: for a step that would never wait
     * longer than {@code cap} even with time to spare.
     *
     * @param cap the step's own upper bound
     * @return {@code min(cap, remaining())}
     */
    Duration remainingAtMost(Duration cap) {
        var left = remaining();
        return left.compareTo(cap) < 0 ? left : cap;
    }

    /** @return {@code true} once the whole budget has been used */
    boolean passed() {
        return remaining().isZero();
    }
}
