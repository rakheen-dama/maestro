package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wake's post-put re-check removes only the permit that wake stored.
 *
 * <p>Every permit used to be the same shared marker, so a wake for an old run
 * whose re-check found the run gone could remove a permit a later wake had
 * stored for a NEW run of the same workflow id (an admin retry or a recovery
 * relaunching between the re-check and its {@code remove}). The new run then
 * parked for up to its wake-recheck interval.
 */
class ParkingLotPermitTokenTest {

    private static final String KEY = "wf:signal:go";

    /**
     * Forces the interleaving with the {@code runsHere} predicate as the hook:
     * the stale wake's second call (the re-check) waits until the old run has
     * ended, answers "not running", and then waits again before returning — so
     * its {@code remove} runs only after the new run's wake stored a permit.
     */
    @Test
    void aStaleWakesRecheckLeavesTheNextRunsPermit() throws Exception {
        Set<String> running = ConcurrentHashMap.newKeySet();
        running.add("wf");
        var atRecheck = new CountDownLatch(1);
        var oldRunGone = new CountDownLatch(1);
        var recheckAnswered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var staleCalls = new AtomicInteger();
        var staleWaker = new Thread[1];

        var lot = new ParkingLot(id -> {
            if (Thread.currentThread() == staleWaker[0] && staleCalls.incrementAndGet() == 2) {
                atRecheck.countDown();
                awaitLatch(oldRunGone);
                var answer = running.contains(id);
                recheckAnswered.countDown();
                awaitLatch(resume);
                return answer;
            }
            return running.contains(id);
        });

        staleWaker[0] = Thread.ofVirtual().unstarted(() -> lot.wake("wf", KEY));
        staleWaker[0].start();
        assertTrue(atRecheck.await(5, TimeUnit.SECONDS), "the stale wake did not reach its re-check");

        // The old run ends: its registration goes, then its permit cleanup runs.
        running.remove("wf");
        lot.clearPending("wf");
        oldRunGone.countDown();
        assertTrue(recheckAnswered.await(5, TimeUnit.SECONDS), "the stale re-check did not answer");

        // A new run of the same id registers, and a wake for it stores a permit.
        running.add("wf");
        lot.wake("wf", KEY);
        assertEquals(1, lot.pendingWakeCount(), "precondition: the new run's wake stored a permit");

        resume.countDown();
        staleWaker[0].join(Duration.ofSeconds(5));
        assertFalse(staleWaker[0].isAlive(), "the stale wake did not finish");

        assertEquals(1, lot.pendingWakeCount(), "the stale wake removed a permit it did not store");
        var started = System.nanoTime();
        assertNull(lot.parkWithTimeout(KEY, Duration.ofSeconds(30)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "the new run's park must return on its permit, not after a re-check");
        assertEquals(0, lot.pendingWakeCount());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
