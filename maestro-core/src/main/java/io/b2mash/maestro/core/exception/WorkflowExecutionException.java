package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a workflow method execution fails with an unhandled exception.
 *
 * <p>The original exception is available via {@link #getCause()}. When the
 * failure is read back from the store (for example by the Spring client's
 * {@code startAndWait}), the cause is the exception reconstructed from the
 * recorded type and message, and is {@code null} for a terminated workflow.
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

    /**
     * Creates the exception with an explicit message — used where the
     * failure is read back from the store, so the message can name the
     * recorded exception and the cause can be its reconstruction.
     *
     * @param workflowId the workflow ID whose execution failed or was stopped
     * @param message    the detail message
     * @param cause      the (reconstructed) exception the workflow ended with,
     *                   or {@code null} when there is none (for example a
     *                   terminated workflow)
     */
    public WorkflowExecutionException(String workflowId, String message, @Nullable Throwable cause) {
        super(message);
        this.workflowId = workflowId;
        if (cause != null) {
            initCause(cause);
        }
    }

    /** Returns the workflow ID whose execution failed. */
    public String workflowId() {
        return workflowId;
    }
}
