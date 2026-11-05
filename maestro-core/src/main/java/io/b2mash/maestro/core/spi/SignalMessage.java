package io.b2mash.maestro.core.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Message delivering a named signal to a workflow.
 *
 * <p>Signal messages are routed by {@link #workflowId()} — the business
 * workflow ID used for correlation.
 *
 * <h2>Message identity</h2>
 * <p>{@link #messageId()} is the producer's identity for this <em>logical</em>
 * signal: every retry or republish of the same signal carries the same id, and
 * the engine persists at most one signal row per
 * {@code (workflowId, signalName, messageId)} — a redelivered or republished
 * duplicate is suppressed rather than becoming a second row that a later
 * same-name await would consume. A message without an id is not deduplicated
 * (each delivery is a new signal). An id longer than 512 characters is treated
 * as absent. The field is additive on the wire: a consumer built before it
 * existed ignores it (Jackson 3 does not fail on unknown properties by
 * default).
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param workflowId  business workflow ID to deliver the signal to
 * @param signalName  the signal name (e.g., {@code "payment.result"})
 * @param payload     signal data, may be {@code null}
 * @param messageId   the producer-assigned identity of this logical signal,
 *                    or {@code null} for "no identity — do not deduplicate"
 * @see WorkflowMessaging#publishSignal(String, SignalMessage)
 */
public record SignalMessage(
        String workflowId,
        String signalName,
        @Nullable JsonNode payload,
        @Nullable String messageId
) {

    /**
     * Creates a signal message with no message identity — every delivery of
     * it is a distinct signal.
     *
     * @param workflowId business workflow ID to deliver the signal to
     * @param signalName the signal name
     * @param payload    signal data, may be {@code null}
     */
    public SignalMessage(String workflowId, String signalName, @Nullable JsonNode payload) {
        this(workflowId, signalName, payload, null);
    }

    /**
     * Returns a copy of this message carrying {@code messageId}.
     *
     * @param messageId the identity to carry, or {@code null} to clear it
     * @return a new message with the same workflow, name and payload
     */
    public SignalMessage withMessageId(@Nullable String messageId) {
        return new SignalMessage(workflowId, signalName, payload, messageId);
    }
}
