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
    VERSION_MARKER,

    /**
     * Row-mapper sentinel for a persisted {@code event_type} string this build
     * does not define — written by a <em>newer</em> node during a
     * mixed-version deploy window.
     *
     * <p><b>Never persisted.</b> Every {@code WorkflowStore.appendEvent}
     * implementation rejects it with an {@link IllegalArgumentException}, so
     * the sentinel can never round-trip into history. It exists only so a row
     * mapper can return something instead of throwing: an enum-parse failure
     * deep inside a store read surfaces as an ordinary exception, which the
     * engine would record as a workflow failure <em>and compensate</em> — for
     * work that never failed.
     *
     * <p>The engine detects this constant at every replay read and
     * {@linkplain io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException
     * stands the run down}: nothing is written, no compensation runs, the
     * instance keeps its recoverable status and an upgraded node adopts it.
     *
     * @see #fromStoredName(String)
     */
    UNKNOWN;

    /**
     * Total parse of a persisted {@code event_type} string: the matching
     * constant, or {@link #UNKNOWN} when this build does not define one.
     *
     * <p>Row mappers MUST use this instead of {@link #valueOf(String)} —
     * {@code valueOf} throws {@link IllegalArgumentException} for a type
     * written by a newer node, and that throw is indistinguishable from a
     * workflow failure by the time it reaches the executor.
     *
     * @param name the stored type string; may be {@code null}, which also
     *             yields {@link #UNKNOWN}
     * @return the matching constant, or {@link #UNKNOWN}
     */
    public static EventType fromStoredName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
