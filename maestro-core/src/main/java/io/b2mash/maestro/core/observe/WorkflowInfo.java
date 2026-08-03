package io.b2mash.maestro.core.observe;

/**
 * Identity of a workflow for observation. Never carry payloads here.
 *
 * <p>Immutable and thread-safe.
 *
 * @param workflowId   the business workflow ID (unbounded — never use as a
 *                     metric tag)
 * @param workflowType the workflow type name (code-bounded — safe as a tag)
 * @param serviceName  the owning service name (code-bounded)
 */
public record WorkflowInfo(String workflowId, String workflowType, String serviceName) {}
