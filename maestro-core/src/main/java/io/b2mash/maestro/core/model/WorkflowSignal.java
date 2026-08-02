package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A named, typed message delivered into a running workflow from an external source.
 *
 * <p>Maestro's self-recovery property guarantees that signals are never lost.
 * Three scenarios are handled:
 * <ol>
 *   <li><b>Signal before await:</b> Persisted with {@code consumed = false}.
 *       Consumed when the workflow reaches {@code awaitSignal()}.</li>
 *   <li><b>Signal before workflow starts:</b> Persisted with
 *       {@link #workflowInstanceId()} as {@code null}. Adopted when the
 *       workflow instance is created via
 *       {@link io.b2mash.maestro.core.spi.WorkflowStore#adoptOrphanedSignals}.</li>
 *   <li><b>Signal while service is down:</b> Persisted by the Kafka consumer
 *       on another instance. Found during recovery.</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param id                  primary key
 * @param workflowInstanceId  owning workflow instance UUID, {@code null} for pre-delivered signals
 * @param workflowId          business workflow ID used for correlation
 * @param signalName          the signal name (e.g., {@code "payment.result"})
 * @param payload             signal data, may be {@code null}
 * @param consumed            whether the signal has been consumed by the workflow
 * @param receivedAt          when the signal was persisted
 * @param traceContext        raw W3C {@code traceparent} captured from the
 *                            transport that delivered this signal, or
 *                            {@code null}. <b>Opaque metadata:</b> no store or
 *                            engine logic parses or branches on its contents,
 *                            and its absence degrades to an untraced (fresh
 *                            root) resume, never an error.
 * @see io.b2mash.maestro.core.spi.WorkflowStore#saveSignal(WorkflowSignal)
 * @see io.b2mash.maestro.core.observe.TraceContextHolder
 */
public record WorkflowSignal(
        UUID id,
        @Nullable UUID workflowInstanceId,
        String workflowId,
        String signalName,
        @Nullable JsonNode payload,
        boolean consumed,
        Instant receivedAt,
        @Nullable String traceContext
) {

    /**
     * Creates a signal with no trace context — the shape of every signal that
     * did not arrive over a traced transport.
     *
     * <p><b>Never use this to copy an existing signal.</b> A copy site (status
     * transition, orphan adoption) must call the canonical constructor and
     * carry {@link #traceContext()} forward; this overload would silently drop
     * it.
     *
     * @param id                 primary key
     * @param workflowInstanceId owning instance UUID, {@code null} pre-delivery
     * @param workflowId         business workflow ID
     * @param signalName         the signal name
     * @param payload            signal data, may be {@code null}
     * @param consumed           whether the signal has been consumed
     * @param receivedAt         when the signal was persisted
     */
    public WorkflowSignal(
            UUID id,
            @Nullable UUID workflowInstanceId,
            String workflowId,
            String signalName,
            @Nullable JsonNode payload,
            boolean consumed,
            Instant receivedAt
    ) {
        this(id, workflowInstanceId, workflowId, signalName, payload, consumed, receivedAt, null);
    }
}
