package io.b2mash.maestro.core.exception;

/**
 * Thrown by a start whose workflow row <b>was persisted</b> but whose run was
 * not launched, on a node that runs no recovery poller: the instance lock was
 * lost between the row's write and the run's registration, and a single
 * relaunch could not take it either (PR #72 review). The workflow exists, as
 * {@code RUNNING}; it is not lost, and starting it again would only find it
 * ({@link WorkflowAlreadyExistsException}). It runs when a node of the service
 * starts with recovery enabled, or this node restarts.
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share across
 * threads.
 */
public final class WorkflowNotLaunchedException extends MaestroException {

    private final String workflowId;

    /**
     * @param workflowId the workflow whose row was persisted and not launched
     * @param message    the explanation, naming the workflow and how it gets run
     */
    public WorkflowNotLaunchedException(String workflowId, String message) {
        super(message);
        this.workflowId = workflowId;
    }

    /** @return the workflow whose row was persisted and not launched */
    public String workflowId() {
        return workflowId;
    }
}
