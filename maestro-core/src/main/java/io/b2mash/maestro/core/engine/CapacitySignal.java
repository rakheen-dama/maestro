package io.b2mash.maestro.core.engine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An event count: "a workflow slot or a recovery ticket was given back on
 * this node" (Issue 30).
 *
 * <p>The {@link TaskQueueGovernor} owning it calls {@link #signal()} whenever a
 * workflow permit or a recovery ticket is given back, and once when it shuts
 * down. The recovery poller reads {@link #epoch()} <em>before</em> a pass and,
 * after a pass that deferred instances for lack of capacity, waits in
 * {@link #awaitChange} for the count to move. A release during the pass has
 * already moved it, so the wait returns at once: no wake-up is lost.
 *
 * <p>A signal with no waiter costs one atomic increment; only when a thread is
 * waiting does it take the lock to wake it. Every give signals, useful or not
 * (a permit handed straight to a queued run frees nothing); the waiter filters
 * on headroom, so a spurious wake costs it an in-memory check, never a store
 * statement.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Any number of threads may signal; any number may wait.
 */
final class CapacitySignal {

    private final AtomicLong epoch = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    /** Threads inside {@link #awaitChange}; written under {@link #lock} only. */
    private volatile int waiters;

    /**
     * Returns the current count. Read it before the work whose outcome a later
     * {@link #awaitChange} waits on.
     *
     * @return the number of signals so far
     */
    long epoch() {
        return epoch.get();
    }

    /** Moves the count on and wakes every waiter. Never blocks for long: only to hand the wake-up over. */
    void signal() {
        epoch.incrementAndGet();
        // Ordered after the increment: a waiter registers before it checks the
        // count, so either it sees the new count or this sees it waiting.
        if (waiters > 0) {
            lock.lock();
            try {
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Waits until the count differs from {@code seen}, for at most {@code max}.
     *
     * @param seen the count read before the work this wait follows
     * @param max  the longest to wait; zero or negative only checks
     * @return {@code true} if the count moved, {@code false} if {@code max}
     *         elapsed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    boolean awaitChange(long seen, Duration max) throws InterruptedException {
        if (epoch.get() != seen) {
            return true;
        }
        var nanos = saturatedNanos(max);
        lock.lockInterruptibly();
        try {
            waiters++;
            try {
                while (epoch.get() == seen) {
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = changed.awaitNanos(nanos);
                }
                return true;
            } finally {
                waiters--;
            }
        } finally {
            lock.unlock();
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
