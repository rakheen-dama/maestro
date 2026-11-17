package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BatchPacer}: the pause rule, and a pause that ends on a stop request or an interrupt. */
class BatchPacerTest {

    private final List<Duration> recorded = new CopyOnWriteArrayList<>();
    private final BatchPacer.Wait recorder = (stop, d) -> {
        recorded.add(d);
        return stop.getCount() == 0;
    };

    @Test
    void thePauseIsTheBatchDurationCappedAtFiveSeconds() {
        assertEquals(Duration.ofMillis(40), BatchPacer.pauseFor(Duration.ofMillis(40).toNanos()));
        assertEquals(Duration.ofSeconds(5), BatchPacer.pauseFor(Duration.ofMinutes(3).toNanos()));
        assertEquals(Duration.ZERO, BatchPacer.pauseFor(-1));
    }

    @Test
    void aZeroPauseDoesNotWait() {
        var pacer = new BatchPacer(() -> 0L, recorder);
        assertTrue(pacer.pauseAfter(0));
        assertEquals(List.of(), recorded, "a zero pause never reaches the wait");
    }

    @Test
    void aPauseAfterStopReturnsFalseWithoutWaiting() {
        var pacer = new BatchPacer(() -> 0L, recorder);
        pacer.requestStop();
        assertTrue(pacer.stopRequested());
        assertFalse(pacer.pauseAfter(Duration.ofSeconds(5).toNanos()));
        assertEquals(List.of(), recorded, "a stopped pacer never reaches the wait");
    }

    @Test
    void requestStopEndsAWaitInProgressWithoutAnInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var pacer = new BatchPacer(System::nanoTime, (stop, d) -> {
            entered.countDown();
            return BatchPacer.LATCH.await(stop, d);
        });
        var done = new CompletableFuture<Boolean>();
        var interruptedAfter = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            var proceed = pacer.pauseAfter(Duration.ofSeconds(5).toNanos());
            interruptedAfter.set(Thread.currentThread().isInterrupted());
            done.complete(proceed);
        });
        assertTrue(entered.await(5, SECONDS), "the pause began");
        pacer.requestStop();
        var proceed = done.completeOnTimeout(null, 1, SECONDS).get();
        assertNotNull(proceed, "the pause was still waiting 1 s after requestStop");
        assertFalse(proceed, "a stopped pause tells the sweep not to run its next batch");
        assertFalse(interruptedAfter.get(), "nothing interrupted the pausing thread");
    }

    @Test
    void anInterruptEndsTheWaitAndIsRestored() throws Exception {
        var entered = new CountDownLatch(1);
        var pacer = new BatchPacer(System::nanoTime, (stop, d) -> {
            entered.countDown();
            return BatchPacer.LATCH.await(stop, d);
        });
        var done = new CompletableFuture<Boolean>();
        var interruptedAfter = new AtomicBoolean(false);
        var thread = Thread.ofVirtual().start(() -> {
            var proceed = pacer.pauseAfter(Duration.ofSeconds(5).toNanos());
            interruptedAfter.set(Thread.currentThread().isInterrupted());
            done.complete(proceed);
        });
        assertTrue(entered.await(5, SECONDS), "the pause began");
        thread.interrupt();
        var proceed = done.completeOnTimeout(null, 1, SECONDS).get();
        assertNotNull(proceed, "the pause was still waiting 1 s after the interrupt");
        assertFalse(proceed, "an interrupted pause tells the sweep not to run its next batch");
        assertTrue(interruptedAfter.get(), "the interrupt status was restored");
    }
}
