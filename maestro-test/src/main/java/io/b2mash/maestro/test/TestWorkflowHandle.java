package io.b2mash.maestro.test;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Handle to a workflow started via {@link TestWorkflowEnvironment}.
 *
 * <p>Provides methods to deliver signals, query status, wait for results,
 * and inspect the event log for test assertions.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var handle = env.startWorkflow(OrderWorkflow.class, input);
 * handle.signal("payment.result", paymentResult);
 *
 * var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 * assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
 * }</pre>
 */
public final class TestWorkflowHandle {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    private final String workflowId;
    private final UUID instanceId;
    private final WorkflowExecutor executor;
    private final InMemoryWorkflowStore store;
    private final PayloadSerializer serializer;

    TestWorkflowHandle(
            String workflowId,
            UUID instanceId,
            WorkflowExecutor executor,
            InMemoryWorkflowStore store,
            PayloadSerializer serializer
    ) {
        this.workflowId = workflowId;
        this.instanceId = instanceId;
        this.executor = executor;
        this.store = store;
        this.serializer = serializer;
    }

    // ── Signal delivery ──────────────────────────────────────────────────

    /**
     * Delivers a signal to this workflow.
     *
     * @param signalName the signal name (e.g., {@code "payment.result"})
     * @param payload    the signal payload, or {@code null}
     */
    public void signal(String signalName, @Nullable Object payload) {
        executor.deliverSignal(workflowId, signalName, payload);
    }

    // ── Result retrieval ─────────────────────────────────────────────────

    /**
     * Blocks until the workflow completes and returns the deserialized result.
     *
     * <p>Polls the store every 10ms until the workflow reaches a terminal
     * state. If the workflow fails, throws a {@link RuntimeException}
     * wrapping the failure details.
     *
     * @param <T>     the result type
     * @param type    the expected result class
     * @param timeout maximum time to wait
     * @return the workflow result
     * @throws RuntimeException  if the workflow failed
     * @throws TimeoutException  if the timeout elapses before completion
     */
    public <T> T getResult(Class<T> type, Duration timeout) throws TimeoutException {
        var instance = awaitTerminal(timeout);

        if (instance.status() == WorkflowStatus.FAILED) {
            var output = instance.output();
            var detail = output != null ? output.toString() : "unknown";
            throw new RuntimeException("Workflow '%s' failed: %s".formatted(workflowId, detail));
        }

        return serializer.deserialize(instance.output(), type);
    }

    /**
     * Blocks until the workflow completes, ignoring the result.
     *
     * @param timeout maximum time to wait
     * @throws RuntimeException if the workflow failed
     * @throws TimeoutException if the timeout elapses before completion
     */
    public void awaitCompletion(Duration timeout) throws TimeoutException {
        var instance = awaitTerminal(timeout);

        if (instance.status() == WorkflowStatus.FAILED) {
            var output = instance.output();
            var detail = output != null ? output.toString() : "unknown";
            throw new RuntimeException("Workflow '%s' failed: %s".formatted(workflowId, detail));
        }
    }

    // ── Status and identity ──────────────────────────────────────────────

    /**
     * Returns the current workflow status.
     */
    public WorkflowStatus getStatus() {
        return store.getInstance(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow '%s' not found in store".formatted(workflowId)))
                .status();
    }

    /**
     * Returns the business workflow ID.
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Returns the workflow instance UUID.
     */
    public UUID getInstanceId() {
        return instanceId;
    }

    // ── Event log ────────────────────────────────────────────────────────

    /**
     * Returns the workflow's memoization event log, ordered by sequence.
     *
     * <p>Useful for asserting that specific activities were executed,
     * signals were received, or timers were scheduled.
     */
    public List<WorkflowEvent> getEvents() {
        return store.getEvents(instanceId);
    }

    // ── Query ────────────────────────────────────────────────────────────

    /**
     * Queries the running workflow by invoking a {@code @QueryMethod}.
     *
     * @param <T>        the result type
     * @param queryName  the query name
     * @param queryArg   the query argument, or {@code null}
     * @param resultType the expected result type
     * @return the query result
     */
    public <T> T query(String queryName, @Nullable Object queryArg, Class<T> resultType) {
        return executor.queryWorkflow(workflowId, queryName, queryArg, resultType);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private io.b2mash.maestro.core.model.WorkflowInstance awaitTerminal(Duration timeout) throws TimeoutException {
        var deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            var instance = store.getInstance(workflowId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workflow '%s' not found in store".formatted(workflowId)));

            if (instance.status().isTerminal()) {
                return instance;
            }

            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for workflow completion", e);
            }
        }

        var currentStatus = store.getInstance(workflowId)
                .map(i -> i.status().toString())
                .orElse("NOT_FOUND");
        throw new TimeoutException(
                "Workflow '%s' did not complete within %s (current status: %s)"
                        .formatted(workflowId, timeout, currentStatus));
    }
}
