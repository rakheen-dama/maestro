package io.maestro.admin.projection;

import io.maestro.core.spi.LifecycleEventType;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

/**
 * Projects workflow lifecycle events into the admin database tables.
 *
 * <p>This component receives events from the {@link io.maestro.admin.kafka.AdminEventConsumer}
 * and upserts workflow instance, event, service, and metrics data into the admin-owned
 * Postgres tables for dashboard queries.
 *
 * <p>All four operations (upsert service, upsert workflow, append event, update metrics)
 * run within a single transaction to maintain consistency.
 *
 * <p><b>Thread safety:</b> This class is thread-safe. All state is held in the injected
 * {@link JdbcTemplate} and {@link ObjectMapper}, both of which are thread-safe.
 *
 * @see io.maestro.admin.kafka.AdminEventConsumer
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
     * @param objectMapper Jackson 3 ObjectMapper for serializing detail JsonNode to string
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
        upsertWorkflow(event);
        appendEvent(event);

        if (isWorkflowStatusEvent(event.eventType())) {
            updateMetrics(event);
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
     * Updates the metrics counters for workflow-level status change events.
     *
     * <p>Increments the counter for the new status and decrements the counter
     * for the previous status (assumed to be {@code RUNNING} for all transitions
     * except {@code WORKFLOW_STARTED}).
     */
    private void updateMetrics(WorkflowLifecycleEvent event) {
        var eventType = event.eventType();
        var serviceName = event.serviceName();
        var newStatus = mapStatus(eventType);

        logger.debug("Updating metrics: service={}, eventType={}, newStatus={}",
                serviceName, eventType, newStatus);

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

        // Decrement the previous status counter (RUNNING) for all transitions except WORKFLOW_STARTED
        if (eventType != LifecycleEventType.WORKFLOW_STARTED) {
            jdbc.update("""
                    UPDATE admin_metrics
                    SET workflow_count = GREATEST(workflow_count - 1, 0), last_updated_at = NOW()
                    WHERE service_name = ? AND status = ?
                    """,
                    serviceName,
                    "RUNNING");
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
