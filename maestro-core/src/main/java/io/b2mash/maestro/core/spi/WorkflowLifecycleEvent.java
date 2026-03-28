package io.b2mash.maestro.core.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Lifecycle event published to the admin dashboard for observability.
 *
 * <p>These events are consumed by the Maestro Admin application to provide
 * real-time visibility into workflow state across all services.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param workflowInstanceId  the workflow instance UUID
 * @param workflowId          business workflow ID
 * @param workflowType        workflow type name
 * @param serviceName         name of the service that published this event
 * @param taskQueue           task queue the workflow is assigned to
 * @param eventType           the type of lifecycle event
 * @param stepName            name of the activity or step, {@code null} for workflow-level events
 * @param detail              additional event details, may be {@code null}
 * @param timestamp           when the event occurred
 * @see LifecycleEventType
 * @see WorkflowMessaging#publishLifecycleEvent(WorkflowLifecycleEvent)
 */
public record WorkflowLifecycleEvent(
        UUID workflowInstanceId,
        String workflowId,
        String workflowType,
        String serviceName,
        String taskQueue,
        LifecycleEventType eventType,
        @Nullable String stepName,
        @Nullable JsonNode detail,
        Instant timestamp
) {}
