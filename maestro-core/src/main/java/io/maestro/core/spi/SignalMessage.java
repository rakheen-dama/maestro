package io.maestro.core.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Message delivering a named signal to a workflow.
 *
 * <p>Signal messages are routed by {@link #workflowId()} — the business
 * workflow ID used for correlation.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param workflowId  business workflow ID to deliver the signal to
 * @param signalName  the signal name (e.g., {@code "payment.result"})
 * @param payload     signal data, may be {@code null}
 * @see WorkflowMessaging#publishSignal(String, SignalMessage)
 */
public record SignalMessage(
        String workflowId,
        String signalName,
        @Nullable JsonNode payload
) {}
