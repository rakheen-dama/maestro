package io.b2mash.maestro.core.engine;

import java.lang.reflect.Method;

/**
 * Metadata for a registered workflow type.
 *
 * <p>Captures the workflow type name, task queue, implementation instance,
 * and the {@link io.b2mash.maestro.core.annotation.WorkflowMethod}-annotated method
 * that serves as the workflow entry point.
 *
 * <p>Used by {@link WorkflowExecutor} for routing task messages to the
 * correct implementation and for recovering workflows at startup.
 *
 * @param workflowType the workflow type name (from {@code @DurableWorkflow.name()})
 * @param taskQueue    the task queue name
 * @param workflowImpl the workflow implementation instance
 * @param workflowMethod the entry-point method annotated with {@code @WorkflowMethod}
 */
public record WorkflowRegistration(
        String workflowType,
        String taskQueue,
        Object workflowImpl,
        Method workflowMethod
) {}
