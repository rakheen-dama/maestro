package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6 D-3: the event count the recovery poller reads before a pass and waits
 * on after a pass that deferred, so a release during the pass is never missed.
 */
class CapacitySignalTest {

    @Test
    void aSignalDuringThePassIsSeenByTheWaitAfterIt() throws Exception {
        var s = new CapacitySignal();
        var seen = s.epoch();
        s.signal(); // "a release during the pass"
        assertTrue(s.awaitChange(seen, Duration.ofMinutes(1))); // returns at once
    }

    @Test
    void awaitTimesOutWithoutASignal() throws Exception {
        var s = new CapacitySignal();
        assertFalse(s.awaitChange(s.epoch(), Duration.ofMillis(50)));
    }

    @Test
    void aSignalWakesAParkedWaiter() throws Exception {
        var s = new CapacitySignal();
        var seen = s.epoch();
        var returned = new CountDownLatch(1);
        var result = new AtomicBoolean();
        var waiter = Thread.ofVirtual().start(() -> {
            try {
                result.set(s.awaitChange(seen, Duration.ofMinutes(1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            returned.countDown();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
        s.signal();
        assertTrue(returned.await(5, SECONDS), "the parked waiter returned");
        assertTrue(result.get(), "it reports that the epoch moved");
    }

    @Test
    void anInterruptEndsTheWait() throws Exception {
        var s = new CapacitySignal();
        var seen = s.epoch();
        var thrown = new AtomicReference<@Nullable Throwable>();
        var returned = new CountDownLatch(1);
        var waiter = Thread.ofVirtual().start(() -> {
            try {
                s.awaitChange(seen, Duration.ofMinutes(1));
            } catch (Throwable t) {
                thrown.set(t);
            }
            returned.countDown();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
        waiter.interrupt();
        assertTrue(returned.await(5, SECONDS), "the interrupted waiter returned");
        assertInstanceOf(InterruptedException.class, thrown.get());
    }
}
