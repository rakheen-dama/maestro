package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;

/**
 * Identity of one signal for observation.
 *
 * <p>Immutable and thread-safe.
 *
 * @param workflowId   the business workflow ID (unbounded — never use as a
 *                     metric tag)
 * @param workflowType the workflow type name, or {@code null} when the signal
 *                     was persisted before the instance existed (pre-delivery)
 * @param signalName   the signal name (code-bounded — signal names are string
 *                     literals in workflow/listener code)
 * @param traceContext W3C {@code traceparent} captured when the signal was
 *                     persisted from a transport consumer, or {@code null} (§4)
 */
public record SignalInfo(String workflowId, @Nullable String workflowType,
                         String signalName, @Nullable String traceContext) {}
