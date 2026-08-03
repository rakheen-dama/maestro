package io.b2mash.maestro.core.exception;

/**
 * Sealed base for the engine's <em>control-flow signals</em> — throwables that
 * mean "this workflow's local run must stop now" and are <b>never</b> workflow
 * failures.
 *
 * <p>Three things extend it, and nothing else can:
 * <ul>
 *   <li>{@link ExecutorShutdownException} — this node is stopping while the
 *       workflow was parked.</li>
 *   <li>{@link WorkflowTerminatedException} — an operator terminated the
 *       workflow; the {@code TERMINATED} row is already durable.</li>
 *   <li>{@link UnknownWorkflowHistoryException} — this node cannot interpret
 *       the workflow's persisted history (a newer node wrote it).</li>
 * </ul>
 *
 * <h2>Why this extends {@code Error}, not {@code MaestroException}</h2>
 * <p>Every other exception in this package extends {@link MaestroException} (an
 * unchecked {@link RuntimeException}) so workflow authors can catch the
 * engine's failure types uniformly. These three deliberately break that
 * convention, and for one shared reason: a workflow author's ordinary
 * {@code try { workflow.awaitSignal(...) } catch (Exception e) { ... }} around
 * a park point or an activity call is common, reasonable-looking code — and if
 * these were {@code RuntimeException}s, that block would silently swallow them
 * and reinstate the exact bug each one exists to prevent (a routine deploy
 * recorded as a workflow failure with compensations run for work that never
 * failed; a terminated workflow carrying on executing activities an operator
 * asked you to stop; an older node in a mixed-version fleet marking a workflow
 * FAILED because it could not read a newer node's event). Extending
 * {@code Error} puts them outside {@code catch (Exception)}'s reach — and
 * outside most {@code catch (Throwable)} "log and continue" blocks. This
 * mirrors Temporal's approach to the same problem.
 *
 * <h2>Engine maintainers</h2>
 * <p>This type exists so a broad-catch site needs <em>one</em> check rather
 * than an enumeration that drifts every time a fourth signal is added.
 * Anywhere a {@code catch (Throwable)} collects outcomes (parallel branches,
 * compensation branches, the retry executor), check
 * {@code instanceof MaestroControlFlowError} and rethrow <em>before</em>
 * anything records a failure. Anywhere reflection is involved
 * ({@code Method.invoke}, {@link java.util.concurrent.CompletableFuture}
 * completion), unwrap the cause and check {@code instanceof Error} <em>before</em>
 * {@code instanceof Exception}/{@code RuntimeException} — otherwise the unwrap
 * silently re-wraps a control-flow signal into a catchable type.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch, swallow, or wrap any subtype. If you must catch broadly
 * (for example {@code catch (Throwable t)} to log and continue), check for
 * {@code MaestroControlFlowError} first and rethrow it.
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share.
 */
public sealed abstract class MaestroControlFlowError extends Error
        permits ExecutorShutdownException, WorkflowTerminatedException,
                UnknownWorkflowHistoryException {

    /**
     * Creates a control-flow signal.
     *
     * @param message descriptive message naming what is being stood down and why
     */
    protected MaestroControlFlowError(String message) {
        super(message);
    }
}
