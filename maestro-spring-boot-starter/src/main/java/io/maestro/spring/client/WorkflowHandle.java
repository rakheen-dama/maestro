package io.maestro.spring.client;

import io.maestro.core.engine.WorkflowExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Handle to an existing workflow for signal delivery and queries.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Send a signal to a running workflow
 * maestro.getWorkflow("order-abc").signal("payment.result", paymentResult);
 *
 * // Query a running workflow's state
 * String status = maestro.getWorkflow("order-abc")
 *     .query("currentStep", String.class);
 * }</pre>
 *
 * <p><b>Thread safety:</b> This class is immutable and safe for concurrent use.
 */
public class WorkflowHandle {

    private final WorkflowExecutor executor;
    private final String workflowId;

    WorkflowHandle(WorkflowExecutor executor, String workflowId) {
        this.executor = executor;
        this.workflowId = workflowId;
    }

    /**
     * Delivers a signal to the workflow.
     *
     * <p>The signal is persisted immediately. If the workflow is waiting
     * for this signal, it is unparked. If the workflow hasn't reached the
     * await point yet, the signal is stored and consumed when it does.
     *
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     */
    public void signal(String signalName, @Nullable Object payload) {
        executor.deliverSignal(workflowId, signalName, payload);
    }

    /**
     * Queries the running workflow by name (no argument).
     *
     * @param queryName  the query name (from {@code @QueryMethod.name()} or the method name)
     * @param resultType the expected result type
     * @param <R>        the result type
     * @return the query result
     */
    public <R> R query(String queryName, Class<R> resultType) {
        return executor.queryWorkflow(workflowId, queryName, null, resultType);
    }

    /**
     * Queries the running workflow by name with an argument.
     *
     * @param queryName  the query name
     * @param queryArg   the query argument
     * @param resultType the expected result type
     * @param <R>        the result type
     * @return the query result
     */
    public <R> R query(String queryName, Object queryArg, Class<R> resultType) {
        return executor.queryWorkflow(workflowId, queryName, queryArg, resultType);
    }

    /**
     * Returns the workflow's business ID.
     */
    public String workflowId() {
        return workflowId;
    }
}
