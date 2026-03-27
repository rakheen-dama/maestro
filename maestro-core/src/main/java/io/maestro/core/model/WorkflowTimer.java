package io.maestro.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable timer that resumes a parked workflow after a specified time.
 *
 * <p>When a workflow calls {@code workflow.sleep(duration)}, a timer is
 * persisted with a {@link #fireAt()} timestamp. A leader-elected timer
 * poller scans for due timers and resumes the corresponding workflow.
 *
 * <p>Timers survive JVM restarts — the worker does not need to remain alive.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param id                  primary key
 * @param workflowInstanceId  owning workflow instance UUID
 * @param workflowId          business workflow ID (e.g., {@code "order-abc"}) for timer fire routing
 * @param timerId             logical timer identifier within the workflow
 * @param fireAt              when the timer should fire
 * @param status              current timer lifecycle state
 * @param createdAt           when the timer was created
 * @see TimerStatus
 * @see io.maestro.core.spi.WorkflowStore#getDueTimers(Instant, int)
 */
public record WorkflowTimer(
        UUID id,
        UUID workflowInstanceId,
        String workflowId,
        String timerId,
        Instant fireAt,
        TimerStatus status,
        Instant createdAt
) {}
