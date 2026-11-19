package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopSupervisor;

import java.time.Duration;

/**
 * Test seam: a loop as the stop tests drive it — its name, a request to stop,
 * and a bounded wait. Production no longer uses it: since P1 task 3 every
 * supervised loop is a member of the executor's lifecycle registry, and
 * shutdown stops and waits for loops through the registry.
 */
interface StoppableLoop {

    /**
     * The loop's name in shutdown's log, as it is named in its other logs and
     * on {@code maestro.loop.errors}.
     *
     * @return the loop's fixed name
     */
    String loopName();

    /**
     * Asks the loop to stop and returns at once: the loop starts no new
     * iteration. A supervised loop is never interrupted; a store call already
     * in progress in the timer or recovery poller may still be (Issue 64,
     * until they move under the supervisor); a driver that ignores the
     * interrupt finishes the call first. Idempotent.
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

    /**
     * The shutdown seam of a supervised loop: its name, its
     * {@link LoopSupervisor#requestStop()} and its
     * {@link LoopSupervisor#awaitStopped(Duration)}. (P1's lifecycle registry
     * replaces this seam later; the two methods it calls stay.)
     *
     * @param supervisor the supervised loop
     * @return the loop as shutdown stops it
     */
    static StoppableLoop of(LoopSupervisor supervisor) {
        return new StoppableLoop() {
            @Override
            public String loopName() {
                return supervisor.name();
            }

            @Override
            public void requestStop() {
                supervisor.requestStop();
            }

            @Override
            public boolean awaitStopped(Duration bound) {
                return supervisor.awaitStopped(bound);
            }
        };
    }
}
