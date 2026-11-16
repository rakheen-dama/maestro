package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wake's hand-off to a raced waiter removes only the permit that wake
 * stored.
 *
 * <p>A wake that finds no waiter stores a permit, then looks again: a park may
 * have registered in between, and the wake hands the permit to it. The
 * hand-off used to remove whatever permit was in the slot. A wake held between
 * that look and the hand-off could therefore take a permit a LATER wake had
 * stored for the next park of the same run, after the raced waiter had been
 * woken by another wake and left; the next park then waited for its
 * wake-recheck interval although its signal was durable.
 */
class ParkingLotHandOffTest {

    private static final String KEY = "wf:signal:go";

    @Test
    void aHeldHandOffLeavesALaterWakesPermit() throws Exception {
        var aChecked = new CountDownLatch(1);
        var parkerParked = new CountDownLatch(1);
        var atHandOff = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var heldWakerCalls = new AtomicInteger();
        var heldWaker = new Thread[1];

        var lot = new ParkingLot(
                id -> {
                    // The held wake's run check sits between its waiter lookup
                    // (no waiter yet) and its put: the parker registers here.
                    if (Thread.currentThread() == heldWaker[0] && heldWakerCalls.incrementAndGet() == 1) {
                        aChecked.countDown();
                        awaitLatch(parkerParked);
                    }
                    return true;
                },
                () -> {
                    if (Thread.currentThread() == heldWaker[0]) {
                        atHandOff.countDown();
                        awaitLatch(resume);
                    }
                });

        heldWaker[0] = Thread.ofVirtual().unstarted(() -> lot.wake("wf", KEY));
        heldWaker[0].start();
        assertTrue(aChecked.await(5, TimeUnit.SECONDS), "the held wake did not reach its run check");

        // A park registers after the held wake's lookup and before its put.
        var parker = Thread.ofVirtual().start(() -> lot.park("wf", KEY));
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!(lot.isParked(KEY) && parker.getState() == Thread.State.WAITING)
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(lot.isParked(KEY), "precondition: the parker registered");
        parkerParked.countDown();
        assertTrue(atHandOff.await(5, TimeUnit.SECONDS),
                "the held wake did not find the raced waiter after its put");

        // Another wake reaches the waiter directly; it returns and unregisters.
        lot.wake("wf", KEY);
        parker.join(Duration.ofSeconds(5));
        assertFalse(parker.isAlive(), "the parker was not woken");

        // A later wake, for the run's next park on the key, stores its permit.
        lot.wake("wf", KEY);
        assertEquals(1, lot.pendingWakeCount(), "precondition: the later wake stored a permit");

        resume.countDown();
        heldWaker[0].join(Duration.ofSeconds(5));
        assertFalse(heldWaker[0].isAlive(), "the held wake did not finish");

        assertEquals(1, lot.pendingWakeCount(), "the held hand-off removed a permit it did not store");
        var started = System.nanoTime();
        assertNull(lot.parkWithTimeout("wf", KEY, Duration.ofSeconds(30)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "the next park must return on the later wake's permit, not after a re-check");
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
