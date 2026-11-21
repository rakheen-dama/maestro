package io.b2mash.maestro.core.exception;

/**
 * Thrown by a workflow start whose input cannot be read back into the
 * workflow method's declared parameter type (Issue 44).
 *
 * <p>Every run of a workflow — the first one, each recovery and a Retry —
 * receives its input as read back from its persisted JSON with the
 * parameter's generic type. An input that serializes but cannot be read back
 * (an interface or abstract class with no type information, a class with no
 * creator Jackson can use, a custom serializer without a matching
 * deserializer) would leave a run that stands down at sequence 0 on every
 * attempt. The start reads the serialized input back first and refuses such
 * an input with this exception instead: <b>nothing is persisted</b> and no
 * lock is held, so the same workflow id can be started again with a readable
 * input. The same refusal covers an absent input ({@code null}, or a JSON
 * {@code null}) for a primitive parameter, which no run could be invoked
 * with, and an input the store keeps in a form it cannot parse back (the JDBC
 * store then rolls its insert back).
 *
 * <p>The cause is the {@link SerializationException} the read-back raised,
 * or an {@link IllegalArgumentException} for the primitive case.
 *
 * <p>Only an unreadable input is refused. An input that reads back as
 * another class than the one passed — a subtype under a declared supertype,
 * a record under {@code Object} or a type variable, which reads as Jackson's
 * natural form — is accepted, as an activity result declared that way is:
 * every run, the first included, receives the read-back value.
 *
 */
public final class UnreadableWorkflowInputException extends MaestroException {

    private final String workflowId;
    private final String workflowType;
    private final String parameterType;

    /**
     * @param workflowId    the workflow id whose start was refused
     * @param workflowType  the workflow type
     * @param parameterType the workflow method's generic parameter type, as
     *                      {@link java.lang.reflect.Type#getTypeName()} renders it
     * @param cause         the read-back failure
     */
    public UnreadableWorkflowInputException(String workflowId, String workflowType, String parameterType,
                                            Throwable cause) {
        super(("Workflow '%s' of type '%s' was not started: its input cannot be read back as the workflow "
                + "method's parameter type %s, so no run could ever receive it. Nothing was persisted. Declare "
                + "a parameter type Jackson can deserialize (a concrete class or record, or type information "
                + "for a polymorphic one), or pass an input of that type.")
                .formatted(workflowId, workflowType, parameterType), cause);
        this.workflowId = workflowId;
        this.workflowType = workflowType;
        this.parameterType = parameterType;
    }

    /** Returns the workflow id whose start was refused. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the workflow type. */
    public String workflowType() {
        return workflowType;
    }

    /** Returns the workflow method's generic parameter type name. */
    public String parameterType() {
        return parameterType;
    }
}
