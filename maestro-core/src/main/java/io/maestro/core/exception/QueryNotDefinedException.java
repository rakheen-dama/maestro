package io.maestro.core.exception;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when a query is requested for a name that has no corresponding
 * {@link io.maestro.core.annotation.QueryMethod} on the workflow type.
 */
public final class QueryNotDefinedException extends QueryException {

    private final String workflowType;

    /**
     * @param workflowId   the target workflow's business ID
     * @param queryName    the query name that was not found
     * @param workflowType the workflow type that was searched
     */
    public QueryNotDefinedException(@NonNull String workflowId, @NonNull String queryName,
                                    @NonNull String workflowType) {
        super("No @QueryMethod named '%s' defined on workflow type '%s' (workflowId='%s')"
                .formatted(queryName, workflowType, workflowId),
                workflowId, queryName);
        this.workflowType = workflowType;
    }

    /** Returns the workflow type that was searched for query methods. */
    public String workflowType() {
        return workflowType;
    }
}
