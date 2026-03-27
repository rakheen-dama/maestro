package io.maestro.core.exception;

/**
 * Base exception for workflow query errors.
 *
 * <p>This is a {@code sealed} class — all permitted subtypes cover the
 * distinct failure modes of the query system:
 * <ul>
 *   <li>{@link QueryNotDefinedException} — no query handler with the given name</li>
 *   <li>{@link WorkflowNotQueryableException} — the workflow is not currently
 *       in-memory on this executor</li>
 * </ul>
 *
 * <p>Callers can exhaustively match on subtypes:
 * <pre>{@code
 * switch (exception) {
 *     case QueryNotDefinedException e -> handleMissing(e);
 *     case WorkflowNotQueryableException e -> handleNotInMemory(e);
 * }
 * }</pre>
 */
public sealed class QueryException extends MaestroException
        permits QueryNotDefinedException, WorkflowNotQueryableException {

    private final String workflowId;
    private final String queryName;

    /**
     * @param message    descriptive error message
     * @param workflowId the target workflow's business ID
     * @param queryName  the query name that was requested
     */
    protected QueryException(String message, String workflowId, String queryName) {
        super(message);
        this.workflowId = workflowId;
        this.queryName = queryName;
    }

    /** Returns the target workflow's business ID. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the query name that was requested. */
    public String queryName() {
        return queryName;
    }
}
