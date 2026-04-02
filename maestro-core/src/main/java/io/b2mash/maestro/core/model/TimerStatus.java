package io.b2mash.maestro.core.model;

/**
 * Lifecycle states of a durable workflow timer.
 *
 * @see WorkflowTimer
 */
public enum TimerStatus {

    /** Timer is waiting to fire. */
    PENDING,

    /** Timer has fired and the workflow was resumed. */
    FIRED,

    /** Timer was cancelled before it could fire. */
    CANCELLED
}
