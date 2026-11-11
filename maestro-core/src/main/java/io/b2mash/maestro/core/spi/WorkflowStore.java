package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
 *   <li>{@link #completeInstance(WorkflowInstance, WorkflowEvent)} must be atomic:
 *       the terminal status and the terminal event are written together or not
 *       at all.</li>
 *   <li>Methods that return lists must never return {@code null} — return an
 *       empty list instead.</li>
 * </ul>
 *
 * <h2>Lease fencing</h2>
 * <p>Every write a <em>running workflow's own thread</em> makes has a fenced
 * overload taking a {@link RunLease}: {@link #appendEvent(WorkflowEvent, RunLease)},
 * {@link #completeInstance(WorkflowInstance, WorkflowEvent, RunLease)},
 * {@link #updateInstance(WorkflowInstance, RunLease)},
 * {@link #saveTimer(WorkflowTimer, RunLease)},
 * {@link #markSignalConsumed(UUID, RunLease)}, {@link #markTimerFired(UUID, RunLease)}
 * and {@link #markTimerCancelled(UUID, RunLease)}. A fenced write is applied
 * only if the instance row's {@link WorkflowInstance#leaseEpoch()} still
 * equals the lease's epoch. The four "loud" overloads — the ones that carry
 * progress — check this <em>atomically</em> with the write (one
 * {@code INSERT ... SELECT} under a row lock, or the epoch folded into the
 * instance compare-and-set), so a concurrent adoption bump cannot slip between
 * the check and the write; they reject a stale lease with
 * {@link FencedWriteException} and write nothing. The three CAS flips are
 * <em>best-effort</em>: they check the epoch in the same statement
 * ({@code AND EXISTS (...)}) but without locking the row, and report a stale
 * lease silently by returning {@code false}. That is enough because none of
 * them carries progress on its own — the memo written by a loud fenced append
 * in the same step is the truth, and a flip that lost to an adoption is at
 * worst a row the adopter's run re-reads and settles. This is what makes an
 * instance lock that expired, was lost or was never configured unable to let
 * a stale node persist progress (Issue 11).
 *
 * <p>The unfenced forms stay for writes that are <em>not</em> a run's own:
 * {@link #createInstance} (no lease exists yet), {@link #saveSignal} and
 * {@link #adoptOrphanedSignals} (signal ingestion from any node), the timer
 * leader's {@link #markTimerFired(UUID)}, an operator's terminate and retry
 * ({@code updateInstance}, {@code deleteFailureEvents}), and the adoption bump
 * itself — all of which are arbitrated by the version compare-and-set or are
 * idempotent. The fenced overloads are {@code default} methods that delegate to
 * the unfenced forms: <b>a store that does not override them is unfenced</b>
 * and gives only the unique-index guarantee the engine always had. The shipped
 * JDBC store and the in-memory test store override all seven.
 *
 * <h2>Signal identity and atomic consumption</h2>
 * <p>Two further defaults carry the signal-idempotency guarantees:
 * {@link #saveSignalIfAbsent} (at most one row per
 * {@code (workflowId, signalName, messageId)}) and
 * {@link #appendEventAndConsumeSignal} (the {@code SIGNAL_RECEIVED} memo and
 * the consumed flip in one transaction, fenced on the run's lease). Their
 * defaults keep the pre-V9 behaviour — no deduplication, and a two-step
 * consume with a crash window — so a store that does not override them loses
 * no signal but gives neither guarantee. The shipped JDBC store and the
 * in-memory test store override both.
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
     * Returns all workflow instances in a recoverable (active) state, of every
     * service.
     *
     * <p>Recoverable instances are those with a status where
     * {@link io.b2mash.maestro.core.model.WorkflowStatus#isActive()} returns
     * {@code true}.
     *
     * <p><b>Unbounded; the engine no longer calls it.</b> It reads every
     * active row in the store, payloads included, in one result. The engine's
     * recovery pass uses the paged, service-scoped
     * {@link #getRecoverableInstances(String, RecoveryCursor, int)} instead,
     * whose default is built on this method — so every store must still
     * implement it.
     *
     * @return list of recoverable instances, ordered by {@code startedAt} ascending
     */
    List<WorkflowInstance> getRecoverableInstances();

    /**
     * Returns one page of one service's recoverable (active) instances: those
     * whose {@link WorkflowInstance#serviceName()} equals {@code serviceName},
     * ordered by {@code (startedAt, id)} ascending, strictly after
     * {@code after}, at most {@code limit} of them.
     *
     * <p>The engine's recovery pass reads with this, page by page, passing
     * {@link RecoveryCursor#of} the last instance of each page as the next
     * page's {@code after}. A page shorter than {@code limit} means no further
     * instance follows the cursor — the pass stops there — so an
     * implementation must not drop a qualifying row from a page it fills short.
     * Rows inserted or reactivated behind the cursor during a pass are picked
     * up by the next pass.
     *
     * <p>The default filters, sorts ({@link RecoveryCursor#ORDER}) and slices
     * the result of {@link #getRecoverableInstances()} — correct for any store,
     * but it runs that unbounded scan of every active row of every service
     * <em>once per page</em>: a pass over A active instances of the service,
     * in the engine's pages of 200, costs ⌊A / 200⌋ + 1 full scans where the
     * engine used to make one. Third-party stores should override it with a
     * keyset query; the shipped JDBC store and the in-memory test store do.
     *
     * @param serviceName the service whose instances to return
     * @param after       the last instance of the previous page, or
     *                    {@code null} for the first page
     * @param limit       the most instances to return; positive
     * @return the page — never {@code null}, empty past the last instance
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    default List<WorkflowInstance> getRecoverableInstances(
            String serviceName, @Nullable RecoveryCursor after, int limit) {
        Objects.requireNonNull(serviceName, "serviceName");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return getRecoverableInstances().stream()
                .filter(i -> i.status().isActive())
                .filter(i -> serviceName.equals(i.serviceName()))
                .filter(i -> after == null || after.precedes(i))
                .sorted(RecoveryCursor.ORDER)
                .limit(limit)
                .toList();
    }

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

    /**
     * {@link #updateInstance(WorkflowInstance)} fenced on the writer's lease:
     * the row is written if and only if its stored version equals
     * {@code instance.version() - 1} <em>and</em> its stored
     * {@code lease_epoch} equals {@code lease.epoch()}, both checked in the
     * one compare-and-set. A stale lease throws {@link FencedWriteException}
     * and writes nothing; a bare version mismatch is reported exactly as the
     * unfenced form reports it. The instance being written must carry the
     * lease's epoch unchanged ({@code instance.leaseEpoch() == lease.epoch()}).
     *
     * <p>Used for the status moves a running workflow makes as it parks and
     * wakes, and for the transition to {@code COMPENSATING}. The default
     * delegates to the unfenced form — see the class Javadoc.
     *
     * @param instance the fully-built updated instance (version already incremented)
     * @param lease    the writing run's lease
     * @throws FencedWriteException      if the stored lease epoch differs from
     *                                   {@code lease.epoch()} (nothing was written)
     * @throws WorkflowNotFoundException if the workflow does not exist
     * @throws OptimisticLockException   if the stored version does not match
     *                                   {@code instance.version() - 1}
     */
    default void updateInstance(WorkflowInstance instance, RunLease lease) {
        updateInstance(instance);
    }

    /**
     * Atomically finalises a workflow instance: writes its terminal status and
     * appends its terminal event as <b>one</b> all-or-nothing operation.
     *
     * <p><b>Compare-and-set.</b> Exactly the {@link #updateInstance} version
     * convention: {@code terminal} is the complete new row with its version
     * already incremented, and it is written if and only if the stored row's
     * version equals {@code terminal.version() - 1}. Unlike
     * {@code updateInstance}, a version mismatch is reported by returning
     * {@code false} rather than by throwing, because losing the CAS on a
     * terminal write is an expected race the caller handles with a fresh read.
     *
     * <p><b>Atomicity.</b> {@code terminalEvent} is appended <em>in the same
     * transaction</em> as the status write. When the CAS fails, or the append
     * fails for any reason, <b>nothing</b> is written — neither the row nor the
     * event. This closes Issue 26: because a reader can never observe a
     * terminal status without its terminal event, nor the terminal event
     * without the terminal status, {@link #getRecoverableInstances()} can never
     * re-enter a workflow that has already been finalised, and no reader is
     * ever handed a terminal workflow whose log is one event short.
     *
     * <p>The {@code (workflow_instance_id, sequence_number)} uniqueness
     * {@link #appendEvent} enforces applies to {@code terminalEvent} too: if an
     * event already occupies {@code terminalEvent.sequenceNumber()} (typically
     * another runner finalised the workflow first), a
     * {@link DuplicateEventException} is thrown and nothing is written.
     *
     * <p>Implementations must not provide this by calling
     * {@link #updateInstance} and {@link #appendEvent} as two independent
     * writes — that is precisely the non-atomic gap this method exists to
     * remove. A relational store wraps the {@code UPDATE} and the
     * {@code INSERT} in one transaction; an in-memory store performs both
     * under the instance's lock so nothing is written unless both land.
     *
     * @param terminal      the fully-built terminal instance (version already
     *                      incremented; {@link WorkflowInstance#status()} must be
     *                      terminal)
     * @param terminalEvent the {@link io.b2mash.maestro.core.model.EventType#WORKFLOW_COMPLETED}
     *                      or {@link io.b2mash.maestro.core.model.EventType#WORKFLOW_FAILED}
     *                      event to append; its
     *                      {@link WorkflowEvent#workflowInstanceId()} must equal
     *                      {@code terminal.id()}
     * @return {@code true} if the status and the event were both written;
     *         {@code false} if the stored version did not equal
     *         {@code terminal.version() - 1} (nothing was written)
     * @throws WorkflowNotFoundException if no row exists for the workflow
     *                                   (nothing was written)
     * @throws DuplicateEventException   if an event already exists at
     *                                   {@code terminalEvent.sequenceNumber()}
     *                                   for this instance (nothing was written)
     * @throws IllegalArgumentException  if {@code terminal.status()} is not
     *                                   {@linkplain io.b2mash.maestro.core.model.WorkflowStatus#isTerminal()
     *                                   terminal}, if {@code terminalEvent.eventType()}
     *                                   is not the type paired with the status
     *                                   ({@code COMPLETED} ↔ {@code WORKFLOW_COMPLETED},
     *                                   {@code FAILED} ↔ {@code WORKFLOW_FAILED};
     *                                   {@code TERMINATED} is never accepted —
     *                                   see {@link TerminalWrites#requireValid}), or if
     *                                   {@code terminalEvent.workflowInstanceId()}
     *                                   differs from {@code terminal.id()}
     */
    boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent);

    /**
     * {@link #completeInstance(WorkflowInstance, WorkflowEvent)} fenced on the
     * writer's lease: the terminal status and event are written, together,
     * only if the stored version equals {@code terminal.version() - 1}
     * <em>and</em> the stored {@code lease_epoch} equals {@code lease.epoch()}.
     * A stale lease throws {@link FencedWriteException} and writes nothing;
     * a bare version mismatch returns {@code false} exactly as the unfenced
     * form does. The default delegates to the unfenced form — see the class
     * Javadoc.
     *
     * @param terminal      as for the unfenced form
     * @param terminalEvent as for the unfenced form
     * @param lease         the finalising run's lease
     * @return {@code true} if both were written; {@code false} on a version
     *         mismatch with a current lease (nothing was written)
     * @throws FencedWriteException      if the stored lease epoch differs from
     *                                   {@code lease.epoch()} (nothing was written)
     * @throws WorkflowNotFoundException as for the unfenced form
     * @throws DuplicateEventException   as for the unfenced form
     * @throws IllegalArgumentException  as for the unfenced form
     */
    default boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent, RunLease lease) {
        return completeInstance(terminal, terminalEvent);
    }

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
     * {@link #appendEvent(WorkflowEvent)} fenced on the writer's lease: the
     * event is inserted if and only if the instance row of
     * {@code lease.instanceId()} currently carries {@code lease.epoch()},
     * checked atomically with the insert (a relational store does this with
     * one {@code INSERT ... SELECT ... FROM instance WHERE id = ? AND
     * lease_epoch = ?}, holding a share lock on the row so a concurrent
     * adoption bump orders strictly before or after it). A stale lease throws
     * {@link FencedWriteException} and writes nothing. The
     * {@code (workflow_instance_id, sequence_number)} uniqueness check applies
     * as for the unfenced form; a collision under a current lease is still a
     * {@link DuplicateEventException}. The default delegates to the unfenced
     * form — see the class Javadoc.
     *
     * @param event the event to append; {@code event.workflowInstanceId()}
     *              must equal {@code lease.instanceId()}
     * @param lease the appending run's lease
     * @throws FencedWriteException    if the stored lease epoch differs from
     *                                 {@code lease.epoch()} (nothing was written)
     * @throws DuplicateEventException as for the unfenced form
     */
    default void appendEvent(WorkflowEvent event, RunLease lease) {
        appendEvent(event);
    }

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
     * <p><b>The failing await's deadline (R61).</b> An await that parked
     * before timing out also recorded a {@code TIMER_SCHEDULED} memo carrying
     * its absolute deadline in the slot immediately before its
     * {@code SIGNAL_TIMEOUT} (same step name,
     * {@code $maestro:awaitSignal:<signalName>}), and a timer row with logical
     * ID {@code await-signal-<memoSeq>}, now {@code FIRED} or
     * {@code CANCELLED}. That deadline is spent, so it is part of the failure
     * too: when the failing {@code SIGNAL_TIMEOUT} at {@code seq} is deleted
     * and the event at {@code seq - 1} is a {@code TIMER_SCHEDULED} with the
     * same step name, implementations must delete that memo and every timer
     * row of the instance with timer ID {@code await-signal-<seq - 1>} (any
     * status) as well. The retried await then runs live at the same slot: a
     * signal that has arrived since is consumed at once; otherwise it arms a
     * fresh full timeout from the retry and parks. Leaving either behind makes
     * the retried await honour the spent deadline and fail again at once.
     * {@code sleep()} rows and the memos and rows of earlier awaits are never
     * touched.
     *
     * <p><b>Atomicity.</b> Implementations should apply all of the deletes
     * together (one transaction), so a crash cannot leave a partial delete for
     * a second retry to compound. The shipped JDBC store does.
     *
     * <p><b>Idempotent.</b> Called on an instance with no failure memos it
     * deletes nothing and returns {@code 0}.
     *
     * <p><b>Implementation note:</b> this is the only <em>per-instance</em>
     * operation that removes rows from the event log of a workflow that may
     * run again; the only other one is
     * {@link #purgeTerminalInstancesBefore(io.b2mash.maestro.core.model.WorkflowStatus, Instant, int)},
     * which removes a long-finished workflow's entire history together with
     * the instance itself. Implementations must scope this delete to the
     * given instance and to those event types (plus the single failing-timeout
     * memo and its await's park memo and deadline rows) — never to a sequence
     * range, which would take compensation and success memos with it.
     *
     * @param instanceId the workflow instance UUID whose failure memos to delete
     * @return the number of events deleted (deleted timer rows are not counted)
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
     * <p><b>{@code receivedAt} is assigned by the store.</b> It is the order
     * key of {@link #getUnconsumedSignals(String, String)}, so it must reflect
     * the order in which the store accepted the signals. A store backed by a
     * database shared between nodes <em>must</em> assign it from the database
     * clock and ignore the caller's {@link WorkflowSignal#receivedAt()} — each
     * node's JVM clock skews independently, and a caller-supplied value would
     * let two same-name signals delivered through different nodes be consumed
     * in skew order rather than arrival order. A single-process store (such as
     * the in-memory test store) may keep the caller's value, because every
     * caller shares one clock.
     *
     * @param signal the signal to persist; its {@code receivedAt} is advisory
     *               (see above)
     */
    void saveSignal(WorkflowSignal signal);

    /**
     * Persists a signal unless one with the same identity is already stored —
     * the delivery path's idempotent insert.
     *
     * <p>A signal's identity is {@code (workflowId, signalName, messageId)}.
     * When {@link WorkflowSignal#messageId()} is non-{@code null} and a row
     * with the same three values exists — consumed or not, adopted or still
     * orphaned — nothing is written and this returns {@code false}: the
     * delivery is a duplicate (a transport redelivery, a producer republish, a
     * client retry) of a signal already durable. A signal with a {@code null}
     * message id has no identity and is always inserted. Otherwise this
     * behaves exactly as {@link #saveSignal}, including the store-assigned
     * {@code receivedAt}.
     *
     * <p>The check and the insert must be atomic with respect to concurrent
     * calls: two nodes delivering the same identity at once must leave exactly
     * one row (a relational store uses a unique index on the three columns,
     * partial on {@code message_id IS NOT NULL}).
     *
     * <p>The default delegates to {@link #saveSignal} and returns {@code true}
     * — no deduplication. A store that cannot enforce identity still loses no
     * signal; it only keeps today's at-least-once duplicates.
     *
     * @param signal the signal to persist
     * @return {@code true} if a row was inserted; {@code false} if a row with
     *         the same non-null identity already existed (nothing written)
     */
    default boolean saveSignalIfAbsent(WorkflowSignal signal) {
        saveSignal(signal);
        return true;
    }

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
     * {@link #markSignalConsumed(UUID)} fenced on the consuming run's lease:
     * the flip is applied only while the instance row of
     * {@code lease.instanceId()} carries {@code lease.epoch()}, checked in
     * the same statement but without locking the row (best-effort). A stale
     * lease is reported <em>silently</em> — {@code false}, nothing written.
     *
     * <p>The engine's own consume path does <b>not</b> use this overload: it
     * writes the {@code SIGNAL_RECEIVED} memo and the flip together through
     * {@link #appendEventAndConsumeSignal}. This overload is for a store user
     * that wants the flip refused once the lease is stale. The default
     * delegates to the unfenced form — see the class Javadoc.
     *
     * @param signalId the signal UUID to mark as consumed
     * @param lease    the consuming run's lease
     * @return {@code true} if the transition was applied; {@code false} if the
     *         signal was already consumed <em>or</em> the lease is stale
     */
    default boolean markSignalConsumed(UUID signalId, RunLease lease) {
        return markSignalConsumed(signalId);
    }

    /**
     * Consumes one signal row: appends its {@code SIGNAL_RECEIVED} memo
     * (fenced on {@code lease}, exactly as {@link #appendEvent(WorkflowEvent,
     * RunLease)}) and flips the row's {@code consumed} flag, as one unit.
     *
     * <h4>Overriding stores — atomic</h4>
     * <p>A store that overrides this writes both or neither, in one
     * transaction:
     * <ul>
     *   <li>both written → {@code true};</li>
     *   <li>the row was no longer unconsumed (a concurrent consumer — another
     *       parallel branch, another runner — took it first), or the
     *       transaction lost a serialization conflict → {@code false} and
     *       <b>nothing was written</b>: no memo, row untouched. The caller may
     *       pick another row for the same sequence;</li>
     *   <li>stale lease → {@link FencedWriteException}, nothing written;</li>
     *   <li>an event already at {@code memo}'s sequence →
     *       {@link DuplicateEventException}, nothing written.</li>
     * </ul>
     * Atomicity closes two holes the two-statement form leaves open: a crash
     * between the memo and the flip left a consumed memo beside an unconsumed
     * row that the next same-name await consumed a second time; and two
     * consumers racing for one row both appended memos and both took its
     * payload.
     *
     * <h4>The default — at-least-once</h4>
     * <p>The default composes {@link #appendEvent(WorkflowEvent, RunLease)}
     * and the unfenced {@link #markSignalConsumed(UUID)}, and <b>always
     * returns {@code true} once the memo is appended</b>: the appended memo is
     * the durable truth for its sequence, so a lost flip must not send the
     * caller looking for another row to memoize at the same sequence. A lost
     * flip is logged at {@code WARN}. The crash window between the two writes
     * remains — a store relying on the default keeps the pre-V9 at-least-once
     * consume semantics.
     *
     * @param memo     the {@code SIGNAL_RECEIVED} event to append;
     *                 {@code memo.workflowInstanceId()} must equal
     *                 {@code lease.instanceId()}
     * @param lease    the consuming run's lease
     * @param signalId the signal row to consume
     * @return {@code true} if the memo was written (and, for an atomic store,
     *         the row consumed); {@code false} only from an atomic store, when
     *         nothing was written
     * @throws FencedWriteException    if the lease is stale (nothing written)
     * @throws DuplicateEventException if an event already exists at the
     *                                 memo's sequence (nothing written)
     */
    default boolean appendEventAndConsumeSignal(WorkflowEvent memo, RunLease lease, UUID signalId) {
        appendEvent(memo, lease);
        if (!markSignalConsumed(signalId)) {
            org.slf4j.LoggerFactory.getLogger(WorkflowStore.class).warn(
                    "Signal row {} was already consumed when instance {} memoized it at seq {} — the "
                            + "memo stands (this store's consume is not atomic)",
                    signalId, memo.workflowInstanceId(), memo.sequenceNumber());
        }
        return true;
    }

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

    /**
     * Purges unconsumed signals addressed to workflows that finished long ago,
     * so the signal table does not grow without bound.
     *
     * <p>Deletes exactly the signals with {@code consumed = false} whose
     * {@link WorkflowSignal#workflowInstanceId()} references an instance whose
     * status is {@link io.b2mash.maestro.core.model.WorkflowStatus#COMPLETED
     * COMPLETED} or {@link io.b2mash.maestro.core.model.WorkflowStatus#TERMINATED
     * TERMINATED} and whose {@code completedAt} is strictly before
     * {@code cutoff}. No such workflow can ever consume them. Everything else
     * must survive:
     * <ul>
     *   <li><b>{@code FAILED} instances are excluded.</b> A failed workflow can
     *       be resumed by
     *       {@link io.b2mash.maestro.core.engine.WorkflowExecutor#retryWorkflow
     *       retryWorkflow}, and the retried run may still consume the signals
     *       that arrived after it failed (Issue 19) — purging them would make
     *       the retry wait for a signal that was delivered and then
     *       discarded.</li>
     *   <li><b>Orphans are never touched.</b> A signal with a {@code null}
     *       {@code workflowInstanceId} belongs to a workflow that has not
     *       started yet (pre-delivery) and will be adopted when it does.</li>
     *   <li><b>Consumed rows are never touched here.</b> Their retention is a
     *       separate concern this method does not address.</li>
     *   <li>Instances with a {@code null} {@code completedAt}, or one at or
     *       after {@code cutoff}, keep their signals.</li>
     * </ul>
     *
     * <p><b>Idempotent and safe to run concurrently.</b> The engine's signal
     * janitor calls this periodically on every node without leader election;
     * a second call with the same cutoff deletes nothing and returns
     * {@code 0}. Shared-database implementations should compare against the
     * stored {@code completed_at} in a single statement.
     *
     * @param cutoff signals of instances completed strictly before this instant
     *               are purged
     * @return the number of signal rows deleted
     */
    int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff);

    // ── Retention ────────────────────────────────────────────────────────

    /**
     * Deletes one batch of long-finished workflow instances together with
     * their entire history — events, signals and timers — so the store does
     * not grow without bound.
     *
     * <p>Selects at most {@code batchSize} instances whose status equals
     * {@code status} and whose {@code completedAt} is non-null and strictly
     * before {@code cutoff}, oldest first, and deletes for each of them:
     * <ul>
     *   <li>every event row of the instance,</li>
     *   <li>every signal row whose {@link WorkflowSignal#workflowInstanceId()}
     *       is the instance — consumed <em>or not</em> (a workflow that is
     *       gone can never consume them, and unlike
     *       {@link #deleteUnconsumedSignalsForTerminalInstancesBefore} this
     *       purge takes the whole history),</li>
     *   <li>every timer row of the instance, whatever its status (a
     *       {@code TERMINATED} workflow may leave {@code PENDING} timers
     *       behind), and finally</li>
     *   <li>the instance row itself.</li>
     * </ul>
     * Everything else must survive: instances in any other status, instances
     * with a {@code null} {@code completedAt} or one at or after the cutoff,
     * and orphaned signals (a {@code null} {@code workflowInstanceId}) —
     * which may still be adopted by a <em>future</em> instance reusing the
     * same workflow ID.
     *
     * <p><b>One batch is one transaction, and it is all-or-nothing.</b> The
     * children must be deleted before the parent, because the shipped schema
     * declares the child foreign keys without {@code ON DELETE CASCADE}. If
     * the final instance delete removes fewer rows than were selected — a
     * {@code FAILED} candidate was retried to {@code RUNNING} between the
     * select and the delete — the whole batch must roll back rather than
     * leave a now-active workflow with its memoization log gone. A store
     * that can lock the selected rows (Postgres {@code FOR UPDATE SKIP
     * LOCKED}) excludes that race outright and also lets two sweepers that
     * somehow run at once take disjoint batches.
     *
     * <p><b>Status is passed by the caller</b>, one status at a time, because
     * each terminal status has its own retention: {@code FAILED} workflows
     * are kept longer, since an operator may still retry them. Passing a
     * non-terminal status is a programming error.
     *
     * <p><b>Idempotent.</b> A second call with the same arguments after the
     * first emptied the candidate set returns {@link PurgeResult#EMPTY}. The
     * engine's retention sweeper calls this in a loop, under leader election,
     * until a batch comes back smaller than {@code batchSize}.
     *
     * @param status    the terminal status to purge; must satisfy
     *                  {@link io.b2mash.maestro.core.model.WorkflowStatus#isTerminal()}
     * @param cutoff    instances with {@code completedAt} strictly before this
     *                  instant are candidates
     * @param batchSize the most instances to delete in this call; must be positive
     * @return how many rows of each table were deleted; never {@code null}
     * @throws IllegalArgumentException if {@code status} is not terminal or
     *                                  {@code batchSize} is not positive
     */
    PurgeResult purgeTerminalInstancesBefore(io.b2mash.maestro.core.model.WorkflowStatus status,
                                             Instant cutoff, int batchSize);

    // ── Timer operations ─────────────────────────────────────────────────

    /**
     * Persists a durable timer.
     *
     * @param timer the timer to persist
     */
    void saveTimer(WorkflowTimer timer);

    /**
     * {@link #saveTimer(WorkflowTimer)} fenced on the arming run's lease: the
     * row is inserted if and only if the instance row of
     * {@code lease.instanceId()} currently carries {@code lease.epoch()},
     * checked atomically with the insert. A stale lease throws
     * {@link FencedWriteException} and writes nothing — a fenced node must
     * not arm a deadline that later fires on the adopting node's workflow. The
     * default delegates to the unfenced form — see the class Javadoc.
     *
     * @param timer the timer to persist; {@code timer.workflowInstanceId()}
     *              must equal {@code lease.instanceId()}
     * @param lease the arming run's lease
     * @throws FencedWriteException if the stored lease epoch differs from
     *                              {@code lease.epoch()} (nothing was written)
     */
    default void saveTimer(WorkflowTimer timer, RunLease lease) {
        saveTimer(timer);
    }

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
     * sequence number of the {@code sleep()} or parked {@code awaitSignal()}
     * that created it). If an
     * implementation somehow holds duplicates, it must return one of them
     * deterministically.
     *
     * @param workflowInstanceId the owning workflow instance
     * @param timerId            the logical timer ID (e.g. {@code "sleep-2"} or
     *                           {@code "await-signal-7"}),
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
     * {@link #markTimerFired(UUID)} fenced on the run's lease — the form a
     * workflow's own thread uses when it self-fires an await deadline it has
     * decided has elapsed, <em>before</em> it appends the {@code SIGNAL_TIMEOUT}
     * memo. The flip is applied only while the instance row of
     * {@code lease.instanceId()} carries {@code lease.epoch()}, checked in the
     * same statement but without locking the row (best-effort); a stale lease
     * is reported silently as {@code false}, nothing written — and the memo
     * append that follows is the loud, atomic rejection. A fenced run must not
     * retire a deadline the adopting node's run is still parked on. The timer
     * <em>leader</em> keeps using the unfenced form: it fires on behalf of
     * whichever run owns the workflow, so no single lease applies. The
     * default delegates to the unfenced form — see the class Javadoc.
     *
     * @param timerId the timer UUID to mark as fired
     * @param lease   the firing run's lease
     * @return {@code true} if the transition was applied; {@code false} if the
     *         timer was already terminal <em>or</em> the lease is stale
     */
    default boolean markTimerFired(UUID timerId, RunLease lease) {
        return markTimerFired(timerId);
    }

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

    /**
     * {@link #markTimerCancelled(UUID)} fenced on the run's lease: the flip is
     * applied only while the instance row of {@code lease.instanceId()}
     * carries {@code lease.epoch()}, checked in the same statement but without
     * locking the row (best-effort); a stale lease is reported silently as
     * {@code false}, nothing written. The engine cancels an await's deadline
     * row right after the loud fenced append of the {@code SIGNAL_RECEIVED}
     * memo that made it moot, so a flip lost to an adoption in that gap
     * leaves at worst a {@code PENDING} row the adopter's run re-reads. The
     * default delegates to the unfenced form — see the class Javadoc.
     *
     * @param timerId the timer UUID to cancel
     * @param lease   the cancelling run's lease
     * @return {@code true} if the transition was applied; {@code false} if the
     *         timer was already terminal <em>or</em> the lease is stale
     */
    default boolean markTimerCancelled(UUID timerId, RunLease lease) {
        return markTimerCancelled(timerId);
    }
}
