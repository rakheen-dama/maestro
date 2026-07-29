package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParkingLot}.
 *
 * <p>Tests verify the park/unpark mechanism using virtual threads,
 * timeout behavior, and shutdown cancellation.
 */
class ParkingLotTest {

    private final ParkingLot parkingLot = new ParkingLot();

    @Test
    @DisplayName("park() blocks until unpark() delivers a payload")
    void parkAndUnpark() throws Exception {
        var key = "order-abc:signal:payment.result";
        var payload = "payment-success";
        var result = new AtomicReference<Object>();
        var parked = new CountDownLatch(1);

        var thread = Thread.ofVirtual().start(() -> {
            parked.countDown();
            result.set(parkingLot.park(key));
        });

        // Wait for thread to park
        assertTrue(parked.await(5, TimeUnit.SECONDS), "Thread should park within timeout");
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked(key));
        parkingLot.unpark(key, payload);

        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive(), "Thread should have resumed and completed");
        assertEquals(payload, result.get(), "Park should return the unparked payload");
        assertFalse(parkingLot.isParked(key), "Key should be removed after unpark");
    }

    @Test
    @DisplayName("park() with null payload returns null")
    void parkWithNullPayload() throws Exception {
        var key = "order-abc:timer:sleep-1";
        var result = new AtomicReference<>(new Object()); // sentinel to detect null
        var parked = new CountDownLatch(1);

        var thread = Thread.ofVirtual().start(() -> {
            parked.countDown();
            result.set(parkingLot.park(key));
        });

        assertTrue(parked.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked(key));

        parkingLot.unpark(key, null);
        thread.join(Duration.ofSeconds(5));

        assertNull(result.get(), "Park should return null when unparked with null");
    }

    @Test
    @DisplayName("parkWithTimeout() returns payload within timeout")
    void parkWithTimeoutSuccess() throws Exception {
        var key = "order-abc:signal:approval";
        var payload = "approved";
        var result = new AtomicReference<Object>();
        var parked = new CountDownLatch(1);

        var thread = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                result.set(parkingLot.parkWithTimeout(key, Duration.ofSeconds(5)));
            } catch (TimeoutException e) {
                result.set(e);
            }
        });

        assertTrue(parked.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked(key));

        parkingLot.unpark(key, payload);
        thread.join(Duration.ofSeconds(5));

        assertEquals(payload, result.get(), "Should return payload before timeout");
    }

    @Test
    @DisplayName("parkWithTimeout() throws TimeoutException when timeout elapses")
    void parkWithTimeoutExpires() throws Exception {
        var key = "order-abc:signal:never-arriving";
        var result = new AtomicReference<Object>();
        var parked = new CountDownLatch(1);

        var thread = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                result.set(parkingLot.parkWithTimeout(key, Duration.ofMillis(100)));
            } catch (TimeoutException e) {
                result.set(e);
            }
        });

        assertTrue(parked.await(5, TimeUnit.SECONDS));
        thread.join(Duration.ofSeconds(5));

        assertTrue(result.get() instanceof TimeoutException,
                "Should throw TimeoutException when timeout elapses");
        assertFalse(parkingLot.isParked(key), "Key should be cleaned up after timeout");
    }

    @Test
    @DisplayName("unpark() with no parked thread stores a permit, not a future")
    void unparkWithNoParkedThread() {
        // Should not throw
        parkingLot.unpark("nonexistent:signal:foo", "payload");
        assertEquals(0, parkingLot.parkedCount(), "No futures should be registered");
        assertEquals(1, parkingLot.pendingWakeCount(), "A one-shot permit should be stored");
    }

    // ── Wake permits (missed-unpark race) ───────────────────────────────

    @Test
    @DisplayName("unpark before park stores a permit the next park consumes immediately")
    void unparkBeforeParkIsNotLost() throws Exception {
        var key = "order-abc:timer:sleep-3";

        // Timer fires before the workflow thread reaches park() — the exact
        // race that permanently strands a sleeping workflow without permits
        parkingLot.unpark(key, "fired-early");

        var result = new AtomicReference<Object>();
        var thread = Thread.ofVirtual().start(() -> result.set(parkingLot.park(key)));
        thread.join(Duration.ofSeconds(5));

        assertFalse(thread.isAlive(), "park must consume the permit and return immediately");
        assertEquals("fired-early", result.get());
        assertEquals(0, parkingLot.pendingWakeCount(), "permit must be consumed");
    }

    @Test
    @DisplayName("permits are one-shot — a second park blocks")
    void permitIsOneShot() throws Exception {
        var key = "order-abc:signal:approval";
        parkingLot.unpark(key, "first");
        parkingLot.unpark(key, "second"); // latest-wins, still one permit

        assertEquals("second", parkingLot.park(key), "single permit carries the latest payload");

        var parked = new CountDownLatch(1);
        var thread = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                parkingLot.parkWithTimeout(key, Duration.ofMillis(200));
            } catch (TimeoutException expected) {
                // the permit was consumed — this park must genuinely wait
            }
        });
        assertTrue(parked.await(5, TimeUnit.SECONDS));
        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive());
        assertEquals(0, parkingLot.pendingWakeCount());
    }

    @Test
    @DisplayName("parkWithTimeout consumes a stored permit immediately")
    void parkWithTimeoutConsumesPermit() throws Exception {
        var key = "order-abc:signal:payment.result";
        parkingLot.unpark(key, "early-signal");

        var result = parkingLot.parkWithTimeout(key, Duration.ofMillis(50));

        assertEquals("early-signal", result,
                "a pre-stored permit must satisfy parkWithTimeout without waiting");
    }

    @Test
    @DisplayName("clearPending removes a workflow's permits without touching others")
    void clearPendingRemovesWorkflowPermits() {
        parkingLot.unpark("order-abc:signal:a", "x");
        parkingLot.unpark("order-abc:timer:t1", "y");
        parkingLot.unpark("order-xyz:signal:a", "z");

        parkingLot.clearPending("order-abc");

        assertEquals(1, parkingLot.pendingWakeCount(),
                "only the other workflow's permit should remain");
        assertEquals("z", parkingLot.park("order-xyz:signal:a"));
    }

    @Test
    @DisplayName("isParked() returns false when not parked")
    void isParkedReturnsFalseWhenNotParked() {
        assertFalse(parkingLot.isParked("nonexistent:signal:foo"));
    }

    @Test
    @DisplayName("shutdown() abandons every waiter with ExecutorShutdownException, not a failure")
    void shutdownAbandonsEveryWaiter() throws Exception {
        var result1 = new AtomicReference<Object>();
        var result2 = new AtomicReference<Object>();
        var parked = new CountDownLatch(2);

        var thread1 = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                parkingLot.park("order-abc:signal:payment");
            } catch (ExecutorShutdownException e) {
                result1.set(e);
            }
        });

        var thread2 = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                parkingLot.park("order-xyz:timer:sleep-5");
            } catch (ExecutorShutdownException e) {
                result2.set(e);
            }
        });

        assertTrue(parked.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.parkedCount() == 2);

        parkingLot.shutdown();

        thread1.join(Duration.ofSeconds(5));
        thread2.join(Duration.ofSeconds(5));

        // The exception type is the whole point: a CancellationException (or any
        // wrapper of it) reads to the executor as a workflow failure and would
        // fail — and compensate — a workflow that was merely parked.
        assertInstanceOf(ExecutorShutdownException.class, result1.get());
        assertInstanceOf(ExecutorShutdownException.class, result2.get());
    }

    @Test
    @DisplayName("A park that registers after shutdown() throws immediately instead of blocking")
    void parkAfterShutdownThrowsImmediately() {
        parkingLot.shutdown();

        assertThrows(ExecutorShutdownException.class,
                () -> parkingLot.park("order-abc:signal:payment"),
                "a park racing shutdown must fail fast, not block out its timeout and stall the drain");
        assertThrows(ExecutorShutdownException.class,
                () -> parkingLot.parkWithTimeout("order-abc:timer:sleep-5", Duration.ofMinutes(10)));
        assertEquals(0, parkingLot.parkedCount(), "a refused park must not leave its future registered");
    }

    @Test
    @DisplayName("Multiple park/unpark cycles on the same key work correctly")
    void multipleCyclesOnSameKey() throws Exception {
        var key = "order-abc:signal:approval";

        for (int i = 0; i < 3; i++) {
            var payload = "cycle-" + i;
            var result = new AtomicReference<Object>();
            var parked = new CountDownLatch(1);

            var thread = Thread.ofVirtual().start(() -> {
                parked.countDown();
                result.set(parkingLot.park(key));
            });

            assertTrue(parked.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked(key));

            parkingLot.unpark(key, payload);
            thread.join(Duration.ofSeconds(5));

            assertEquals(payload, result.get(), "Cycle " + i + " should deliver correct payload");
        }

        assertEquals(0, parkingLot.parkedCount(), "No futures should remain after all cycles");
    }
}
