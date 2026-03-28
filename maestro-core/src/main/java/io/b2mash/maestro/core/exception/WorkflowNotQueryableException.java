package io.b2mash.maestro.core.exception;

import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a query targets a workflow that is not currently running
 * in-memory on this executor instance.
 *
 * <p>This can happen when:
 * <ul>
 *   <li>The workflow completed or failed (terminal state).</li>
 *   <li>The service was restarted and the workflow hasn't been recovered yet.</li>
 *   <li>The workflow is running on a different service instance.</li>
 * </ul>
 *
 * <p>Future versions may support replay-based queries for this case.
 */
public final class WorkflowNotQueryableException extends QueryException {

    private final @Nullable WorkflowStatus status;

    /**
     * @param workflowId the target workflow's business ID
     * @param queryName  the query name that was requested
     * @param status     the workflow's current status, or {@code null} if unknown
     */
    public WorkflowNotQueryableException(String workflowId, String queryName,
                                         @Nullable WorkflowStatus status) {
        super(("Workflow '%s' is not running in-memory on this executor (status=%s). " +
                "Query '%s' cannot be served. Replay-based queries are planned for a future version.")
                        .formatted(workflowId, status, queryName),
                workflowId, queryName);
        this.status = status;
    }

    /** Returns the workflow's current persisted status, or {@code null} if the instance was not found in the store. */
    public @Nullable WorkflowStatus status() {
        return status;
    }
}
