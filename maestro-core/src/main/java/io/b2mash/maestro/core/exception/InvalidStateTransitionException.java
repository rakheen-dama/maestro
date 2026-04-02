package io.b2mash.maestro.core.exception;

import io.b2mash.maestro.core.model.WorkflowStatus;

/**
 * Thrown when an illegal workflow state transition is attempted.
 *
 * @see WorkflowStatus
 */
public final class InvalidStateTransitionException extends MaestroException {

    private final String workflowId;
    private final WorkflowStatus from;
    private final WorkflowStatus to;

    /**
     * @param workflowId the workflow ID
     * @param from       the current status
     * @param to         the attempted target status
     */
    public InvalidStateTransitionException(String workflowId, WorkflowStatus from, WorkflowStatus to) {
        super("Invalid state transition for workflow '%s': %s → %s"
                .formatted(workflowId, from, to));
        this.workflowId = workflowId;
        this.from = from;
        this.to = to;
    }

    /** Returns the workflow ID. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the current status. */
    public WorkflowStatus from() {
        return from;
    }

    /** Returns the attempted target status. */
    public WorkflowStatus to() {
        return to;
    }
}
