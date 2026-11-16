package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ParkingLot#wake(String, String)}'s gate (finding 3.2): a wake
 * that finds no registered waiter stores a permit only while the workflow runs
 * on this node, checked before the put and again after it; a registered
 * waiter is always woken.
 */
class ParkingLotWakeGateTest {

    @Test
    void aWakeForAWorkflowRunningHere_storesAPermitTheNextParkConsumes() throws Exception {
        var lot = new ParkingLot(id -> true);
        lot.wake("wf", "wf:signal:go");
        assertEquals(1, lot.pendingWakeCount());
        assertNull(lot.parkWithTimeout("wf:signal:go", Duration.ofSeconds(30)));   // returns at once
        assertEquals(0, lot.pendingWakeCount());
    }

    @Test
    void aWakeForAWorkflowNotRunningHere_storesNothing() {
        var lot = new ParkingLot(id -> false);
        lot.wake("wf", "wf:signal:go");
        assertEquals(0, lot.pendingWakeCount());
    }

    @Test
    void aWakeCompletesARegisteredWaiterWhateverThePredicateSays() throws Exception {
        var lot = new ParkingLot(id -> false);
        var outcome = new CompletableFuture<Object>();
        Thread.ofVirtual().start(() -> {
            try {
                lot.parkWithTimeout("wf:signal:go", Duration.ofSeconds(30));
                outcome.complete("woken");
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!lot.isParked("wf:signal:go") && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(lot.isParked("wf:signal:go"), "the waiter did not register within 5 s");

        lot.wake("wf", "wf:signal:go");

        var wokenBy = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!outcome.isDone() && System.nanoTime() < wokenBy) {
            Thread.sleep(5);
        }
        // "woken" means the park returned (null) — not a TimeoutException, which
        // only a 30 s wait could produce
        assertEquals("woken", outcome.getNow("still parked after 5 s"));
        assertEquals(0, lot.pendingWakeCount());
    }

    @Test
    void aRunEndingBetweenTheCheckAndThePut_leavesNoPermit() {
        var calls = new AtomicInteger();
        var lot = new ParkingLot(id -> calls.getAndIncrement() == 0);   // running at the check, gone at the re-check
        lot.wake("wf", "wf:signal:go");
        assertEquals(0, lot.pendingWakeCount());
        assertEquals(2, calls.get(), "checked before the put and again after it");
    }

    @Test
    void theNoArgumentLotTreatsEveryWorkflowAsRunningHere() {
        var lot = new ParkingLot();
        lot.wake("wf", "wf:timer:sleep-1");
        assertEquals(1, lot.pendingWakeCount());
    }
}
