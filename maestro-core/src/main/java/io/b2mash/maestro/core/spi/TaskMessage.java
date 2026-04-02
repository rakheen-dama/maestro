package io.b2mash.maestro.core.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Message dispatched to a task queue to start or resume a workflow execution.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param workflowInstanceId  the workflow instance UUID
 * @param workflowId          business workflow ID (e.g., {@code "order-abc"})
 * @param workflowType        workflow type name (e.g., {@code "order-fulfilment"})
 * @param runId               current run ID
 * @param serviceName         name of the owning service
 * @param input               workflow input payload, may be {@code null}
 * @see WorkflowMessaging#publishTask(String, TaskMessage)
 */
public record TaskMessage(
        UUID workflowInstanceId,
        String workflowId,
        String workflowType,
        UUID runId,
        String serviceName,
        @Nullable JsonNode input
) {}
