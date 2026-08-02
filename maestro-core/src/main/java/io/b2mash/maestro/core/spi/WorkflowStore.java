package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;

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
 * @see io.b2mash.maestro.core.model.WorkflowInstance
 * @see io.b2mash.maestro.core.model.WorkflowEvent
 * @see io.b2mash.maestro.core.model.WorkflowSignal
 * @see io.b2mash.maestro.core.model.WorkflowTimer
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
     * {@link io.b2mash.maestro.core.model.WorkflowStatus#isActive()} returns
     * {@code true}. This is used during startup recovery to resume
     * interrupted workflows.
     *
     * @return list of recoverable instances, ordered by {@code startedAt} ascending
     */
    List<WorkflowInstance> getRecoverableInstances();

    /**
     * Updates an existing workflow instance with optimistic locking.
     *
     * <p><b>Version convention:</b> the caller builds the complete new state,
     * including the new version ({@code current + 1}), before calling this
     * method — e.g. {@code instance.toBuilder().version(current.version() + 1)}.
     * The store persists the instance verbatim if and only if the stored
     * row's version equals {@link WorkflowInstance#version()}{@code - 1};
     * otherwise an {@link OptimisticLockException} is thrown. After a
     * successful update, the in-memory instance and the stored row agree.
     *
     * @param instance the fully-built updated instance (version already incremented)
     * @throws WorkflowNotFoundException if the workflow does not exist
     * @throws OptimisticLockException   if the stored version does not match
     *                                   {@code instance.version() - 1}
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

    /**
     * Deletes a workflow instance's <b>failure</b> memos — and nothing else —
     * so a manual retry can re-execute the step that failed.
     *
     * <p>Deletes exactly the events whose type is
     * {@link io.b2mash.maestro.core.model.EventType#ACTIVITY_FAILED} or
     * {@link io.b2mash.maestro.core.model.EventType#WORKFLOW_FAILED} for the
     * given instance. <b>Every other event must survive</b>, and the distinction
     * is load-bearing rather than cosmetic:
     * <ul>
     *   <li><b>Success memos</b> ({@code ACTIVITY_COMPLETED},
     *       {@code SIGNAL_RECEIVED}, {@code TIMER_FIRED}, {@code SIDE_EFFECT},
     *       …) are what make the retry replay the completed prefix instead of
     *       re-executing it. Deleting them would re-run real side effects.</li>
     *   <li><b>Compensation events</b> ({@code COMPENSATION_*}) record side
     *       effects that genuinely happened, and their memos are what stop the
     *       compensations running a second time on the retry replay.</li>
     * </ul>
     *
     * <p>Why deleting the failures is what a retry means: a failed activity's
     * outcome is memoized, and replay deliberately re-throws a stored
     * {@code ACTIVITY_FAILED} rather than re-executing the step. Without this
     * operation a retry would replay the recorded failure and fail again
     * identically, however long ago the underlying fault was fixed. Removing
     * the {@code WORKFLOW_FAILED} event additionally frees the sequence number
     * the retried run needs for its own terminal event.
     *
     * <p><b>The failing timeout memo (Issue 19).</b> A timed-out await
     * memoizes a {@code SIGNAL_TIMEOUT} event so replay re-raises the timeout
     * deterministically. When the workflow FAILED <em>because</em> of that
     * timeout (the {@code WORKFLOW_FAILED} payload's {@code exceptionType}
     * records a {@code SignalTimeoutException}), the memo is itself a failure
     * record: implementations must also delete the instance's
     * highest-sequenced {@code SIGNAL_TIMEOUT} event. That memo is <em>not</em>
     * necessarily the last memo before {@code WORKFLOW_FAILED} — an uncaught
     * timeout in a saga appends {@code COMPENSATION_*} events between the
     * failing memo and the terminal, and the memo must be deleted regardless.
     * Deleting it frees the retried await to run live and consume the
     * now-delivered signal. Earlier <em>caught</em> gate timeouts sit at lower
     * sequences, are never that maximum, and must survive — deleting them
     * would let a retry replay consume a late-arrived signal at the gate and
     * diverge from the pre-failure execution.
     *
     * <p><b>Idempotent.</b> Called on an instance with no failure memos it
     * deletes nothing and returns {@code 0}.
     *
     * <p><b>Implementation note:</b> this is the only operation that removes
     * rows from the event log. Implementations must scope the delete to the
     * given instance and to those event types (plus the single failing-timeout
     * memo) — never to a sequence range, which would take compensation and
     * success memos with it.
     *
     * @param instanceId the workflow instance UUID whose failure memos to delete
     * @return the number of events deleted
     * @see io.b2mash.maestro.core.engine.WorkflowExecutor#retryWorkflow
     */
    int deleteFailureEvents(UUID instanceId);

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
     * Atomically marks a signal as consumed (compare-and-set).
     *
     * <p>Once consumed, the signal will no longer be returned by
     * {@link #getUnconsumedSignals(String, String)}. Implementations must
     * transition the {@code consumed} flag atomically so that a signal row
     * can never satisfy two consumers.
     *
     * @param signalId the signal UUID to mark as consumed
     * @return {@code true} if this call transitioned the signal from
     *         unconsumed to consumed; {@code false} if it was already
     *         consumed or does not exist
     */
    boolean markSignalConsumed(UUID signalId);

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
     * Looks up a workflow's timer by its logical timer ID, whatever its status.
     *
     * <p>This is the only way to read a timer that is no longer <em>due</em>.
     * {@link #getDueTimers(Instant, int)} deliberately returns only
     * {@code PENDING} rows, so a timer that has already fired is invisible to
     * it. Replay needs the fired ones: a node can die between
     * {@link #markTimerFired(UUID)} and the workflow thread appending its
     * {@code TIMER_FIRED} event, leaving an event log that says "scheduled,
     * never fired" and a row that says otherwise. Replay consults this method to
     * tell that crash window apart from a timer that is genuinely still pending,
     * and continues rather than parking forever.
     *
     * <p>A timer ID is unique within a workflow instance (it is derived from the
     * sequence number of the {@code sleep()} that created it). If an
     * implementation somehow holds duplicates, it must return one of them
     * deterministically.
     *
     * @param workflowInstanceId the owning workflow instance
     * @param timerId            the logical timer ID (e.g. {@code "sleep-2"}),
     *                           not the timer's database UUID
     * @return the timer, or empty if this instance has no timer with that ID
     */
    Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId);

    /**
     * Marks a timer as fired (atomic compare-and-set: PENDING → FIRED).
     *
     * <p>Only transitions timers in {@code PENDING} status. If the timer
     * is already {@code FIRED} or {@code CANCELLED}, this is a no-op and
     * returns {@code false}. This allows callers to detect whether they
     * won the race (e.g., fire vs. cancel).
     *
     * @param timerId the timer UUID to mark as fired
     * @return {@code true} if the transition was applied (PENDING → FIRED),
     *         {@code false} if the timer was already in a terminal state
     */
    boolean markTimerFired(UUID timerId);

    /**
     * Marks a timer as cancelled (atomic compare-and-set: PENDING → CANCELLED).
     *
     * <p>Only transitions timers in {@code PENDING} status. If the timer is
     * already {@code FIRED} or {@code CANCELLED}, this is a no-op and returns
     * {@code false}. This allows callers to detect whether they won the race
     * (e.g., cancel vs. fire) — the live cancellation path
     * ({@link io.b2mash.maestro.core.engine.WorkflowExecutor#cancelTimer})
     * only unparks the waiting workflow when this call returns {@code true},
     * so a caller that discards the return value would silently leave a lost
     * race un-observed rather than reporting it.
     *
     * @param timerId the timer UUID to cancel
     * @return {@code true} if the transition was applied (PENDING → CANCELLED),
     *         {@code false} if the timer was already in a terminal state
     */
    boolean markTimerCancelled(UUID timerId);
}
