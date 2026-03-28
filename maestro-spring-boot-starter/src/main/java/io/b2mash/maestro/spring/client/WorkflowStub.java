package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Stub for starting a workflow execution. Created via
 * {@link MaestroClient#newWorkflow(Class, WorkflowOptions)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Fire and forget
 * UUID instanceId = maestro.newWorkflow(OrderWorkflow.class, options)
 *     .startAsync(orderInput);
 *
 * // Start and wait for completion
 * OrderResult result = maestro.newWorkflow(OrderWorkflow.class, options)
 *     .startAndWait(orderInput, Duration.ofMinutes(5), OrderResult.class);
 * }</pre>
 *
 * <p><b>Thread safety:</b> This class is immutable and safe for concurrent use.
 * The {@link #startAndWait} method blocks the calling thread during polling.
 *
 * @param <T> the workflow class type
 */
public final class WorkflowStub<T> {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final WorkflowExecutor executor;
    private final WorkflowRegistration registration;
    private final WorkflowOptions options;
    private final PayloadSerializer serializer;
    private final WorkflowStore store;

    WorkflowStub(
            WorkflowExecutor executor,
            WorkflowRegistration registration,
            WorkflowOptions options,
            PayloadSerializer serializer,
            WorkflowStore store
    ) {
        this.executor = executor;
        this.registration = registration;
        this.options = options;
        this.serializer = serializer;
        this.store = store;
    }

    /**
     * Starts the workflow asynchronously and returns immediately.
     *
     * @param input the workflow input, or {@code null} for no-arg workflows
     * @return the workflow instance UUID (primary key)
     */
    public UUID startAsync(@Nullable Object input) {
        return executor.startWorkflow(
                options.workflowId(),
                registration.workflowType(),
                registration.taskQueue(),
                input,
                registration.workflowImpl(),
                registration.workflowMethod()
        );
    }

    /**
     * Starts the workflow and blocks until it completes or the timeout elapses.
     *
     * <p>Polls the workflow store for terminal status (COMPLETED, FAILED,
     * TERMINATED). On a virtual thread, the polling sleep yields the carrier
     * thread — no platform thread is blocked.
     *
     * @param input   the workflow input, or {@code null}
     * @param timeout maximum time to wait for completion
     * @param <R>     the result type
     * @param resultType the expected result type
     * @return the workflow output, or {@code null} if the workflow returned void/null
     * @throws TimeoutException if the timeout elapses before completion
     * @throws io.b2mash.maestro.core.exception.WorkflowExecutionException if the workflow failed
     */
    public <R> @Nullable R startAndWait(
            @Nullable Object input,
            Duration timeout,
            Class<R> resultType
    ) throws TimeoutException {
        startAsync(input);
        var deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            var instance = store.getInstance(options.workflowId());
            if (instance.isPresent() && instance.get().status().isTerminal()) {
                var status = instance.get().status();
                if (status == WorkflowStatus.FAILED || status == WorkflowStatus.TERMINATED) {
                    throw new WorkflowExecutionException(
                            options.workflowId(),
                            new RuntimeException("Workflow ended with status " + status));
                }
                // COMPLETED — deserialize output
                return serializer.deserialize(instance.get().output(), resultType);
            }

            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowExecutionException(options.workflowId(), e);
            }
        }

        throw new TimeoutException(
                "Workflow '%s' did not complete within %s".formatted(options.workflowId(), timeout));
    }
}
