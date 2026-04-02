package io.b2mash.maestro.core.exception;

/**
 * Thrown when a workflow instance cannot be found by its workflow ID.
 */
public final class WorkflowNotFoundException extends MaestroException {

    private final String workflowId;

    /**
     * @param workflowId the business workflow ID that was not found
     */
    public WorkflowNotFoundException(String workflowId) {
        super("Workflow not found: " + workflowId);
        this.workflowId = workflowId;
    }

    /** Returns the workflow ID that was not found. */
    public String workflowId() {
        return workflowId;
    }
}
