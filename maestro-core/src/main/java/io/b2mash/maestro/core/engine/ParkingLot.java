package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages parked virtual threads for durable sleep and signal-await operations.
 *
 * <p>When a workflow calls {@code sleep()} or {@code awaitSignal()}, the
 * workflow's virtual thread needs to block cheaply until an external event
 * (timer fire, signal delivery) resumes it. This class provides the
 * park/unpark mechanism using {@link CompletableFuture}.
 *
 * <p>Virtual threads block on {@link CompletableFuture#join()} without
 * consuming a platform thread — the carrier thread is released back to
 * the ForkJoinPool.
 *
 * <h2>Key Format</h2>
 * <p>Keys encode the workflow identity and parking reason:
 * <ul>
 *   <li>{@code "{workflowId}:signal:{signalName}"} — waiting for a signal</li>
 *   <li>{@code "{workflowId}:timer:{timerId}"} — waiting for a timer</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. The underlying {@link ConcurrentHashMap}
 * handles concurrent park/unpark from different virtual threads.
 */
final class ParkingLot {

    private static final Logger logger = LoggerFactory.getLogger(ParkingLot.class);

    private final ConcurrentHashMap<String, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();

    /**
     * Parks the current virtual thread until {@link #unpark(String, Object)} is called.
     *
     * <p>Creates a {@link CompletableFuture}, registers it under the given key,
     * and blocks on {@link CompletableFuture#join()}. The virtual thread yields
     * its carrier thread while parked.
     *
     * @param key the parking key (e.g., {@code "order-abc:signal:payment.result"})
     * @return the payload delivered by {@link #unpark(String, Object)}, may be {@code null}
     * @throws CancellationException if the future is cancelled (e.g., during shutdown)
     */
    @Nullable Object park(String key) {
        var future = new CompletableFuture<Object>();
        var existing = futures.putIfAbsent(key, future);
        if (existing != null) {
            throw new IllegalStateException(
                    "Parking key '%s' already occupied — duplicate park call would orphan the existing waiter".formatted(key));
        }

        try {
            return future.join();
        } finally {
            futures.remove(key, future);
        }
    }

    /**
     * Parks the current virtual thread with a timeout.
     *
     * <p>Behaves like {@link #park(String)} but throws {@link TimeoutException}
     * if the specified duration elapses before {@link #unpark(String, Object)} is called.
     *
     * @param key     the parking key
     * @param timeout maximum time to wait
     * @return the payload delivered by unpark, may be {@code null}
     * @throws TimeoutException      if the timeout elapses before unpark
     * @throws CancellationException if the future is cancelled (e.g., during shutdown)
     */
    @Nullable Object parkWithTimeout(String key, Duration timeout) throws TimeoutException {
        var future = new CompletableFuture<Object>();
        var existing = futures.putIfAbsent(key, future);
        if (existing != null) {
            throw new IllegalStateException(
                    "Parking key '%s' already occupied — duplicate park call would orphan the existing waiter".formatted(key));
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while parked at key: " + key);
        } catch (java.util.concurrent.ExecutionException e) {
            // Propagate the cause — this shouldn't normally happen since we complete with values
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected exception while parked at key: " + key, e.getCause());
        } finally {
            futures.remove(key, future);
        }
    }

    /**
     * Unparks a virtual thread that is waiting at the given key.
     *
     * <p>Completes the {@link CompletableFuture} associated with the key,
     * causing the parked virtual thread to resume with the given payload.
     *
     * <p>If no thread is currently parked at this key, the call is logged
     * as a debug message and ignored — the signal/timer result will be
     * consumed from the store when the workflow reaches the await point.
     *
     * @param key     the parking key
     * @param payload the result to deliver to the parked thread, may be {@code null}
     */
    void unpark(String key, @Nullable Object payload) {
        var future = futures.get(key);
        if (future != null) {
            future.complete(payload);
            logger.debug("Unparked workflow at key '{}'", key);
        } else {
            logger.debug("No parked workflow at key '{}' — signal/timer will be consumed from store", key);
        }
    }

    /**
     * Returns whether a virtual thread is currently parked at the given key.
     *
     * @param key the parking key
     * @return {@code true} if a thread is parked at this key
     */
    boolean isParked(String key) {
        return futures.containsKey(key);
    }

    /**
     * Cancels all parked futures whose key starts with the given workflow ID prefix.
     *
     * <p>Used during graceful shutdown to unblock all parked virtual threads
     * for a specific workflow so they can exit cleanly.
     *
     * @param workflowId the workflow ID prefix to match
     */
    void unparkAll(String workflowId) {
        var prefix = workflowId + ":";
        futures.forEach((key, future) -> {
            if (key.startsWith(prefix)) {
                future.cancel(false);
                logger.debug("Cancelled parked future at key '{}' during shutdown", key);
            }
        });
    }

    /**
     * Returns the number of currently parked workflows. Primarily for testing.
     *
     * @return the number of parked entries
     */
    int parkedCount() {
        return futures.size();
    }
}
