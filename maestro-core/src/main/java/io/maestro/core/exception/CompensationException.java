package io.maestro.core.exception;

import java.util.List;

/**
 * Thrown when one or more compensation activities fail during saga unwind.
 *
 * <p>This exception records which compensations failed so that operators
 * can investigate and manually reconcile. The workflow still transitions
 * to {@link io.maestro.core.model.WorkflowStatus#FAILED} — partial
 * compensation is logged and visible in the event timeline.
 */
public final class CompensationException extends MaestroException {

    private final String workflowId;
    private final List<String> failedCompensations;

    /**
     * Creates a new compensation exception.
     *
     * @param workflowId          the workflow business ID
     * @param failedCompensations list of step names that failed to compensate
     */
    public CompensationException(String workflowId, List<String> failedCompensations) {
        super("Compensation partially failed for workflow '%s': %s"
                .formatted(workflowId, failedCompensations));
        this.workflowId = java.util.Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.failedCompensations = List.copyOf(
                java.util.Objects.requireNonNull(failedCompensations, "failedCompensations must not be null"));
    }

    /** Returns the workflow business ID. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the step names of compensations that failed. */
    public List<String> failedCompensations() {
        return failedCompensations;
    }
}
