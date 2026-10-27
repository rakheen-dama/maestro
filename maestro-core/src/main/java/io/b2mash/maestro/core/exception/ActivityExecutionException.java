package io.b2mash.maestro.core.exception;

/**
 * Thrown when an activity method fails after all retry attempts are exhausted.
 *
 * <p>The original exception is available via {@link #getCause()}. The
 * activity's own exception is <b>never</b> delivered unwrapped to workflow
 * code, live or on replay: on replay the cause may be a reconstruction (see
 * below), so a uniform wrapper plus {@link #causedBy(Class)} is the only
 * contract that behaves identically on both paths.
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

    /**
     * Tests whether the activity failed with an exception of the given type.
     *
     * <p>Returns {@code true} when {@code type.isInstance(getCause())}, or when
     * the cause is a {@link ReconstructedActivityFailure} whose
     * {@link ReconstructedActivityFailure#originalClassName() originalClassName()}
     * names {@code type} or a subtype of it. The name is resolved with
     * {@code Class.forName(name, false, Thread.currentThread().getContextClassLoader())};
     * if the class cannot be loaded, only exact name equality counts. This
     * makes the check behave identically on the live path and on replay.
     *
     * <p>Canonical pattern:
     * <pre>{@code
     * try { provider.charge(...); }
     * catch (ActivityExecutionException e) {
     *     if (e.causedBy(PaymentDeclinedException.class)) { ... } else { throw e; }
     * }
     * }</pre>
     *
     * @param type the exception type to test for
     * @return {@code true} if the cause is (or was, before replay reconstruction) of that type
     */
    public boolean causedBy(Class<? extends Throwable> type) {
        var cause = getCause();
        if (type.isInstance(cause)) {
            return true;
        }
        if (cause instanceof ReconstructedActivityFailure rebuilt) {
            var name = rebuilt.originalClassName();
            if (type.getName().equals(name)) {
                return true;
            }
            try {
                var original = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
                return type.isAssignableFrom(original);
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
        return false;
    }
}
