package io.b2mash.maestro.core.exception;

/**
 * Thrown when a workflow method execution fails with an unhandled exception.
 *
 * <p>The original exception is available via {@link #getCause()}.
 */
public final class WorkflowExecutionException extends MaestroException {

    private final String workflowId;

    /**
     * @param workflowId the workflow ID whose execution failed
     * @param cause      the exception thrown by the workflow method
     */
    public WorkflowExecutionException(String workflowId, Throwable cause) {
        super("Workflow execution failed: " + workflowId, cause);
        this.workflowId = workflowId;
    }

    /** Returns the workflow ID whose execution failed. */
    public String workflowId() {
        return workflowId;
    }
}
