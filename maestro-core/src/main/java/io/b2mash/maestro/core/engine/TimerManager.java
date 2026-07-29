package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns due-timer queries for the {@link TimerPoller}.
 *
 * <p>Timer <b>creation</b> is handled by
 * {@link DefaultWorkflowOperations#sleep(java.time.Duration)} which persists
 * timers directly via the store as part of the memoization flow. Timer
 * <b>firing</b> is handled by {@link WorkflowExecutor#fireTimer(String, String, UUID)}
 * which marks the timer as fired and unparks the workflow. Timer
 * <b>cancellation</b> is handled by
 * {@link WorkflowExecutor#cancelTimer(String, String, UUID)} — cancellation
 * must be able to unpark the waiting workflow the same way firing does, which
 * requires the {@link ParkingLot} this class deliberately has no access to;
 * see {@link WorkflowExecutor#cancelTimer(String, String, UUID)}'s Javadoc for
 * why a store-only cancel would strand the workflow.
 *
 * <p>This class provides the remaining piece:
 * {@link #getDueTimers(int)} — for the {@link TimerPoller} to find timers
 * that need firing.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The underlying {@link WorkflowStore}
 * handles concurrent access.
 *
 * @see TimerPoller
 * @see WorkflowExecutor#fireTimer(String, String, UUID)
 * @see WorkflowExecutor#cancelTimer(String, String, UUID)
 */
final class TimerManager {

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
