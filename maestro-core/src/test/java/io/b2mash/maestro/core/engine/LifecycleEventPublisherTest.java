package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.awaitility.Awaitility.await;

/**
 * Unit tests for {@link LifecycleEventPublisher} — the bounded, off-thread
 * dispatcher {@link WorkflowExecutor} uses so a slow or unreachable
 * {@code WorkflowMessaging} implementation never shows up as latency on a
 * workflow thread.
 */
@DisplayName("LifecycleEventPublisher never blocks its caller, even under backpressure")
class LifecycleEventPublisherTest {

    @Test
    @DisplayName("submit never blocks, even once the bounded queue is saturated")
    void submit_neverBlocks_underBackpressure() throws Exception {
        var publisher = new LifecycleEventPublisher("test-service");
        var workerEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            // Occupy the single worker thread so everything after this queues up.
            publisher.submit(() -> {
                workerEntered.countDown();
                blockOn(release);
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS), "the worker must pick up the first task");

            // Flood well past the bounded queue capacity to force drops.
            var start = System.nanoTime();
            for (var i = 0; i < 1_500; i++) {
                publisher.submit(() -> {});
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0,
                    "submitting past a saturated queue must drop, not block, took " + elapsed);
        } finally {
            release.countDown();
            publisher.shutdown();
        }
    }

    @Test
    @DisplayName("queued tasks still run once the worker is free")
    void queuedTasks_stillRunEventually() throws Exception {
        var publisher = new LifecycleEventPublisher("test-service");
        var completed = new AtomicInteger();
        try {
            for (var i = 0; i < 10; i++) {
                publisher.submit(completed::incrementAndGet);
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertTrue(completed.get() == 10, "all 10 queued tasks should eventually run"));
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown forces through a stalled worker rather than hanging")
    void shutdown_forcesThroughAStalledWorker() throws Exception {
        var publisher = new LifecycleEventPublisher("test-service");
        var workerEntered = new CountDownLatch(1);
        publisher.submit(() -> {
            workerEntered.countDown();
            blockOn(new CountDownLatch(1)); // never released — simulates a permanently stalled transport
        });
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));

        var start = System.nanoTime();
        publisher.shutdown();
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0,
                "shutdown must not hang out a stalled publish task, took " + elapsed);
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdown_isIdempotent() {
        var publisher = new LifecycleEventPublisher("test-service");
        publisher.shutdown();
        assertTrue(true, "a second shutdown() must not throw");
        publisher.shutdown();
    }

    private static void blockOn(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
