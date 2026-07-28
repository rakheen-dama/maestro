package io.b2mash.maestro.core.exception;

/**
 * Signals that a workflow's <em>local run</em> was abandoned because this node
 * is shutting down. It is <b>not</b> a workflow failure.
 *
 * <p>When a node shuts down gracefully, workflows parked on
 * {@code awaitSignal()} or {@code sleep()} are unblocked with this exception so
 * their virtual threads can exit promptly. Their durable state is untouched and
 * still valid: the instance stays in {@code WAITING_SIGNAL} or
 * {@code WAITING_TIMER}, no compensation runs, and any node — including this
 * one after a restart — recovers it from the store and carries on.
 *
 * <p>The engine distinguishes this exception from every other throwable
 * escaping a workflow method. Anything else means the workflow genuinely
 * failed, so it is compensated and transitioned to {@code FAILED}; this one
 * means only that the process is stopping.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch and swallow this exception, and do not wrap it in another
 * exception. Doing either tells the engine your workflow failed during a
 * routine deploy — which will run your compensations. Broad
 * {@code catch (RuntimeException)} blocks around {@code awaitSignal()} or
 * {@code sleep()} are the usual way this happens; rethrow it if you must catch
 * broadly.
 */
public final class ExecutorShutdownException extends MaestroException {

    /**
     * Creates a new shutdown signal.
     *
     * @param message descriptive message naming what was abandoned
     */
    public ExecutorShutdownException(String message) {
        super(message);
    }
}
