package io.b2mash.maestro.core.exception;

/**
 * Thrown when an activity method fails after all retry attempts are exhausted.
 *
 * <p>The activity's own exception is <b>never</b> delivered unwrapped to
 * workflow code, and never as the object the activity threw: once the failure
 * is recorded, the cause is <b>rebuilt from the persisted failure record</b>,
 * on the first run exactly as on every replay (Issue 42), so a {@code catch}
 * sees the same exception on both paths by construction.
 *
 * <h2>What the cause is</h2>
 * <ul>
 *   <li><b>An opted-in class</b> (annotated
 *       {@link io.b2mash.maestro.core.annotation.FailureDetails @FailureDetails}):
 *       the class itself, created by the mapper from the recorded message and
 *       properties — when the rebuilt object is of the same class, carries the
 *       same message and writes the same properties again. Otherwise a
 *       {@link ReconstructedActivityFailure}, reported on the first run.</li>
 *   <li><b>Any other class:</b> the class itself, created through its public
 *       {@code (String)} constructor with the recorded message, if it is an
 *       accessible public class with one that does not throw; otherwise
 *       (package-private or private nested class, abstract class, no such
 *       constructor, throwing constructor, class missing from the classpath,
 *       or an engine control-flow signal name) a
 *       {@link ReconstructedActivityFailure} whose
 *       {@link ReconstructedActivityFailure#originalClassName() originalClassName()}
 *       is the thrown type's fully-qualified name. Only the type and the
 *       message are kept: no other field, no cause chain, no stack trace of
 *       the original.</li>
 * </ul>
 * The message is the recorded one, {@code null} included. Workflow code
 * branches with {@link #causedBy(Class)} (which recognises a reconstruction by
 * its original class) and reads recorded properties through
 * {@link #failure()}, which holds them whatever the cause turned out to be.
 */
public final class ActivityExecutionException extends MaestroException {

    private final String workflowId;
    private final String activityName;
    private final RecordedFailure failure;

    /**
     * Wraps a cause whose record is derived from the cause itself
     * ({@link RecordedFailure#of(Throwable)}: no recorded properties).
     *
     * @param workflowId   the workflow ID that owns this activity
     * @param activityName the name of the failed activity method
     * @param cause        the exception the activity call failed with
     */
    public ActivityExecutionException(String workflowId, String activityName, Throwable cause) {
        this(workflowId, activityName, cause, RecordedFailure.of(cause));
    }

    /**
     * Wraps a cause rebuilt from a persisted failure record.
     *
     * <p>The message is {@code "Activity '<activity>' failed in workflow
     * '<workflow>': <recorded message>"}, or the recorded type in place of a
     * {@code null} message.
     *
     * @param workflowId   the workflow ID that owns this activity
     * @param activityName the name of the failed activity method
     * @param cause        the exception rebuilt from {@code failure}
     * @param failure      what was recorded for the failure
     */
    public ActivityExecutionException(String workflowId, String activityName, Throwable cause,
                                      RecordedFailure failure) {
        super("Activity '%s' failed in workflow '%s': %s"
                .formatted(activityName, workflowId,
                        failure.message() != null ? failure.message() : failure.type()),
                cause);
        this.workflowId = workflowId;
        this.activityName = activityName;
        this.failure = failure;
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
     * Returns what was recorded for this failure: the type the activity threw,
     * its message and, for a class annotated
     * {@link io.b2mash.maestro.core.annotation.FailureDetails @FailureDetails},
     * its recorded properties.
     *
     * <p>Read from the persisted record, so it is the same on the first run and
     * on every replay, and it is there even when the cause is a
     * {@link ReconstructedActivityFailure} — the class is missing on this node
     * or could not be rebuilt faithfully:
     * <pre>{@code
     * catch (ActivityExecutionException e) {
     *     if (e.causedBy(PaymentDeclinedException.class)) {
     *         var code = e.failure().details().path("reasonCode").asString();
     *     }
     * }
     * }</pre>
     *
     * @return the recorded failure; never {@code null}
     */
    public RecordedFailure failure() {
        return failure;
    }

    /**
     * Tests whether the activity failed with an exception of the given type.
     *
     * <p>Returns {@code true} when {@code type.isInstance(getCause())}, or when
     * the cause is a {@link ReconstructedActivityFailure} whose
     * {@link ReconstructedActivityFailure#originalClassName() originalClassName()}
     * names {@code type} or a subtype of it. The name is resolved with
     * {@link ExceptionTypeResolver#resolve(String)} (context class loader, then
     * the library loader, never initialising the class); if the class cannot be
     * loaded, only exact name equality counts. This
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
            var original = ExceptionTypeResolver.resolve(name);
            return original != null && type.isAssignableFrom(original);
        }
        return false;
    }
}
