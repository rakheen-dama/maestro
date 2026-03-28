package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns timer lifecycle operations: cancellation and due-timer queries.
 *
 * <p>Timer <b>creation</b> is handled by
 * {@link DefaultWorkflowOperations#sleep(java.time.Duration)} which persists
 * timers directly via the store as part of the memoization flow. Timer
 * <b>firing</b> is handled by {@link WorkflowExecutor#fireTimer(String, String, UUID)}
 * which marks the timer as fired and unparks the workflow.
 *
 * <p>This class provides the remaining pieces:
 * <ul>
 *   <li>{@link #cancelTimer(UUID)} — for admin dashboard timer cancellation</li>
 *   <li>{@link #getDueTimers(int)} — for the {@link TimerPoller} to find timers
 *       that need firing</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The underlying {@link WorkflowStore}
 * handles concurrent access.
 *
 * @see TimerPoller
 * @see WorkflowExecutor#fireTimer(String, String, UUID)
 */
final class TimerManager {

    private static final Logger logger = LoggerFactory.getLogger(TimerManager.class);

    private final WorkflowStore store;

    /**
     * Creates a new timer manager.
     *
     * @param store workflow store for timer persistence
     */
    TimerManager(WorkflowStore store) {
        this.store = store;
    }

    /**
     * Cancels a pending timer.
     *
     * <p>Sets the timer status from {@code PENDING} to {@code CANCELLED}.
     * If the timer has already been fired or cancelled, this is a no-op
     * (idempotent).
     *
     * @param timerDbId the timer's database UUID
     */
    void cancelTimer(UUID timerDbId) {
        store.markTimerCancelled(timerDbId);
        logger.debug("Cancelled timer {}", timerDbId);
    }

    /**
     * Returns timers that are due to fire.
     *
     * <p>Queries for timers where {@code fireAt <= now} and
     * {@code status = PENDING}. The store implementation should use
     * row-level locking to prevent contention.
     *
     * @param batchSize maximum number of timers to return
     * @return list of due timers, ordered by {@code fireAt} ascending
     */
    List<WorkflowTimer> getDueTimers(int batchSize) {
        return store.getDueTimers(Instant.now(), batchSize);
    }
}
