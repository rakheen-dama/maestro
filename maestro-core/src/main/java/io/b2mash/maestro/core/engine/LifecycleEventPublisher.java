package io.b2mash.maestro.core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-thread, best-effort dispatcher for lifecycle event publishing.
 *
 * <p>{@link WorkflowExecutor} calls {@link #submit} from inside a workflow's
 * virtual thread — most notably from {@code startWorkflow} itself. A
 * {@code WorkflowMessaging} implementation is free to block there:
 * Kafka's producer, for example, blocks synchronously inside {@code send()}
 * while it waits (up to {@code max.block.ms}, 60s by default) for metadata on
 * a topic that does not exist. The SPI contract says a lifecycle failure must
 * not interrupt workflow execution; this class makes that true for latency
 * too, by moving the publish call onto a small pool that the caller never
 * waits on.
 *
 * <h2>Backpressure</h2>
 * <p>The pool is intentionally small and its queue bounded. When the queue is
 * full — because the transport is slow or unreachable — new events are
 * dropped rather than queued without bound or, worse, run on the calling
 * (workflow) thread. Drops are logged, rate-limited to one summary line per
 * {@value #DROP_LOG_INTERVAL_SECONDS}s, so sustained backpressure is visible
 * in the logs without flooding them.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link #submit} may be called concurrently
 * from any number of workflow threads. {@link #shutdown()} is idempotent.
 */
final class LifecycleEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleEventPublisher.class);

    /** Worker threads: publishing is I/O-bound and low-volume, one is enough. */
    private static final int POOL_SIZE = 1;

    /**
     * Bounds how many lifecycle events can be queued while the transport is
     * slow. Beyond this, events are dropped rather than accumulating without
     * bound in front of a stalled publisher.
     */
    private static final int QUEUE_CAPACITY = 1_000;

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long DROP_LOG_INTERVAL_SECONDS = 10;

    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedSinceLastLog = new AtomicLong();

    // Seeded one interval in the past (not a sentinel like Long.MIN_VALUE) so the
    // very first drop logs immediately: nanoTime() deltas are only meaningful —
    // safe against nanoTime's arbitrary, wraparound-prone origin — when both
    // sides of the subtraction are real nanoTime() readings.
    private final AtomicLong lastDropLogAtNanos =
            new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(DROP_LOG_INTERVAL_SECONDS));

    /**
     * Creates a publisher whose worker thread is named for the owning service.
     *
     * @param serviceName the owning service name, used only for thread naming
     */
    LifecycleEventPublisher(String serviceName) {
        var threadFactory = daemonThreadFactory(serviceName);
        this.executor = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                (task, exec) -> recordDropped());
    }

    /**
     * Submits a lifecycle event publish task. Never blocks: the task either
     * queues, or — under backpressure — is dropped and counted.
     *
     * @param publishTask runs the actual (potentially slow or blocking) publish
     */
    void submit(Runnable publishTask) {
        try {
            executor.execute(publishTask);
        } catch (RejectedExecutionException e) {
            // Only reachable once the executor has been shut down.
            recordDropped();
        }
    }

    /**
     * Shuts the publisher down, giving queued and in-flight publishes a short
     * window to finish before forcing termination. Called once from
     * {@link WorkflowExecutor#shutdown()}.
     */
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // ── Internal: rate-limited drop logging ─────────────────────────────

    private void recordDropped() {
        droppedSinceLastLog.incrementAndGet();
        var now = System.nanoTime();
        var last = lastDropLogAtNanos.get();
        var intervalNanos = TimeUnit.SECONDS.toNanos(DROP_LOG_INTERVAL_SECONDS);
        if ((now - last) >= intervalNanos && lastDropLogAtNanos.compareAndSet(last, now)) {
            var count = droppedSinceLastLog.getAndSet(0);
            logger.warn("Dropped {} lifecycle event(s) in the last ~{}s due to backpressure "
                            + "(queue full or transport slow/unreachable)",
                    count, DROP_LOG_INTERVAL_SECONDS);
        }
    }

    private static ThreadFactory daemonThreadFactory(String serviceName) {
        var counter = new AtomicInteger();
        return runnable -> {
            var thread = new Thread(runnable,
                    "maestro-lifecycle-publisher-%s-%d".formatted(serviceName, counter.incrementAndGet()));
            thread.setDaemon(true);
            return thread;
        };
    }
}
