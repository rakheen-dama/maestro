package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;

/**
 * Signals that a workflow's <em>local run</em> was abandoned because the
 * workflow has been terminated by an admin action. It is <b>not</b> a workflow
 * failure, and it is not something the workflow can recover from.
 *
 * <p>{@code WorkflowExecutor.terminateWorkflow} writes {@code TERMINATED} to
 * the instance row first — the durable record is already final by the time this
 * exception is thrown. The exception exists only to make the still-running
 * virtual thread stop promptly and unwind:
 * <ul>
 *   <li>If the terminating node is also the owner, every park belonging to that
 *       workflow is abandoned with this exception, and any park registering just
 *       afterwards throws it immediately.</li>
 *   <li>If the owner is a different node, its thread hits this exception the
 *       next time it touches the engine — at its next park, at its next status
 *       write, or within one wake-recheck interval for a parked
 *       {@code awaitSignal()}.</li>
 * </ul>
 *
 * <p>Nothing is written when it propagates: the {@code TERMINATED} row is
 * already durable, <b>no compensation runs</b> (terminate marks and stops; it
 * does not unwind a saga), and the instance lock is released as the thread
 * unwinds.
 *
 * <h2>Why this extends {@code Error}, not {@code MaestroException}</h2>
 * <p>For exactly the reason {@link ExecutorShutdownException} does. Both are
 * engine control-flow signals delivered at a park point, and a workflow
 * author's ordinary
 * {@code try { workflow.awaitSignal(...) } catch (Exception e) { ... }} is
 * common, reasonable-looking code. If either signal were a
 * {@link RuntimeException}, such a block would swallow it and the workflow
 * would carry on — here, continuing to execute activities and write events for
 * a workflow an operator has explicitly terminated. Making it an {@code Error}
 * means ordinary {@code catch (Exception)} — and most {@code catch (Throwable)}
 * "log and continue" blocks — cannot intercept it.
 *
 * <p>See {@code CLAUDE.md} § Coding Standards for the project-wide note on the
 * two engine control-flow signals that deliberately do not extend
 * {@code MaestroException}.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch and swallow this exception, and do not wrap it in another
 * exception. Doing either keeps a terminated workflow's thread alive, executing
 * side effects the operator asked you to stop. If you must catch broadly (for
 * example {@code catch (Throwable t)} to log and continue), check for this type
 * first and rethrow it.
 */
public final class WorkflowTerminatedException extends Error {

    private final String workflowId;
    private final @Nullable String reason;

    /**
     * @param workflowId the workflow whose local run is being abandoned
     * @param reason     the operator-supplied termination reason, or {@code null}
     */
    public WorkflowTerminatedException(String workflowId, @Nullable String reason) {
        super("Workflow '%s' was terminated%s — abandoning its local run; the TERMINATED "
                .formatted(workflowId, reason != null ? " (" + reason + ")" : "")
                + "instance row is already durable and no compensation runs");
        this.workflowId = workflowId;
        this.reason = reason;
    }

    /** Returns the workflow ID whose local run was abandoned. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the operator-supplied termination reason, or {@code null}. */
    public @Nullable String reason() {
        return reason;
    }
}
