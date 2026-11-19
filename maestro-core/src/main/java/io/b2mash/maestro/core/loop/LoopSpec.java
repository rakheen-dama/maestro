package io.b2mash.maestro.core.loop;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What a supervised loop is and how it is run. Internal until 1.0 (see the
 * package Javadoc). Build one with {@link #of} and the {@code with…} methods.
 *
 * @param name         the loop's fixed, bounded name: its meter tag value and
 *                     log name ({@code timer-poller}, {@code signal-janitor}, …)
 * @param threadName   the name of the loop's virtual thread
 * @param role         how health reads its failure
 * @param initialDelay the wait before the first iteration: {@link Duration#ZERO}
 *                     runs it at once
 * @param period       the wait after a successful iteration, unless the body
 *                     returns another {@link Next}
 * @param backoff      the wait after a failed iteration
 * @param backoffWait  how that wait is waited: {@link BackoffWait#SLEEP} (the
 *                     whole back-off, ended only by a stop) unless the body
 *                     has a bounded reason to retry sooner
 * @param failing      when a failure streak counts as failing
 * @param onStop       run once, on the thread calling
 *                     {@link LoopSupervisor#requestStop()}, to end a wait the
 *                     supervisor does not own (a batch pacer, a capacity signal);
 *                     each must return at once and never interrupt
 */
public record LoopSpec(
        String name,
        String threadName,
        LoopRole role,
        Duration initialDelay,
        Duration period,
        Backoff backoff,
        BackoffWait backoffWait,
        FailingRule failing,
        List<Runnable> onStop
) {

    /**
     * @throws IllegalArgumentException if a name is blank, {@code initialDelay}
     *                                  is negative or {@code period} is not positive
     */
    public LoopSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(threadName, "threadName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(backoffWait, "backoffWait");
        Objects.requireNonNull(failing, "failing");
        onStop = List.copyOf(onStop);
        if (name.isBlank() || threadName.isBlank()) {
            throw new IllegalArgumentException("name and threadName must not be blank");
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative, got " + initialDelay);
        }
        if (!period.isPositive()) {
            throw new IllegalArgumentException("period must be positive, got " + period);
        }
    }

    /**
     * A loop that runs its first iteration at once, waits {@code period} after
     * every iteration — failed or not ({@link Backoff#fixed}) — reads failing
     * after two failures in a row, and has no stop hook.
     *
     * @param name       the loop's fixed name
     * @param threadName its thread's name
     * @param role       how health reads its failure
     * @param period     the wait after each iteration
     * @return the spec
     */
    public static LoopSpec of(String name, String threadName, LoopRole role, Duration period) {
        return new LoopSpec(name, threadName, role, Duration.ZERO, period, Backoff.fixed(period),
                BackoffWait.SLEEP, FailingRule.streak(2), List.of());
    }

    /**
     * @param delay the wait before the first iteration
     * @return this spec with that initial delay
     */
    public LoopSpec withInitialDelay(Duration delay) {
        return new LoopSpec(name, threadName, role, delay, period, backoff, backoffWait, failing, onStop);
    }

    /**
     * @param backoff the wait after a failed iteration
     * @return this spec with that back-off
     */
    public LoopSpec withBackoff(Backoff backoff) {
        return new LoopSpec(name, threadName, role, initialDelay, period, backoff, backoffWait, failing, onStop);
    }

    /**
     * @param wait how the back-off after a failed iteration is waited
     * @return this spec with {@code wait}
     */
    public LoopSpec withBackoffWait(BackoffWait wait) {
        return new LoopSpec(name, threadName, role, initialDelay, period, backoff, wait, failing, onStop);
    }

    /**
     * @param rule when a failure streak counts as failing
     * @return this spec with that rule
     */
    public LoopSpec withFailing(FailingRule rule) {
        return new LoopSpec(name, threadName, role, initialDelay, period, backoff, backoffWait, rule, onStop);
    }

    /**
     * @param hooks run once by {@link LoopSupervisor#requestStop()}
     * @return this spec with those stop hooks
     */
    public LoopSpec withOnStop(Runnable... hooks) {
        return new LoopSpec(name, threadName, role, initialDelay, period, backoff, backoffWait, failing,
                List.of(hooks));
    }
}
