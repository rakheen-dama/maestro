package io.b2mash.maestro.core.observe;

/**
 * Identity of one activity step for observation.
 *
 * <p>Immutable and thread-safe.
 *
 * @param workflowId     the business workflow ID (unbounded — never use as a
 *                       metric tag)
 * @param workflowType   the workflow type name (code-bounded)
 * @param activityName   the step name, {@code group.method} — code-bounded
 * @param sequenceNumber the memoization sequence this step occupies
 */
public record ActivityInfo(String workflowId, String workflowType,
                           String activityName, int sequenceNumber) {}
