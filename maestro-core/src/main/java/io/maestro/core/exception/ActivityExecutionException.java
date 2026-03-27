package io.maestro.core.exception;

/**
 * Thrown when an activity method fails after all retry attempts are exhausted.
 *
 * <p>The original exception is available via {@link #getCause()}.
 */
public final class ActivityExecutionException extends MaestroException {

    private final String workflowId;
    private final String activityName;

    /**
     * @param workflowId   the workflow ID that owns this activity
     * @param activityName the name of the failed activity method
     * @param cause        the exception thrown by the activity
     */
    public ActivityExecutionException(String workflowId, String activityName, Throwable cause) {
        super("Activity '%s' failed in workflow '%s': %s"
                .formatted(activityName, workflowId,
                        cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName()),
                cause);
        this.workflowId = workflowId;
        this.activityName = activityName;
    }

    /** Returns the workflow ID that owns this activity. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the name of the failed activity method. */
    public String activityName() {
        return activityName;
    }
}
