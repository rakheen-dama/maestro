package io.b2mash.maestro.core.engine;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Metadata for a registered workflow type.
 *
 * <p>Captures the workflow type name, task queue, the factory that builds the
 * workflow implementation object for each run, and the
 * {@link io.b2mash.maestro.core.annotation.WorkflowMethod}-annotated method
 * that serves as the workflow entry point.
 *
 * <p>Used by {@link WorkflowExecutor} for routing task messages to the
 * correct implementation and for recovering workflows at startup.
 *
 * <p><b>Thread safety:</b> the record itself is immutable. The factory may be
 * invoked concurrently from several threads (concurrent starts, recovery
 * passes, retries) and must therefore be thread-safe.
 *
 * @param workflowType    the workflow type name (from {@code @DurableWorkflow.name()})
 * @param taskQueue       the task queue name
 * @param workflowFactory called exactly once per run — on {@code startWorkflow}, on
 *                        every re-adoption by {@code recoverWorkflows}/{@code resumeWorkflow},
 *                        and on {@code retryWorkflow}; the returned object is held in the
 *                        running-workflow record for the life of that run and is the
 *                        receiver for every {@code @QueryMethod} invocation against it; it
 *                        is never reused across runs
 * @param workflowMethod  the entry-point method annotated with {@code @WorkflowMethod}
 */
public record WorkflowRegistration(
        String workflowType,
        String taskQueue,
        Supplier<Object> workflowFactory,
        Method workflowMethod
) {}
