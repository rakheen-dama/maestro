package io.maestro.core.model;

/**
 * Lifecycle states of a workflow instance.
 *
 * <p>State transitions follow the Maestro state machine:
 * <pre>
 * RUNNING → WAITING_SIGNAL | WAITING_TIMER | COMPLETED | FAILED | COMPENSATING
 * WAITING_SIGNAL → RUNNING (signal received or timeout)
 * WAITING_TIMER  → RUNNING (timer fires)
 * COMPENSATING   → FAILED  (compensation done)
 * FAILED         → RUNNING (manual retry)
 * Any active     → TERMINATED (admin action)
 * </pre>
 *
 * @see WorkflowInstance
 */
public enum WorkflowStatus {

    /** Workflow is actively executing on a virtual thread. */
    RUNNING,

    /** Workflow is parked, waiting for a named signal. */
    WAITING_SIGNAL,

    /** Workflow is parked, waiting for a durable timer to fire. */
    WAITING_TIMER,

    /** Workflow finished successfully. Terminal state. */
    COMPLETED,

    /** Workflow failed after retries exhausted or compensation completed. Terminal state. */
    FAILED,

    /** Saga compensation is in progress (unwinding completed activities). */
    COMPENSATING,

    /** Workflow was terminated by an admin action. Terminal state. */
    TERMINATED;

    /**
     * Returns {@code true} if this status represents a terminal state
     * from which no further automatic transitions occur.
     *
     * @return {@code true} for {@link #COMPLETED}, {@link #FAILED}, and {@link #TERMINATED}
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TERMINATED;
    }

    /**
     * Returns {@code true} if this status represents an active state
     * where the workflow is either executing or waiting to resume.
     *
     * @return {@code true} for {@link #RUNNING}, {@link #WAITING_SIGNAL},
     *         {@link #WAITING_TIMER}, and {@link #COMPENSATING}
     */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_SIGNAL
                || this == WAITING_TIMER || this == COMPENSATING;
    }
}
