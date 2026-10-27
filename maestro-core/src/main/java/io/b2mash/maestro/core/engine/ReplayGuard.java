package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;

import java.util.Arrays;
import java.util.Optional;

/**
 * The single place the engine turns "the persisted event at this sequence is
 * not the step the current code performs here" into a stand-down (C10).
 *
 * <p>Memoization matches a stored event to a step by <em>sequence number</em>.
 * Without this guard nothing checked that the event found at that sequence was
 * written by the <em>same</em> operation: a renamed or reordered activity
 * silently returned another step's payload, and a side effect landing on a
 * slot recorded by a different kind of operation was misread as an unreadable
 * payload from a newer node. This guard compares the stored event's step name
 * and type (and, for a {@code parallel()} fork marker, its branch count) with
 * what the current code is about to do, and stands the run down as
 * {@link UnknownWorkflowHistoryException.Kind#NONDETERMINISTIC_HISTORY} on any
 * disagreement — the workflow code changed without a {@code workflow.version()}
 * gate, so rollback, not failure-and-compensate, is the remedy.
 *
 * <h2>Ordering contract</h2>
 * <p>At every replay read this guard is applied <b>always after
 * {@link UnknownHistoryGuard#requireKnown(WorkflowEvent, String)}</b> — it
 * assumes a known type, so an {@link EventType#UNKNOWN} row must already have
 * stood down as the unknown-type kind — and <b>always before any live side
 * effect at that slot</b>: before an activity executes, before a side effect
 * is appended, before anything leaves the process. Standing down later means
 * the outside world was already touched for a step the history says never
 * happened.
 *
 * <h2>Visibility</h2>
 * <p>Public for parity with {@link UnknownHistoryGuard}: replay reads outside
 * this package (for example {@code io.b2mash.maestro.core.saga.SagaManager})
 * must be able to apply the identical guard. It is internal engine API — not
 * part of the embedder-facing surface — and carries no compatibility promise.
 *
 * <p><b>Thread safety:</b> stateless; all methods are static and safe to call
 * from any thread.
 */
public final class ReplayGuard {

    private ReplayGuard() {
    }

    /**
     * Returns {@code stored} when its type is one of {@code compatibleTypes}
     * <em>and</em> its step name equals {@code expectedStepName}; otherwise the
     * run stands down as nondeterministic history.
     *
     * <p>A {@code null} stored step name (a lifecycle event sitting in a replay
     * slot) never matches.
     *
     * @param stored           the event just read from the store at the
     *                         current sequence — already admitted by
     *                         {@link UnknownHistoryGuard#requireKnown}
     * @param workflowId       the workflow being replayed, for the message
     * @param expectedStepName the step name the current code would record at
     *                         this sequence
     * @param compatibleTypes  the event types the current operation can
     *                         replay from
     * @return {@code stored}, for chaining into a replay decision
     * @throws UnknownWorkflowHistoryException with
     *         {@link UnknownWorkflowHistoryException.Kind#NONDETERMINISTIC_HISTORY}
     *         if the stored event does not match
     */
    public static WorkflowEvent requireMatch(WorkflowEvent stored, String workflowId,
                                             String expectedStepName, EventType... compatibleTypes) {
        var typeMatches = Arrays.asList(compatibleTypes).contains(stored.eventType());
        if (typeMatches && expectedStepName.equals(stored.stepName())) {
            return stored;
        }
        throw mismatch(stored, workflowId, expectedStepName);
    }

    /**
     * Stands the run down if {@code stored} holds an event: the current code
     * records nothing at this slot, so anything there was written by a
     * different shape of the same operation.
     *
     * <p>Used where one operation has two layouts that never share a slot —
     * sequential vs parallel saga compensation — to detect a history written by
     * the other layout before any action runs. The detail names
     * {@code expectedOperation} where {@link #requireMatch} would name a step.
     *
     * @param stored            the (possibly empty) read of the slot — already
     *                          admitted by {@link UnknownHistoryGuard#requireKnown}
     * @param workflowId        the workflow being replayed, for the message
     * @param expectedOperation the operation the current code performs, which
     *                          leaves this slot empty
     * @throws UnknownWorkflowHistoryException with
     *         {@link UnknownWorkflowHistoryException.Kind#NONDETERMINISTIC_HISTORY}
     *         if {@code stored} is present
     */
    public static void requireAbsent(Optional<WorkflowEvent> stored, String workflowId,
                                     String expectedOperation) {
        if (stored.isPresent()) {
            throw mismatch(stored.get(), workflowId, expectedOperation);
        }
    }

    /**
     * Returns {@code stored} when its {@code branchCount} payload field equals
     * {@code expectedBranchCount}; otherwise the run stands down.
     *
     * <p>A missing or non-numeric {@code branchCount} is an unreadable marker —
     * the payload kind ({@link UnknownHistoryGuard#payloadStandDown}), not
     * drift. A readable but different count means the code forks into a
     * different number of branches than the recorded run did.
     *
     * @param stored              the fork marker just read from the store —
     *                            already admitted by
     *                            {@link UnknownHistoryGuard#requireKnown} and,
     *                            normally, by {@link #requireMatch}
     * @param workflowId          the workflow being replayed, for the message
     * @param expectedBranchCount the number of branches the current code forks
     * @return {@code stored}, for chaining into a replay decision
     * @throws UnknownWorkflowHistoryException with
     *         {@link UnknownWorkflowHistoryException.Kind#UNKNOWN_EVENT_PAYLOAD}
     *         if the branch count cannot be read, or
     *         {@link UnknownWorkflowHistoryException.Kind#NONDETERMINISTIC_HISTORY}
     *         if it differs
     */
    public static WorkflowEvent requireBranchCount(WorkflowEvent stored, String workflowId,
                                                   int expectedBranchCount) {
        var seq = stored.sequenceNumber();
        var payload = stored.payload();
        var branchCount = payload == null ? null : payload.path("branchCount");
        if (branchCount == null || !branchCount.isIntegralNumber() || !branchCount.canConvertToInt()) {
            throw UnknownHistoryGuard.payloadStandDown(workflowId, seq,
                    "parallel() fork marker without a numeric branchCount");
        }
        var actual = branchCount.asInt();
        if (actual == expectedBranchCount) {
            return stored;
        }
        throw nondeterministic(workflowId, seq,
                "the current code forks %d branches but the history recorded %d"
                        .formatted(expectedBranchCount, actual),
                "seq=%d expected=%s branches=%d stored=%s branches=%d"
                        .formatted(seq, stored.stepName(), expectedBranchCount,
                                stored.stepName(), actual));
    }

    private static UnknownWorkflowHistoryException mismatch(WorkflowEvent stored, String workflowId,
                                                            String expectedStepName) {
        var seq = stored.sequenceNumber();
        return nondeterministic(workflowId, seq,
                "the current code performs '%s' but the history recorded '%s' (%s)"
                        .formatted(expectedStepName, stored.stepName(), stored.eventType()),
                "seq=%d expected=%s stored=%s type=%s"
                        .formatted(seq, expectedStepName, stored.stepName(), stored.eventType()));
    }

    private static UnknownWorkflowHistoryException nondeterministic(String workflowId, int seq,
                                                                    String what, String detail) {
        return new UnknownWorkflowHistoryException(workflowId, seq,
                UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY,
                ("Workflow '%s' replay diverged from its history at sequence %d: %s — the "
                        + "workflow code changed without a workflow.version() gate; standing "
                        + "this run down without recording a failure (roll back the deploy)")
                        .formatted(workflowId, seq, what),
                detail);
    }
}
