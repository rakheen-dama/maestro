package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Handle to a workflow started through {@link MaestroEngineHarness}, reading
 * its state back from the real store.
 *
 * <p>Every accessor queries the backend rather than in-memory engine state, so
 * assertions made through this handle are assertions about what was actually
 * persisted.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond its identifiers; safe to share between threads.
 */
public final class WorkflowHandle {

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final PayloadSerializer serializer;
    private final String workflowId;
    private final UUID instanceId;

    WorkflowHandle(WorkflowStore store, WorkflowExecutor executor, PayloadSerializer serializer,
                   String workflowId, UUID instanceId) {
        this.store = store;
        this.executor = executor;
        this.serializer = serializer;
        this.workflowId = workflowId;
        this.instanceId = instanceId;
    }

    /** @return the business workflow ID */
    public String workflowId() {
        return workflowId;
    }

    /** @return the workflow instance UUID */
    public UUID instanceId() {
        return instanceId;
    }

    /**
     * @return the persisted instance
     * @throws IllegalStateException if the instance is missing from the store
     */
    public WorkflowInstance instance() {
        return store.getInstance(workflowId).orElseThrow(() ->
                new IllegalStateException("No instance in store for workflow " + workflowId));
    }

    /** @return the current persisted status */
    public WorkflowStatus status() {
        return instance().status();
    }

    /** @return the persisted event log, ordered by sequence number */
    public List<WorkflowEvent> events() {
        return store.getEvents(instanceId);
    }

    /** @return whether this executor is currently running the workflow in memory */
    public boolean isRunningLocally() {
        return executor.isRunning(workflowId);
    }

    /**
     * Waits until the workflow reaches the given status.
     *
     * @param expected the awaited status
     * @param timeout  the bound — be generous, this is a stability knob
     */
    public void awaitStatus(WorkflowStatus expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> i.status() == expected)
                        .orElse(false));
    }

    /**
     * Waits until the workflow reaches a terminal status <em>and</em> its
     * terminal event has been appended.
     *
     * <p>Both waits are needed. Finalising a run is two writes: the instance row
     * moves to {@code COMPLETED}/{@code FAILED} first, and the matching
     * {@code WORKFLOW_COMPLETED}/{@code WORKFLOW_FAILED} event is appended
     * after. A test that waits only on the status can therefore read the event
     * log during the gap and see the terminal event missing — invisible on a
     * fast machine, reproducible on slower CI. See {@link TerminalWait}.
     *
     * <p>The terminal event is <em>required</em>, not hoped for. Which node wins
     * the finalisation race is racy; that the shared log ends up carrying the
     * winner's terminal event is not. A terminal status with no terminal event
     * would be an engine defect, so this fails loudly on the timeout instead of
     * quietly returning the status — the earlier best-effort version could mask
     * the very race it was added to close. {@code TERMINATED} is the one
     * exemption, and it is handled in {@link TerminalWait}.
     *
     * @param timeout the bound
     * @return the terminal status reached
     */
    public WorkflowStatus awaitTerminal(Duration timeout) {
        return TerminalWait.awaitTerminal(store, workflowId, timeout).status();
    }

    /**
     * Reads the workflow's output payload.
     *
     * @param type the expected output type
     * @param <T>  the output type
     * @return the deserialized output, or {@code null} if none was recorded
     */
    public <T> @Nullable T result(Class<T> type) {
        return serializer.deserialize(instance().output(), type);
    }
}
