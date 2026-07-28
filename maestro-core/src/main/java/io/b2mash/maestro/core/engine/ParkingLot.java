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
 * <h2>Wake Permits</h2>
 * <p>An {@link #unpark(String, Object)} that finds no registered waiter
 * stores a <b>one-shot permit</b> (latest payload wins) instead of being
 * lost; the next park on that key consumes it and returns immediately.
 * This closes the race where a wake source fires between a caller
 * announcing its intent to wait (e.g. status {@code WAITING_TIMER}) and
 * the park actually registering — without permits, a timer fired in that
 * window would never be re-fired and the workflow would stall.
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. The underlying {@link ConcurrentHashMap}
 * handles concurrent park/unpark from different virtual threads.
 */
final class ParkingLot {

    private static final Logger logger = LoggerFactory.getLogger(ParkingLot.class);

    /** Marker for a permit delivered with a {@code null} payload. */
    private static final Object NULL_PAYLOAD = new Object();

    private final ConcurrentHashMap<String, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> pendingWakes = new ConcurrentHashMap<>();

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
            // Consume a permit stored by an unpark that raced ahead of this
            // park. Checked AFTER registering the future: an unpark running
            // now either sees the future (completes it) or stored a permit
            // before we registered (we consume it here) — never lost.
            var permit = pendingWakes.remove(key);
            if (permit != null) {
                return unwrapPermit(permit);
            }
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
            // See park(): consume a permit from an unpark that raced ahead
            var permit = pendingWakes.remove(key);
            if (permit != null) {
                return unwrapPermit(permit);
            }
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
     * <p>If no thread is currently parked at this key, a one-shot permit is
     * stored (latest payload wins) so the wake is not lost if a park is
     * racing to register — the next park on this key returns immediately.
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
            pendingWakes.put(key, payload != null ? payload : NULL_PAYLOAD);
            // Close the reverse race: a park may have registered its future
            // after our lookup above but before the permit was stored — if
            // so, hand the wake to it now (it may already have consumed the
            // permit itself; remove() makes the hand-off exactly-once).
            var racedFuture = futures.get(key);
            if (racedFuture != null) {
                var permit = pendingWakes.remove(key);
                if (permit != null) {
                    racedFuture.complete(unwrapPermit(permit));
                }
            }
            logger.debug("No parked workflow at key '{}' — stored wake permit", key);
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
        clearPending(workflowId);
    }

    /**
     * Removes any stored wake permits for a workflow. Called when a workflow
     * reaches a terminal state so orphaned permits (e.g. from duplicate
     * signal deliveries) cannot accumulate or leak into a future run.
     *
     * @param workflowId the workflow ID prefix to match
     */
    void clearPending(String workflowId) {
        var prefix = workflowId + ":";
        pendingWakes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Returns the number of stored wake permits. Primarily for testing.
     *
     * @return the number of pending-wake entries
     */
    int pendingWakeCount() {
        return pendingWakes.size();
    }

    private static @Nullable Object unwrapPermit(Object permit) {
        return permit == NULL_PAYLOAD ? null : permit;
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
