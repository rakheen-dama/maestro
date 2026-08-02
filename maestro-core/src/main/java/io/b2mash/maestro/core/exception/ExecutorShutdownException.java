package io.b2mash.maestro.core.exception;

/**
 * Signals that a workflow's <em>local run</em> was abandoned because this node
 * is shutting down. It is <b>not</b> a workflow failure.
 *
 * <p>When a node shuts down gracefully, workflows parked on
 * {@code awaitSignal()} or {@code sleep()} are unblocked with this exception so
 * their virtual threads can exit promptly. Their durable state is untouched and
 * still valid: the instance stays in {@code WAITING_SIGNAL} or
 * {@code WAITING_TIMER} (or {@code COMPENSATING} if it was interrupted mid-saga),
 * no compensation step is recorded as failed, and any node — including this
 * one after a restart — recovers it from the store and carries on.
 *
 * <p>The engine distinguishes this exception from every other throwable
 * escaping a workflow method. Anything else means the workflow genuinely
 * failed, so it is compensated and transitioned to {@code FAILED}; this one
 * means only that the process is stopping.
 *
 * <h2>Why this extends {@code Error}, not {@code MaestroException}</h2>
 * <p>Every other exception in this package extends {@link MaestroException}
 * (an unchecked {@link RuntimeException}) so workflow authors can catch the
 * engine's failure types uniformly. This one deliberately breaks that
 * convention: it extends {@link MaestroControlFlowError} — the sealed base for
 * the engine's control-flow signals, itself an {@link Error} — instead.
 *
 * <p>The reason is that a workflow author's ordinary
 * {@code try { workflow.awaitSignal(...) } catch (Exception e) { ... }} around
 * a park point is common and reasonable-looking code — and if this exception
 * were a {@code RuntimeException}, that block would silently swallow it,
 * reinstating the exact bug this exception exists to prevent: a routine
 * deploy recorded as a workflow failure, running compensations for work that
 * never actually failed. Making it an {@code Error} means ordinary
 * {@code catch (Exception)} — and even most {@code catch (Throwable)} blocks
 * written to "log and continue" — cannot intercept it; it is a control-flow
 * signal from the runtime, not a condition workflow code is expected to
 * handle. This mirrors Temporal's approach to the same problem.
 *
 * <p>See {@code CLAUDE.md} § Coding Standards for the project-wide note on
 * this exception to the "all exceptions extend {@code MaestroException}"
 * rule.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch and swallow this exception, and do not wrap it in another
 * exception. Doing either tells the engine your workflow failed during a
 * routine deploy — which will run your compensations. If you must catch
 * broadly (for example {@code catch (Throwable t)} to log and continue),
 * check for this type first and rethrow it — or, better, check for
 * {@link MaestroControlFlowError}, which covers this signal and its siblings
 * in one test.
 *
 * @see MaestroControlFlowError
 */
public final class ExecutorShutdownException extends MaestroControlFlowError {

    /**
     * Creates a new shutdown signal.
     *
     * @param message descriptive message naming what was abandoned
     */
    public ExecutorShutdownException(String message) {
        super(message);
    }
}
