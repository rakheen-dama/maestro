package io.maestro.core.spi;

import io.maestro.core.exception.DuplicateEventException;
import io.maestro.core.exception.OptimisticLockException;
import io.maestro.core.exception.WorkflowAlreadyExistsException;
import io.maestro.core.exception.WorkflowNotFoundException;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowInstance;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowTimer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent store for workflow instances, events, signals, and timers.
 *
 * <p>This is the primary SPI that Maestro uses to persist all workflow state.
 * Implementations must provide durable, transactional storage — Postgres is
 * the reference implementation.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>All write operations must be durable (committed to disk) before returning.</li>
 *   <li>Optimistic locking on {@link WorkflowInstance#version()} must be enforced
 *       by {@link #updateInstance(WorkflowInstance)}.</li>
 *   <li>The {@code (workflow_instance_id, sequence_number)} uniqueness constraint on
 *       events must be enforced by {@link #appendEvent(WorkflowEvent)}.</li>
 *   <li>Methods that return lists must never return {@code null} — return an
 *       empty list instead.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple virtual threads may call
 * store methods concurrently for different workflow instances.
 *
 * @see io.maestro.core.model.WorkflowInstance
 * @see io.maestro.core.model.WorkflowEvent
 * @see io.maestro.core.model.WorkflowSignal
 * @see io.maestro.core.model.WorkflowTimer
 */
public interface WorkflowStore {

    // ── Instance operations ──────────────────────────────────────────────

    /**
     * Creates a new workflow instance.
     *
     * <p>The instance's {@link WorkflowInstance#version()} should be {@code 0}.
     * After creation, the store should adopt any orphaned signals for the
     * workflow's {@link WorkflowInstance#workflowId()} (see
     * {@link #adoptOrphanedSignals(String, UUID)}).
     *
     * @param instance the workflow instance to create
     * @return the created instance (may have store-assigned fields)
     * @throws WorkflowAlreadyExistsException if a workflow with the same
     *         {@link WorkflowInstance#workflowId()} already exists
     */
    WorkflowInstance createInstance(WorkflowInstance instance);

    /**
     * Retrieves a workflow instance by its business workflow ID.
     *
     * @param workflowId the business workflow ID (e.g., {@code "order-abc"})
     * @return the instance, or empty if not found
     */
    Optional<WorkflowInstance> getInstance(String workflowId);

    /**
     * Returns all workflow instances in a recoverable (active) state.
     *
     * <p>Recoverable instances are those with a status where
     * {@link io.maestro.core.model.WorkflowStatus#isActive()} returns
     * {@code true}. This is used during startup recovery to resume
     * interrupted workflows.
     *
     * @return list of recoverable instances, ordered by {@code startedAt} ascending
     */
    List<WorkflowInstance> getRecoverableInstances();

    /**
     * Updates an existing workflow instance with optimistic locking.
     *
     * <p>The update must check that the current stored version matches
     * {@link WorkflowInstance#version()} on the provided instance. If it
     * matches, the store increments the version and applies the update.
     * If it does not match, an {@link OptimisticLockException} is thrown.
     *
     * @param instance the instance to update (with current version)
     * @throws WorkflowNotFoundException if the workflow does not exist
     * @throws OptimisticLockException   if the version does not match
     */
    void updateInstance(WorkflowInstance instance);

    // ── Event operations ─────────────────────────────────────────────────

    /**
     * Appends an event to the workflow's memoization log.
     *
     * <p>The combination of {@code (workflowInstanceId, sequenceNumber)}
     * must be unique. If a duplicate is detected, a
     * {@link DuplicateEventException} is thrown — this is a safety mechanism
     * that prevents double-recording of activity results.
     *
     * @param event the event to append
     * @throws DuplicateEventException if an event with the same instance ID
     *         and sequence number already exists
     */
    void appendEvent(WorkflowEvent event);

    /**
     * Retrieves a specific event by workflow instance ID and sequence number.
     *
     * <p>This is the core memoization lookup — the activity proxy uses this
     * to check if a step result has already been recorded.
     *
     * @param instanceId     the workflow instance UUID
     * @param sequenceNumber the event sequence number
     * @return the event, or empty if not found (indicating the step needs execution)
     */
    Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber);

    /**
     * Retrieves all events for a workflow instance, ordered by sequence number ascending.
     *
     * <p>Used during recovery to replay the complete event log.
     *
     * @param instanceId the workflow instance UUID
     * @return list of events, ordered by {@code sequenceNumber} ascending
     */
    List<WorkflowEvent> getEvents(UUID instanceId);

    // ── Signal operations ────────────────────────────────────────────────

    /**
     * Persists a signal immediately.
     *
     * <p>The signal may have a {@code null}
     * {@link WorkflowSignal#workflowInstanceId()} if the target workflow
     * instance does not yet exist (pre-delivery pattern). Such "orphaned"
     * signals are adopted when the workflow starts via
     * {@link #adoptOrphanedSignals(String, UUID)}.
     *
     * <p><b>Signals must never be discarded.</b> Even if the workflow doesn't
     * exist yet, the signal is persisted for later delivery.
     *
     * @param signal the signal to persist
     */
    void saveSignal(WorkflowSignal signal);

    /**
     * Retrieves unconsumed signals for a workflow by workflow ID and signal name.
     *
     * <p>Returns signals where {@code consumed = false}. Results are ordered
     * by {@code receivedAt} ascending (earliest first).
     *
     * @param workflowId the business workflow ID
     * @param signalName the signal name to match
     * @return list of unconsumed signals, ordered by {@code receivedAt} ascending
     */
    List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName);

    /**
     * Marks a signal as consumed.
     *
     * <p>Once consumed, the signal will no longer be returned by
     * {@link #getUnconsumedSignals(String, String)}.
     *
     * @param signalId the signal UUID to mark as consumed
     */
    void markSignalConsumed(UUID signalId);

    /**
     * Adopts orphaned signals by setting their {@code workflowInstanceId}.
     *
     * <p>Orphaned signals are those persisted with a {@code null}
     * {@code workflowInstanceId} (pre-delivery pattern). This method
     * links them to the newly created workflow instance.
     *
     * <p>Should be called during workflow instance creation.
     *
     * @param workflowId the business workflow ID to match signals against
     * @param instanceId the workflow instance UUID to assign
     */
    void adoptOrphanedSignals(String workflowId, UUID instanceId);

    // ── Timer operations ─────────────────────────────────────────────────

    /**
     * Persists a durable timer.
     *
     * @param timer the timer to persist
     */
    void saveTimer(WorkflowTimer timer);

    /**
     * Retrieves timers that are due to fire.
     *
     * <p>Returns timers where {@code fireAt <= now} and {@code status = PENDING}.
     * Implementations should use row-level locking (e.g., {@code FOR UPDATE
     * SKIP LOCKED} in Postgres) to allow concurrent timer pollers without
     * contention.
     *
     * @param now       the current time to compare against {@code fireAt}
     * @param batchSize maximum number of timers to return
     * @return list of due timers, ordered by {@code fireAt} ascending
     */
    List<WorkflowTimer> getDueTimers(Instant now, int batchSize);

    /**
     * Marks a timer as fired.
     *
     * <p>Updates the timer status from {@code PENDING} to {@code FIRED}.
     *
     * @param timerId the timer UUID to mark as fired
     */
    void markTimerFired(UUID timerId);
}
