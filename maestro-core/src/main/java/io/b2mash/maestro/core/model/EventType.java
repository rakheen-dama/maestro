package io.b2mash.maestro.core.model;

/**
 * Types of events recorded in the workflow memoization log.
 *
 * <p>Each activity call, signal receipt, timer event, and lifecycle transition
 * is persisted as a {@link WorkflowEvent} with one of these types. During
 * recovery, the event log is replayed to restore workflow state.
 *
 * @see WorkflowEvent
 */
public enum EventType {

    /** Workflow execution started. Recorded once per run. */
    WORKFLOW_STARTED,

    /** An activity method invocation began. */
    ACTIVITY_STARTED,

    /** An activity method completed successfully. The result is stored in the event payload. */
    ACTIVITY_COMPLETED,

    /** An activity method failed after all retries were exhausted. */
    ACTIVITY_FAILED,

    /** A named signal was received and consumed by the workflow. */
    SIGNAL_RECEIVED,

    /** A durable timer was scheduled. */
    TIMER_SCHEDULED,

    /** A durable timer fired, resuming the workflow. */
    TIMER_FIRED,

    /** A durable timer was cancelled while the workflow was waiting on it. */
    TIMER_CANCELLED,

    /** Saga compensation began (unwinding completed activities in reverse order). */
    COMPENSATION_STARTED,

    /** Saga compensation completed (all compensations unwound). */
    COMPENSATION_COMPLETED,

    /** An individual compensation step completed successfully. */
    COMPENSATION_STEP_COMPLETED,

    /** An individual compensation step failed (remaining compensations continue). */
    COMPENSATION_STEP_FAILED,

    /** Workflow completed successfully. */
    WORKFLOW_COMPLETED,

    /** Workflow failed (after retries exhausted or compensation finished). */
    WORKFLOW_FAILED,

    /** A deterministic side-effect was memoized (e.g., currentTime, randomUUID). */
    SIDE_EFFECT,

    /**
     * An {@code awaitSignal} timed out; the timeout is memoized at the await's
     * sequence slot so replay re-raises it deterministically instead of
     * consuming a signal that arrived after the fact (Issue 19 — the signal
     * analogue of {@link #TIMER_CANCELLED}'s Issue 13 memoization).
     */
    SIGNAL_TIMEOUT,

    /**
     * A memoized versioning decision recorded by
     * {@code WorkflowContext.version(String, int, int)}.
     *
     * <p>Payload: <code>{"changeId": "...", "version": N}</code>; step name:
     * {@code $maestro:version:{changeId}}.
     *
     * <p>Introduced in 0.4.0 — nodes older than 0.4.0 cannot interpret this
     * type, so upgrade all nodes of a service together.
     */
    VERSION_MARKER
}
