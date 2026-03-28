package io.b2mash.maestro.core.spi;

/**
 * Types of lifecycle events published to the admin dashboard.
 *
 * <p>These are distinct from {@link io.b2mash.maestro.core.model.EventType} —
 * the model event types are for the internal memoization log, while
 * lifecycle event types are for external observability and the admin dashboard.
 *
 * @see WorkflowLifecycleEvent
 * @see WorkflowMessaging#publishLifecycleEvent(WorkflowLifecycleEvent)
 */
public enum LifecycleEventType {

    /** Workflow execution started. */
    WORKFLOW_STARTED,

    /** Workflow completed successfully. */
    WORKFLOW_COMPLETED,

    /** Workflow failed (retries exhausted or compensation done). */
    WORKFLOW_FAILED,

    /** Workflow was terminated by admin action. */
    WORKFLOW_TERMINATED,

    /** An activity execution started. */
    ACTIVITY_STARTED,

    /** An activity execution completed successfully. */
    ACTIVITY_COMPLETED,

    /** An activity execution failed. */
    ACTIVITY_FAILED,

    /** A signal was received by a workflow. */
    SIGNAL_RECEIVED,

    /** A signal await timed out. */
    SIGNAL_TIMEOUT,

    /** A durable timer was scheduled. */
    TIMER_SCHEDULED,

    /** A durable timer fired. */
    TIMER_FIRED,

    /** Saga compensation started. */
    COMPENSATION_STARTED,

    /** Saga compensation completed. */
    COMPENSATION_COMPLETED,

    /** An individual compensation step completed. */
    COMPENSATION_STEP_COMPLETED,

    /** An individual compensation step failed. */
    COMPENSATION_STEP_FAILED
}
