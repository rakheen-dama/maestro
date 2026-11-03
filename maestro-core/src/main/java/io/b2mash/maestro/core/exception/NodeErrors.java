package io.b2mash.maestro.core.exception;

/**
 * The engine's one definition of a <em>node-level</em> error: a throwable that
 * says this node is broken, not that the workflow (or the activity) failed.
 *
 * <p>A node error is any {@link LinkageError} (a bad deploy:
 * {@code NoClassDefFoundError}, {@code ExceptionInInitializerError}, ...) and
 * any {@link VirtualMachineError} except {@link StackOverflowError}
 * ({@code OutOfMemoryError}, {@code InternalError}, ...). Recording one as a
 * workflow or activity failure would compensate real work that never failed;
 * a rollback or a healthy node heals it. So the engine never records it:
 * {@code WorkflowExecutor} abandons the local run as
 * {@code AbandonReason.NODE_ERROR} (loud, non-terminal, instance left
 * recoverable), and the activity {@code RetryExecutor} rethrows it at once —
 * no retry, no {@code ActivityExecutionException} wrapper, no
 * {@code ACTIVITY_FAILED} — so it reaches that executor arm intact.
 *
 * <p>{@code StackOverflowError} is deliberately <em>not</em> a node error:
 * unbounded recursion is a property of the code and recurs identically on
 * every node, so re-adopting it forever would heal nothing. It, and every
 * other {@code Error} ({@code AssertionError}, ...), stays an ordinary failure
 * (spec C8). The engine's control-flow signals
 * ({@code MaestroControlFlowError}) are neither — they have their own arms.
 *
 * <p>The two forks rank it the same way: {@code parallel()} and parallel saga
 * compensation rethrow a node error from any branch raw, after a control-flow
 * signal and ahead of a stale-run signal or a plain failure, so it reaches
 * the executor's node-error arm instead of an earlier branch's failure being
 * recorded and compensated.
 *
 * <p>It lives in {@code core.exception}, beside the throwable types it
 * classifies, so the retry and engine packages can both use it without
 * depending on each other.
 *
 * <p>Shared so the call sites cannot drift: before it existed, a node
 * error thrown <em>inside</em> an activity was wrapped and retried by the
 * retry executor and ended the workflow {@code FAILED} with compensation,
 * although the same error thrown from the workflow body abandoned the run.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
public final class NodeErrors {

    private NodeErrors() {
    }

    /**
     * Whether {@code t} is a node-level error rather than a workflow or
     * activity failure.
     *
     * @param t the throwable to classify
     * @return {@code true} iff {@code t} is a {@link LinkageError}, or a
     *         {@link VirtualMachineError} other than {@link StackOverflowError}
     */
    public static boolean isNodeError(Throwable t) {
        if (t instanceof StackOverflowError) {
            return false;
        }
        return t instanceof LinkageError || t instanceof VirtualMachineError;
    }
}
