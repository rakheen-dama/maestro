package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5 D-7: {@link LifecycleEventPublisher#shutdown(Duration)} waits at most
 * its flush window, and the events still queued when it ends are counted as
 * dropped and logged once.
 */
@DisplayName("LifecycleEventPublisher shutdown honours its flush window")
class LifecycleEventPublisherShutdownTest {

    @Test
    void eventsStillQueuedAtTheFlushDeadlineAreCountedAndLoggedOnce() throws Exception {
        var publisher = new LifecycleEventPublisher("test-service");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var published = new AtomicInteger();
        try (var logs = CapturingSlf4jProvider.watch(LifecycleEventPublisher.class)) {
            publisher.submit(() -> {
                entered.countDown();
                awaitSwallowingInterrupts(release);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the worker is inside a publish");
            for (var i = 0; i < 3; i++) {
                publisher.submit(published::incrementAndGet);
            }

            var t0 = System.nanoTime();
            publisher.shutdown(Duration.ofMillis(200));
            var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertTrue(tookMs < 1_000, "shutdown(200 ms) took " + tookMs + " ms");
            assertEquals(3, publisher.dropped(), "the three queued events are counted as dropped");
            var lines = logs.records().stream()
                    .filter(r -> r.message().equals(
                            "Lifecycle event publisher stopped with 3 queued event(s) unpublished"))
                    .toList();
            assertEquals(1, lines.size(), logs.records().toString());
            assertEquals(Level.WARN, lines.getFirst().level());
        } finally {
            release.countDown();
        }
        assertEquals(0, published.get(), "a dropped event is never published");
    }

    @Test
    void aFlushWithTimeLeftPublishesWhatIsQueued() throws Exception {
        var publisher = new LifecycleEventPublisher("test-service");
        var published = new AtomicInteger();
        for (var i = 0; i < 3; i++) {
            publisher.submit(published::incrementAndGet);
        }

        publisher.shutdown(Duration.ofSeconds(5));

        assertEquals(3, published.get());
        assertEquals(0, publisher.dropped());
    }

    private static void awaitSwallowingInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException swallowed) {
                // a transport that ignores the interrupt
            }
        }
    }
}
