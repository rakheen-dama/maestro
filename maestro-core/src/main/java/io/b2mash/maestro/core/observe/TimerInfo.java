package io.b2mash.maestro.core.observe;

/**
 * Identity of one durable timer for observation.
 *
 * <p>Immutable and thread-safe.
 *
 * @param workflowId   the business workflow ID (unbounded — never use as a
 *                     metric tag)
 * @param workflowType the workflow type name (code-bounded)
 * @param timerId      the logical timer ID (embeds a sequence number —
 *                     unbounded, never use as a metric tag)
 */
public record TimerInfo(String workflowId, String workflowType, String timerId) {}
