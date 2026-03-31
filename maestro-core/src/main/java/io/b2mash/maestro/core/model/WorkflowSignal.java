package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.databind.JsonNode;

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
 * @see io.b2mash.maestro.core.spi.WorkflowStore#saveSignal(WorkflowSignal)
 */
public record WorkflowSignal(
        UUID id,
        @Nullable UUID workflowInstanceId,
        String workflowId,
        String signalName,
        @Nullable JsonNode payload,
        boolean consumed,
        Instant receivedAt
) {}
