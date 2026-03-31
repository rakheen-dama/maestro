package io.b2mash.maestro.admin.projection;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Projects workflow lifecycle events into the admin database tables.
 *
 * <p>This component receives events from the {@link io.b2mash.maestro.admin.kafka.AdminEventConsumer}
 * and upserts workflow instance, event, service, and metrics data into the admin-owned
 * Postgres tables for dashboard queries.
 *
 * <p>All four operations (upsert service, upsert workflow, append event, update metrics)
 * run within a single transaction to maintain consistency.
 *
 * <p><b>Thread safety:</b> This class is thread-safe. All state is held in the injected
 * {@link JdbcTemplate} and {@link ObjectMapper}, both of which are thread-safe.
 *
 * @see io.b2mash.maestro.admin.kafka.AdminEventConsumer
 * @see WorkflowLifecycleEvent
 */
@Component
public class EventProjector {

    private static final Logger logger = LoggerFactory.getLogger(EventProjector.class);

    private static final Set<LifecycleEventType> WORKFLOW_STATUS_EVENTS = Set.of(
            LifecycleEventType.WORKFLOW_STARTED,
            LifecycleEventType.WORKFLOW_COMPLETED,
            LifecycleEventType.WORKFLOW_FAILED,
            LifecycleEventType.WORKFLOW_TERMINATED,
            LifecycleEventType.COMPENSATION_STARTED
    );

    private static final Set<LifecycleEventType> TERMINAL_EVENTS = Set.of(
            LifecycleEventType.WORKFLOW_COMPLETED,
            LifecycleEventType.WORKFLOW_FAILED,
            LifecycleEventType.WORKFLOW_TERMINATED
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new event projector.
     *
     * @param jdbc         Spring JDBC template for admin database access
     * @param objectMapper Jackson ObjectMapper for serializing detail JsonNode to string
     */
    public EventProjector(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Projects a single lifecycle event into the admin database.
     *
     * <p>Performs four operations in a single transaction:
     * <ol>
     *   <li>Upsert the service record</li>
     *   <li>Upsert the workflow record</li>
     *   <li>Append the event to the event log</li>
     *   <li>Update metrics counters (only for workflow-level status changes)</li>
     * </ol>
     *
     * @param event the workflow lifecycle event to project
     */
    @Transactional
    public void project(WorkflowLifecycleEvent event) {
        logger.debug("Projecting event: type={}, workflowId={}, instanceId={}",
                event.eventType(), event.workflowId(), event.workflowInstanceId());

        upsertService(event.serviceName(), event.timestamp());

        // Capture the current status BEFORE the upsert overwrites it, so metrics
        // can decrement the correct previous status rather than hard-coding RUNNING.
        var oldStatus = isWorkflowStatusEvent(event.eventType())
                ? queryCurrentStatus(event.workflowInstanceId())
                : null;

        upsertWorkflow(event);
        appendEvent(event);

        if (isWorkflowStatusEvent(event.eventType())) {
            updateMetrics(event.serviceName(), oldStatus, mapStatus(event.eventType()));
        }

        logger.debug("Projected event successfully: type={}, workflowId={}",
                event.eventType(), event.workflowId());
    }

    /**
     * Upserts the service record, updating {@code last_seen_at} on conflict.
     */
    private void upsertService(String serviceName, Instant timestamp) {
        logger.debug("Upserting service: name={}", serviceName);

        jdbc.update("""
                INSERT INTO admin_service (name, first_seen_at, last_seen_at)
                VALUES (?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                """,
                serviceName,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp));
    }

    /**
     * Upserts the workflow record based on the event type.
     *
     * <p>The upsert strategy varies by event category:
     * <ul>
     *   <li>{@code WORKFLOW_STARTED} — inserts the initial row or updates status on conflict</li>
     *   <li>Terminal events — sets {@code completed_at} and the terminal status</li>
     *   <li>All other events — updates step name, timestamp, and event count without changing status</li>
     * </ul>
     */
    private void upsertWorkflow(WorkflowLifecycleEvent event) {
        logger.debug("Upserting workflow: instanceId={}, eventType={}",
                event.workflowInstanceId(), event.eventType());

        var eventType = event.eventType();
        var ts = Timestamp.from(event.timestamp());

        if (eventType == LifecycleEventType.WORKFLOW_STARTED) {
            jdbc.update("""
                    INSERT INTO admin_workflow (workflow_instance_id, workflow_id, workflow_type,
                        service_name, task_queue, status, last_step_name, started_at, updated_at, event_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (workflow_instance_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        last_step_name = COALESCE(EXCLUDED.last_step_name, admin_workflow.last_step_name),
                        updated_at = EXCLUDED.updated_at,
                        event_count = admin_workflow.event_count + 1
                    """,
                    event.workflowInstanceId(),
                    event.workflowId(),
                    event.workflowType(),
                    event.serviceName(),
                    event.taskQueue(),
                    mapStatus(eventType),
                    event.stepName(),
                    ts,
                    ts);
        } else if (TERMINAL_EVENTS.contains(eventType)) {
            jdbc.update("""
                    INSERT INTO admin_workflow (workflow_instance_id, workflow_id, workflow_type,
                        service_name, task_queue, status, last_step_name, started_at, completed_at, updated_at, event_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (workflow_instance_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        completed_at = EXCLUDED.completed_at,
                        last_step_name = COALESCE(EXCLUDED.last_step_name, admin_workflow.last_step_name),
                        updated_at = EXCLUDED.updated_at,
                        event_count = admin_workflow.event_count + 1
                    """,
                    event.workflowInstanceId(),
                    event.workflowId(),
                    event.workflowType(),
                    event.serviceName(),
                    event.taskQueue(),
                    mapStatus(eventType),
                    event.stepName(),
                    ts,
                    ts,
                    ts);
        } else if (eventType == LifecycleEventType.COMPENSATION_STARTED) {
            // COMPENSATION_STARTED changes status but is not terminal (no completed_at)
            jdbc.update("""
                    INSERT INTO admin_workflow (workflow_instance_id, workflow_id, workflow_type,
                        service_name, task_queue, status, last_step_name, started_at, updated_at, event_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (workflow_instance_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        last_step_name = COALESCE(EXCLUDED.last_step_name, admin_workflow.last_step_name),
                        updated_at = EXCLUDED.updated_at,
                        event_count = admin_workflow.event_count + 1
                    """,
                    event.workflowInstanceId(),
                    event.workflowId(),
                    event.workflowType(),
                    event.serviceName(),
                    event.taskQueue(),
                    mapStatus(eventType),
                    event.stepName(),
                    ts,
                    ts);
        } else {
            // Non-workflow-level events: update step name, timestamp, event count — don't change status
            jdbc.update("""
                    INSERT INTO admin_workflow (workflow_instance_id, workflow_id, workflow_type,
                        service_name, task_queue, status, last_step_name, started_at, updated_at, event_count)
                    VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, 1)
                    ON CONFLICT (workflow_instance_id) DO UPDATE SET
                        last_step_name = COALESCE(EXCLUDED.last_step_name, admin_workflow.last_step_name),
                        updated_at = EXCLUDED.updated_at,
                        event_count = admin_workflow.event_count + 1
                    """,
                    event.workflowInstanceId(),
                    event.workflowId(),
                    event.workflowType(),
                    event.serviceName(),
                    event.taskQueue(),
                    event.stepName(),
                    ts,
                    ts);
        }
    }

    /**
     * Appends the event to the {@code admin_event} log table.
     *
     * <p><b>Idempotency note:</b> This is an INSERT without deduplication. Under Kafka
     * redelivery (at-least-once semantics), duplicate timeline entries may be inserted and
     * the {@code event_count} in {@code admin_workflow} may overcount. The workflow status
     * and metrics upserts are already idempotent (ON CONFLICT DO UPDATE). The admin dashboard
     * is for monitoring purposes, so approximate event counts are acceptable. A full fix would
     * require either a natural unique key for events or a processed-event tracking table.
     */
    private void appendEvent(WorkflowLifecycleEvent event) {
        logger.debug("Appending event: type={}, workflowId={}",
                event.eventType(), event.workflowId());

        String detailJson = serializeDetail(event);

        jdbc.update("""
                INSERT INTO admin_event (workflow_instance_id, workflow_id, event_type, step_name, detail, event_timestamp)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """,
                event.workflowInstanceId(),
                event.workflowId(),
                event.eventType().name(),
                event.stepName(),
                detailJson,
                Timestamp.from(event.timestamp()));
    }

    /**
     * Queries the current workflow status from admin_workflow before an upsert overwrites it.
     *
     * @param workflowInstanceId the workflow instance UUID
     * @return the current status string, or {@code null} if the workflow has not been projected yet
     */
    @Nullable
    private String queryCurrentStatus(UUID workflowInstanceId) {
        List<String> results = jdbc.queryForList(
                "SELECT status FROM admin_workflow WHERE workflow_instance_id = ?",
                String.class, workflowInstanceId);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Updates the metrics counters for workflow-level status change events.
     *
     * <p>Increments the counter for the new status and decrements the counter
     * for the actual previous status (queried before the upsert).
     *
     * @param serviceName the service name for the metrics row
     * @param oldStatus   the previous workflow status, or {@code null} if this is a new workflow
     * @param newStatus   the new workflow status to increment
     */
    private void updateMetrics(String serviceName, @Nullable String oldStatus, @Nullable String newStatus) {
        if (newStatus == null) {
            return;
        }

        logger.debug("Updating metrics: service={}, oldStatus={}, newStatus={}",
                serviceName, oldStatus, newStatus);

        // Increment the new status counter
        jdbc.update("""
                INSERT INTO admin_metrics (service_name, status, workflow_count, last_updated_at)
                VALUES (?, ?, 1, NOW())
                ON CONFLICT (service_name, status) DO UPDATE SET
                    workflow_count = admin_metrics.workflow_count + 1,
                    last_updated_at = NOW()
                """,
                serviceName,
                newStatus);

        // Decrement the old status counter (if it existed and is different from the new status)
        if (oldStatus != null && !oldStatus.equals(newStatus)) {
            jdbc.update("""
                    UPDATE admin_metrics
                    SET workflow_count = GREATEST(workflow_count - 1, 0), last_updated_at = NOW()
                    WHERE service_name = ? AND status = ?
                    """,
                    serviceName,
                    oldStatus);
        }
    }

    /**
     * Returns {@code true} if the event type represents a workflow-level status change.
     */
    private boolean isWorkflowStatusEvent(LifecycleEventType eventType) {
        return WORKFLOW_STATUS_EVENTS.contains(eventType);
    }

    /**
     * Maps a lifecycle event type to the admin workflow status string.
     *
     * <p><b>Known limitation:</b> WAITING_SIGNAL and WAITING_TIMER workflow states cannot be
     * projected because no corresponding lifecycle event types exist in {@link LifecycleEventType}.
     * When a workflow calls {@code awaitSignal()} or {@code workflow.sleep()}, it transitions to
     * a waiting state internally, but no lifecycle event is published. Projecting these states
     * would require adding new lifecycle event types (e.g., WORKFLOW_WAITING_SIGNAL,
     * WORKFLOW_WAITING_TIMER) to maestro-core.
     *
     * @param eventType the lifecycle event type
     * @return the status string, or {@code null} for non-workflow-level events
     */
    @Nullable
    private static String mapStatus(LifecycleEventType eventType) {
        return switch (eventType) {
            case WORKFLOW_STARTED -> "RUNNING";
            case WORKFLOW_COMPLETED -> "COMPLETED";
            case WORKFLOW_FAILED -> "FAILED";
            case WORKFLOW_TERMINATED -> "TERMINATED";
            case COMPENSATION_STARTED -> "COMPENSATING";
            default -> null;
        };
    }

    /**
     * Serializes the event detail JsonNode to a JSON string, or returns {@code null}
     * if the detail is absent.
     */
    @Nullable
    private String serializeDetail(WorkflowLifecycleEvent event) {
        if (event.detail() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(event.detail());
        } catch (Exception e) {
            logger.warn("Failed to serialize event detail for workflowId={}: {}",
                    event.workflowId(), e.getMessage(), e);
            return null;
        }
    }
}
