package io.b2mash.maestro.core.exception;

/**
 * Thrown when an activity method fails after all retry attempts are exhausted.
 *
 * <p>The original exception is available via {@link #getCause()}.
 *
 * <h2>Matching the cause</h2>
 * <p>On the live path {@code getCause()} is the exception the activity threw.
 * On replay it is the same type <b>only if</b> that type has a public
 * {@code (String)} constructor; otherwise it is a
 * {@link ReconstructedActivityFailure} whose
 * {@link ReconstructedActivityFailure#originalClassName() originalClassName()}
 * is the thrown type's fully-qualified name (the same holds for a type missing
 * from the classpath or an engine control-flow signal name). Workflow code must
 * therefore catch {@code ActivityExecutionException} and branch on
 * {@code getCause()}'s class <em>or</em> on {@code originalClassName()}; it
 * must never rely on {@code catch (MyDomainException)} alone.
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
