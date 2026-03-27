package io.maestro.core.model;

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

    /** Saga compensation began (unwinding completed activities in reverse order). */
    COMPENSATION_STARTED,

    /** Saga compensation completed for a single activity. */
    COMPENSATION_COMPLETED,

    /** Workflow completed successfully. */
    WORKFLOW_COMPLETED,

    /** Workflow failed (after retries exhausted or compensation finished). */
    WORKFLOW_FAILED
}
