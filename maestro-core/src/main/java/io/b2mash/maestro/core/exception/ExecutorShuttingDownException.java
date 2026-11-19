package io.b2mash.maestro.core.exception;

/**
 * Thrown by {@code WorkflowExecutor.retryWorkflow} when this executor refuses
 * the Retry because it is shutting down or has shut down (pilot readiness P1):
 * the call was refused by the executor's lifecycle registry, or given up at
 * the shutdown deadline before it wrote anything. Nothing was read or written
 * after the refusal, and the workflow is still {@code FAILED}.
 *
 * <p>Unlike a store failure, this is a routine refusal by a stopping node: a
 * caller driving Retry from an at-least-once transport should not acknowledge
 * the command, so it is redelivered, and may log it without a stack trace.
 * {@code AdminCommandDispatcher} does exactly that. (A start refused for the
 * same reason keeps throwing {@link IllegalStateException}, its long-standing
 * contract.)
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share across
 * threads.
 */
public final class ExecutorShuttingDownException extends MaestroException {

    private final String workflowId;

    /**
     * @param workflowId the workflow the refused call was for
     * @param message    the explanation
     */
    public ExecutorShuttingDownException(String workflowId, String message) {
        super(message);
        this.workflowId = workflowId;
    }

    /** @return the workflow the refused call was for */
    public String workflowId() {
        return workflowId;
    }
}
