package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.engine.FailureReconstruction;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.model.WorkflowFailureDetail;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTerminationDetail;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

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

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStub.class);

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
     * <p>Builds a new workflow object for this run from the registration's
     * {@linkplain WorkflowRegistration#workflowFactory() factory} — a fresh
     * prototype bean, so instance fields are this run's state alone.
     *
     * @param input the workflow input, or {@code null} for no-arg workflows
     * @return the workflow instance UUID (primary key)
     * @throws io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException if a
     *         workflow with this ID already exists — including one whose earlier
     *         start failed to build its bean (below)
     * @throws io.b2mash.maestro.core.exception.WorkflowInstantiationException if the
     *         workflow bean cannot be created (for example a Spring
     *         {@code BeanCreationException} from a broken dependency, available as
     *         its {@linkplain Throwable#getCause() cause}). The run is not launched,
     *         but the instance row was already written and is left {@code RUNNING}:
     *         it is <em>not</em> rolled back. The recovery poller runs it once the
     *         bean can be built (after the dependency is fixed, typically by a
     *         redeploy), and until then every recovery pass skips it. Retrying
     *         {@code startAsync} with the same workflow ID therefore throws
     *         {@code WorkflowAlreadyExistsException} — the workflow exists and
     *         will run; it is not started a second time.
     */
    public UUID startAsync(@Nullable Object input) {
        return executor.startWorkflow(
                options.workflowId(),
                registration.workflowType(),
                registration.taskQueue(),
                input,
                registration.workflowFactory(),
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
     * @throws io.b2mash.maestro.core.exception.WorkflowExecutionException if the workflow
     *         failed — the message names the recorded exception type and message, and the
     *         {@linkplain Throwable#getCause() cause} is that exception reconstructed (its
     *         original type when it has a public {@code (String)} constructor, otherwise a
     *         {@code ReconstructedActivityFailure}); or if it was terminated — the message
     *         names who terminated it and the reason, and there is no cause
     * @throws io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException if a
     *         workflow with this ID already exists (see {@link #startAsync})
     * @throws io.b2mash.maestro.core.exception.WorkflowInstantiationException if the
     *         workflow bean cannot be created — the instance row stays
     *         {@code RUNNING} for recovery (see {@link #startAsync})
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
                if (status == WorkflowStatus.FAILED) {
                    throw failed(instance.get().output());
                }
                if (status == WorkflowStatus.TERMINATED) {
                    throw terminated(instance.get().output());
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

    /**
     * Builds the exception for a {@code FAILED} workflow from the recorded
     * {@link WorkflowFailureDetail}: the message names the failing exception
     * and the cause is its reconstruction (null if no detail was recorded).
     */
    private WorkflowExecutionException failed(@Nullable JsonNode output) {
        var id = options.workflowId();
        var detail = readDetail(output, WorkflowFailureDetail.class);
        if (detail == null) {
            return new WorkflowExecutionException(id, "Workflow '%s' failed".formatted(id), null);
        }
        var reconstructed = FailureReconstruction.reconstruct(detail.exceptionType(), detail.message());
        return new WorkflowExecutionException(id,
                "Workflow '%s' failed: %s: %s".formatted(id, detail.exceptionType(), detail.message()),
                reconstructed);
    }

    /**
     * Builds the exception for a {@code TERMINATED} workflow from the recorded
     * {@link WorkflowTerminationDetail}. A termination is not a failure, so
     * there is no cause.
     */
    private WorkflowExecutionException terminated(@Nullable JsonNode output) {
        var id = options.workflowId();
        var detail = readDetail(output, WorkflowTerminationDetail.class);
        if (detail == null) {
            return new WorkflowExecutionException(id, "Workflow '%s' was terminated".formatted(id), null);
        }
        var reason = detail.reason() == null ? "" : ": " + detail.reason();
        return new WorkflowExecutionException(id,
                "Workflow '%s' was terminated by %s%s".formatted(id, detail.terminatedBy(), reason),
                null);
    }

    /**
     * Reads a terminal detail payload, or {@code null} when it is absent or
     * unreadable — the terminal status must still be reported even if the
     * payload cannot be.
     */
    private <D> @Nullable D readDetail(@Nullable JsonNode output, Class<D> type) {
        try {
            return serializer.deserialize(output, type);
        } catch (RuntimeException e) {
            logger.warn("Workflow '{}' ended with an unreadable {} payload; reporting the status without it",
                    options.workflowId(), type.getSimpleName(), e);
            return null;
        }
    }
}
