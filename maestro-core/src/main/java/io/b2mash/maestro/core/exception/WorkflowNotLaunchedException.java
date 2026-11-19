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
 * <p>Also thrown by a start or a Retry that this node gave up at its shutdown
 * deadline after the row was written — a start whose {@code createInstance}
 * or workflow factory returned late, or a Retry whose reopen did (pilot
 * readiness P1, Issue 70): the row is {@code RUNNING} and nothing more was
 * written by this node; a node of the service adopts it through recovery.
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
