package io.b2mash.maestro.core.context;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The in-memory "stop this run" latch for one local workflow run.
 *
 * <p>One instance exists per run: the {@code WorkflowExecutor} creates it when
 * it launches the workflow's virtual thread, hands it to the run's root
 * {@link WorkflowContext}, and keeps it on its own record of the running
 * workflow. Every branch context of that run — {@code parallel()} branches,
 * parallel-compensation branches, the sequential-compensation per-entry
 * context — shares the parent's instance (see
 * {@link WorkflowContext#bounded(WorkflowContext, int, int, int, io.b2mash.maestro.core.engine.WorkflowOperations)}),
 * so a single request reaches every thread of the run.
 *
 * <p>Two causes can be requested, and the first request wins:
 * <ul>
 *   <li>{@link Cause#TERMINATED} — set by {@code WorkflowExecutor.terminateWorkflow}
 *       when the terminating node is the run's owner. The activity retry loop
 *       checks it before <em>every</em> attempt (including the first), and the
 *       retry backoff wait returns as soon as it is set: terminate means stop
 *       now. A non-owning node cannot reach this latch; the owner converges via
 *       its terminal-status guards instead.</li>
 *   <li>{@link Cause#SHUTDOWN} — set by {@code WorkflowExecutor.shutdown()}.
 *       It is deliberately <em>not</em> checked before an attempt (shutdown
 *       lets in-flight work drain), but the retry backoff wait honours it, so a
 *       node going down does not wait out a long backoff; the instance keeps its
 *       durable state and stays recoverable.</li>
 * </ul>
 *
 * <h2>Why a latch, not {@code Thread.interrupt()}</h2>
 * <p>This mirrors how {@code ParkingLot} unblocks a parked workflow: shutdown
 * completes every park's future exceptionally and sets a sticky
 * {@code shuttingDown} flag; terminate does the same per workflow with a sticky
 * {@code terminatedWorkflows} entry. Neither interrupts the workflow thread —
 * an interrupt would land wherever the thread happens to be (inside user
 * activity code, a JDBC call, a lock renewal) and be swallowed or misreported
 * there. A retry backoff is the one remaining wait that is not a park, so it
 * gets the same treatment: the wait is {@link #awaitRequested(Duration)}, a
 * timed wait on this latch, which returns early when either cause is requested;
 * the caller then raises the matching control-flow {@code Error} via
 * {@link #throwIfRequested(String)}. The raised types are the existing
 * {@link WorkflowTerminatedException} and {@link ExecutorShutdownException} —
 * no new control-flow type is introduced.
 *
 * <p>The latch is in-memory only and never persisted: the durable record of a
 * terminate is the instance row, and a shutdown writes nothing.
 *
 * <p><b>Thread safety:</b> thread-safe. Any thread may request a cause or read
 * the state concurrently with the run's own threads; the first request wins
 * atomically and later requests are no-ops.
 */
public final class RunCancellation {

    /** Why the run was asked to stop. */
    public enum Cause {
        /** An operator terminated the workflow; the {@code TERMINATED} row is already durable. */
        TERMINATED,
        /** This node's executor is shutting down; the workflow stays recoverable. */
        SHUTDOWN
    }

    /** The winning request: its cause and reason are published together by one compare-and-set. */
    private record Request(Cause cause, @Nullable String reason) {}

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<@Nullable Request> request = new AtomicReference<>();

    /** Creates a latch with nothing requested. */
    public RunCancellation() {
    }

    /**
     * Requests that the run stop because the workflow was terminated.
     *
     * @param reason the operator-supplied termination reason, or {@code null}
     * @return {@code true} iff this call set the cause; {@code false} if a cause
     *         (either one) had already been requested
     */
    public boolean requestTermination(@Nullable String reason) {
        return request(Cause.TERMINATED, reason);
    }

    /**
     * Requests that the run stop because this node's executor is shutting down.
     *
     * @return {@code true} iff this call set the cause; {@code false} if a cause
     *         (either one) had already been requested
     */
    public boolean requestShutdown() {
        return request(Cause.SHUTDOWN, null);
    }

    private boolean request(Cause requested, @Nullable String requestReason) {
        // First request wins: compare-and-set null -> request. The cause and
        // its reason travel in one record, so a reader never sees one without
        // the other and a losing request cannot overwrite the winner's reason.
        if (!request.compareAndSet(null, new Request(requested, requestReason))) {
            return false;
        }
        latch.countDown();
        return true;
    }

    /**
     * Returns whether either cause has been requested.
     *
     * @return {@code true} once a termination or a shutdown was requested
     */
    public boolean isRequested() {
        return request.get() != null;
    }

    /**
     * Returns the requested cause.
     *
     * @return the cause that won, or {@code null} if nothing was requested
     */
    public @Nullable Cause cause() {
        var current = request.get();
        return current == null ? null : current.cause();
    }

    /**
     * Returns the termination reason.
     *
     * @return the operator-supplied reason of a {@link Cause#TERMINATED} request,
     *         or {@code null} if none was given, the cause is
     *         {@link Cause#SHUTDOWN}, or nothing was requested
     */
    public @Nullable String reason() {
        var current = request.get();
        return current == null ? null : current.reason();
    }

    /**
     * Throws if — and only if — the run's workflow was terminated. A shutdown
     * request is ignored here, so in-flight work can drain.
     *
     * @param workflowId the workflow ID, for the exception
     * @throws WorkflowTerminatedException if the cause is {@link Cause#TERMINATED}
     */
    public void throwIfTerminated(String workflowId) {
        var current = request.get();
        if (current != null && current.cause() == Cause.TERMINATED) {
            throw new WorkflowTerminatedException(workflowId, current.reason());
        }
    }

    /**
     * Throws the control-flow signal matching the requested cause, if any.
     *
     * @param workflowId the workflow ID, for the exception
     * @throws WorkflowTerminatedException if the cause is {@link Cause#TERMINATED}
     * @throws ExecutorShutdownException   if the cause is {@link Cause#SHUTDOWN}
     */
    public void throwIfRequested(String workflowId) {
        var current = request.get();
        if (current == null) {
            return;
        }
        switch (current.cause()) {
            case TERMINATED -> throw new WorkflowTerminatedException(workflowId, current.reason());
            case SHUTDOWN -> throw new ExecutorShutdownException(
                    "Executor is shutting down — abandoning the retry backoff of workflow '%s'; the workflow "
                            .formatted(workflowId)
                            + "keeps its durable state and is recoverable");
        }
    }

    /**
     * Waits up to {@code timeout} for either cause to be requested.
     *
     * <p>Returns immediately if a cause was already requested. Used as the
     * activity retry backoff wait, in place of {@code Thread.sleep}.
     *
     * @param timeout the longest to wait
     * @return {@code true} iff a cause was requested before the timeout elapsed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitRequested(Duration timeout) throws InterruptedException {
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
