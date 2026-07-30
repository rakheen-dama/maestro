package io.b2mash.maestro.core.exception;

/**
 * Thrown when an admin command — a signal named {@code $maestro:*}, published
 * by the admin dashboard's Retry/Terminate buttons — cannot be routed to an
 * engine action.
 *
 * <p>Two cases raise this exception, both deterministic and both meaning "this
 * exact message will never succeed as-is":
 * <ul>
 *   <li>The signal name after the {@code $maestro:} prefix is not a known
 *       command (e.g. {@code $maestro:bogus}) — a typo, a newer dashboard
 *       talking to an older service, or a stale deploy.</li>
 *   <li>{@code $maestro:retry} names a workflow whose type has no
 *       {@code @DurableWorkflow} registration on the receiving node — the
 *       wrong service received the command, or the workflow class was removed
 *       without a corresponding deploy of the admin dashboard.</li>
 * </ul>
 *
 * <p>Per the admin-command dispatch table (Issue 15 design §3.3, §7), this is
 * deliberately an exception rather than a logged no-op: unlike an invalid
 * workflow state or an unknown workflow ID — both of which are legitimate,
 * expected outcomes that must acknowledge so the signal topic keeps moving —
 * an unroutable command is an operational anomaly that must not be silently
 * dropped. The transport does not acknowledge the message, so it is
 * redelivered under the existing bounded backoff policy and, once the attempt
 * budget is exhausted, dead-lettered — visible to an operator, never lost, and
 * (because the budget is bounded) never hot-looping.
 *
 * @see io.b2mash.maestro.core.spi.WorkflowMessaging#subscribeSignals
 */
public final class AdminCommandException extends MaestroException {

    /**
     * @param message descriptive error message
     */
    public AdminCommandException(String message) {
        super(message);
    }

    /**
     * @param message descriptive error message
     * @param cause   the underlying cause
     */
    public AdminCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
