package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("unpark() with no parked thread is a no-op")
    void unparkWithNoParkedThread() {
        // Should not throw
        parkingLot.unpark("nonexistent:signal:foo", "payload");
        assertEquals(0, parkingLot.parkedCount(), "No futures should be registered");
    }

    @Test
    @DisplayName("isParked() returns false when not parked")
    void isParkedReturnsFalseWhenNotParked() {
        assertFalse(parkingLot.isParked("nonexistent:signal:foo"));
    }

    @Test
    @DisplayName("unparkAll() cancels all futures for a workflow")
    void unparkAllCancelsWorkflowFutures() throws Exception {
        var result1 = new AtomicReference<Object>();
        var result2 = new AtomicReference<Object>();
        var parked = new CountDownLatch(2);

        var thread1 = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                parkingLot.park("order-abc:signal:payment");
            } catch (CancellationException e) {
                result1.set(e);
            }
        });

        var thread2 = Thread.ofVirtual().start(() -> {
            parked.countDown();
            try {
                parkingLot.park("order-abc:timer:sleep-5");
            } catch (CancellationException e) {
                result2.set(e);
            }
        });

        assertTrue(parked.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.parkedCount() == 2);

        parkingLot.unparkAll("order-abc");

        thread1.join(Duration.ofSeconds(5));
        thread2.join(Duration.ofSeconds(5));

        assertTrue(result1.get() instanceof CancellationException,
                "First thread should receive CancellationException");
        assertTrue(result2.get() instanceof CancellationException,
                "Second thread should receive CancellationException");
    }

    @Test
    @DisplayName("unparkAll() does not affect other workflows")
    void unparkAllDoesNotAffectOtherWorkflows() throws Exception {
        var otherResult = new AtomicReference<Object>();
        var parkedBoth = new CountDownLatch(2);

        // Park two workflows
        Thread.ofVirtual().start(() -> {
            parkedBoth.countDown();
            try {
                parkingLot.park("order-abc:signal:payment");
            } catch (CancellationException ignored) {}
        });

        var otherThread = Thread.ofVirtual().start(() -> {
            parkedBoth.countDown();
            otherResult.set(parkingLot.park("order-xyz:signal:payment"));
        });

        assertTrue(parkedBoth.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.parkedCount() == 2);

        // Cancel only order-abc
        parkingLot.unparkAll("order-abc");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertTrue(
                parkingLot.isParked("order-xyz:signal:payment"),
                "Other workflow should still be parked"));

        // Clean up the other thread
        parkingLot.unpark("order-xyz:signal:payment", "done");
        otherThread.join(Duration.ofSeconds(5));
        assertEquals("done", otherResult.get());
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
