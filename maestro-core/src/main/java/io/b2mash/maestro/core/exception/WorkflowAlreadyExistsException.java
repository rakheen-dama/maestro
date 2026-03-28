package io.b2mash.maestro.core.exception;

/**
 * Thrown when attempting to create a workflow instance with a workflow ID
 * that already exists in the store.
 */
public final class WorkflowAlreadyExistsException extends MaestroException {

    private final String workflowId;

    /**
     * @param workflowId the duplicate workflow ID
     */
    public WorkflowAlreadyExistsException(String workflowId) {
        super("Workflow already exists: " + workflowId);
        this.workflowId = workflowId;
    }

    /** Returns the duplicate workflow ID. */
    public String workflowId() {
        return workflowId;
    }
}
