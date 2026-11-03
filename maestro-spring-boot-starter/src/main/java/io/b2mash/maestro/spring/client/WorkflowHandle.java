package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Handle to an existing workflow: signal it, query it, read its durable
 * status and result, or terminate it. Created via
 * {@link MaestroClient#getWorkflow(String)} or
 * {@link MaestroClient#getWorkflow(String, Class)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Send a signal to a running workflow
 * maestro.getWorkflow("order-abc").signal("payment.result", paymentResult);
 *
 * // Query a running workflow's in-memory state (owning node only)
 * String step = maestro.getWorkflow("order-abc")
 *     .query("currentStep", String.class);
 *
 * // Durable status and result (any node)
 * WorkflowStatus status = maestro.getWorkflow("order-abc").status();
 * Optional<OrderResult> result = maestro.getWorkflow("order-abc", OrderResult.class).result();
 *
 * // Stop it
 * maestro.getWorkflow("order-abc").terminate("customer cancelled");
 * }</pre>
 *
 * <h2>Store reads versus queries</h2>
 * <p>{@link #status()}, {@link #result()} and {@link #terminate(String)} read
 * and write the {@link WorkflowStore}, so they work from <b>any</b> node of the
 * service. {@link #query(String, Class)} invokes a {@code @QueryMethod} on the
 * live workflow object, which exists only on the node currently running the
 * workflow.
 *
 * <p>Creating a handle does not check that the workflow exists; each call
 * resolves the workflow ID when it runs.
 *
 * <p><b>Thread safety:</b> This class is immutable and safe for concurrent use.
 *
 * @param <R> the workflow's result type, as passed to
 *            {@link MaestroClient#getWorkflow(String, Class)} ({@code Object}
 *            for an untyped handle)
 */
public final class WorkflowHandle<R> {

    private final WorkflowExecutor executor;
    private final WorkflowStore store;
    private final PayloadSerializer serializer;
    private final String workflowId;
    private final Class<R> resultType;

    WorkflowHandle(
            WorkflowExecutor executor,
            WorkflowStore store,
            PayloadSerializer serializer,
            String workflowId,
            Class<R> resultType
    ) {
        this.executor = executor;
        this.store = store;
        this.serializer = serializer;
        this.workflowId = workflowId;
        this.resultType = resultType;
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
     * <p>Answered by the live workflow object, so it works only on the node
     * currently running the workflow; use {@link #status()} or
     * {@link #result()} for durable state readable from any node.
     *
     * @param queryName  the query name (from {@code @QueryMethod.name()} or the method name)
     * @param resultType the expected result type
     * @param <Q>        the query result type
     * @return the query result
     */
    public <Q> Q query(String queryName, Class<Q> resultType) {
        return executor.queryWorkflow(workflowId, queryName, null, resultType);
    }

    /**
     * Queries the running workflow by name with an argument.
     *
     * <p>Owning node only — see {@link #query(String, Class)}.
     *
     * @param queryName  the query name
     * @param queryArg   the query argument, or {@code null}
     * @param resultType the expected result type
     * @param <Q>        the query result type
     * @return the query result
     */
    public <Q> Q query(String queryName, @Nullable Object queryArg, Class<Q> resultType) {
        return executor.queryWorkflow(workflowId, queryName, queryArg, resultType);
    }

    /**
     * Returns the workflow's current durable status.
     *
     * <p>Reads the store, so it works from any node — unlike
     * {@link #query(String, Class)}, which needs the node running the workflow.
     *
     * @return the status persisted for this workflow
     * @throws WorkflowNotFoundException if no workflow with this ID exists
     */
    public WorkflowStatus status() {
        return instance().status();
    }

    /**
     * Returns the workflow's result, if it has completed with one.
     *
     * <p>Reads the store, so it works from any node — unlike
     * {@link #query(String, Class)}, which needs the node running the workflow.
     *
     * @return the output deserialized to {@code R} when the workflow is
     *         {@link WorkflowStatus#COMPLETED COMPLETED} with a non-null output;
     *         empty while it is still active, when it {@code FAILED} or was
     *         {@code TERMINATED}, or when it completed without a value
     * @throws WorkflowNotFoundException if no workflow with this ID exists
     */
    public Optional<R> result() {
        var instance = instance();
        if (instance.status() != WorkflowStatus.COMPLETED) {
            return Optional.empty();
        }
        return Optional.ofNullable(serializer.deserialize(instance.output(), resultType));
    }

    /**
     * Terminates the workflow: marks it {@link WorkflowStatus#TERMINATED} and
     * stops it. No compensation runs.
     *
     * <p>Writes the store, so it works from any node; the running workflow
     * converges on the terminal status as described on
     * {@link WorkflowExecutor#terminateWorkflow(String, String)}.
     *
     * @param reason why the workflow is being terminated (recorded on the
     *               instance), or {@code null}
     * @return what the call did — {@code TERMINATED}, {@code ALREADY_TERMINAL}
     *         (no change), or {@code NOT_FOUND}
     * @throws io.b2mash.maestro.core.exception.OptimisticLockException if the
     *         instance row is being written continuously and the attempt budget
     *         is exhausted; transient — retry the call
     */
    public WorkflowExecutor.TerminateOutcome terminate(@Nullable String reason) {
        return executor.terminateWorkflow(workflowId, reason);
    }

    /**
     * Returns the workflow's business ID.
     *
     * @return the workflow ID this handle was created for
     */
    public String workflowId() {
        return workflowId;
    }

    private WorkflowInstance instance() {
        return store.getInstance(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }
}
