package io.b2mash.maestro.core.context;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
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
 * <p>Three causes can be requested:
 * <ul>
 *   <li>{@link Cause#TERMINATED} — set by {@code WorkflowExecutor.terminateWorkflow}
 *       when the terminating node is the run's owner. The activity retry loop
 *       checks it before <em>every</em> attempt (including the first), and the
 *       retry backoff wait returns as soon as it is set: terminate means stop
 *       now. A non-owning node cannot reach this latch; the owner converges via
 *       its terminal-status guards instead.</li>
 *   <li>{@link Cause#STALE} — another runner owns this workflow's progress
 *       (Issue 18 / C5). Set by every stale-run source before it throws: an
 *       event append that collides with a concurrent runner's event (activity
 *       proxy, {@code sleep()}/side-effect/fork appends, signal and
 *       compensation appends), and a fresh read that finds the row finalised
 *       by another runner (park/wake status writes, the {@code awaitSignal()}
 *       and {@code sleep()} wake rechecks, the saga's reads). It is checked at
 *       every stop point — before every activity attempt (with the terminate
 *       check), on entry to {@code sleep()} and {@code awaitSignal()}, and
 *       immediately before the run's terminal write — and the backoff wait
 *       honours it too. So a run whose author caught and swallowed the
 *       {@code DuplicateEventException} or {@code StaleRunException} still
 *       cannot execute its next side effect, consume a signal, schedule a
 *       timer or record an outcome the winner owns.</li>
 *   <li>{@link Cause#SHUTDOWN} — set by {@code WorkflowExecutor.shutdown()}.
 *       It is deliberately <em>not</em> checked before an attempt (shutdown
 *       lets in-flight work drain), but the retry backoff wait honours it, so a
 *       node going down does not wait out a long backoff; the instance keeps its
 *       durable state and stays recoverable.</li>
 * </ul>
 *
 * <h2>Precedence</h2>
 * <p>The causes are ranked {@code TERMINATED} &gt; {@code STALE} &gt;
 * {@code SHUTDOWN}, and a request replaces the current cause only if it ranks
 * strictly higher:
 * <ul>
 *   <li>{@code TERMINATED} is final: it replaces a prior {@code STALE} or
 *       {@code SHUTDOWN} request, and nothing replaces it.</li>
 *   <li>{@code STALE} replaces a prior {@code SHUTDOWN} — shutdown lets the
 *       next attempt run so in-flight work drains, but a run that is known to
 *       be stale must not execute another side effect, drain or not.</li>
 *   <li>{@code SHUTDOWN} never replaces anything.</li>
 * </ul>
 * A repeated request of the current cause is a no-op that keeps the first
 * one's reason. In every case the weaker request is the one that gives way:
 * a stronger stop is never masked by an earlier, weaker one.
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
 * timed wait on this latch, which returns early when any cause is requested;
 * the caller then raises the matching signal via
 * {@link #throwIfRequested(String)}. The raised types are the existing
 * {@link WorkflowTerminatedException}, {@link StaleRunException} and
 * {@link ExecutorShutdownException} — no new exception type is introduced.
 *
 * <p>The latch is in-memory only and never persisted: the durable record of a
 * terminate is the instance row, the durable record of a stale run is the
 * winner's event, and a shutdown writes nothing.
 *
 * <p><b>Thread safety:</b> thread-safe. Any thread may request a cause or read
 * the state concurrently with the run's own threads; every transition is one
 * atomic compare-and-set, so a reader always sees a cause together with its
 * own reason.
 */
public final class RunCancellation {

    /** Why the run was asked to stop. */
    public enum Cause {
        /** An operator terminated the workflow; the {@code TERMINATED} row is already durable. */
        TERMINATED(3),
        /** This node's executor is shutting down; the workflow stays recoverable. */
        SHUTDOWN(1),
        /**
         * Another runner owns this workflow's progress — one of this run's
         * appends collided with its event, or a fresh read found the row
         * finalised by it. The run must stand down.
         */
        STALE(2);

        /** Precedence: a request replaces the current cause only if its rank is strictly higher. */
        private final int rank;

        Cause(int rank) {
            this.rank = rank;
        }
    }

    /** The current request: its cause and reason are published together by one compare-and-set. */
    private record Request(Cause cause, @Nullable String reason) {}

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<@Nullable Request> request = new AtomicReference<>();

    /** Creates a latch with nothing requested. */
    public RunCancellation() {
    }

    /**
     * Requests that the run stop because the workflow was terminated.
     *
     * <p>Replaces a prior {@link Cause#SHUTDOWN} or {@link Cause#STALE}
     * request; a prior {@link Cause#TERMINATED} request is kept, reason and all.
     *
     * @param reason the operator-supplied termination reason, or {@code null}
     * @return {@code true} iff this call set the cause or replaced a weaker one;
     *         {@code false} if the run was already terminated
     */
    public boolean requestTermination(@Nullable String reason) {
        return requestIfStronger(new Request(Cause.TERMINATED, reason));
    }

    /**
     * Requests that the run stop because this node's executor is shutting down.
     *
     * @return {@code true} iff this call set the cause; {@code false} if any
     *         cause had already been requested (a shutdown never replaces one)
     */
    public boolean requestShutdown() {
        return requestIfStronger(new Request(Cause.SHUTDOWN, null));
    }

    /**
     * Requests that the run stand down because another runner owns the
     * workflow's progress — called when one of this run's appends lost the
     * race on the {@code (workflow_instance_id, sequence_number)} guard.
     *
     * <p>Replaces a prior {@link Cause#SHUTDOWN} request (a shutdown drain must
     * not let a known-stale run start another attempt); a repeated stale
     * request keeps the first detail, and a later {@link #requestTermination}
     * replaces it. Also called when a fresh read finds the row finalised by
     * another runner, so the stand-down is sticky whatever workflow code does
     * with the {@code StaleRunException}.
     *
     * @param detail what made the run stale, e.g. {@code "append collision at sequence 4"}
     * @return {@code true} iff this call set the cause or replaced a shutdown
     *         request; {@code false} if the run was already stale or terminated
     */
    public boolean requestStale(String detail) {
        return requestIfStronger(new Request(Cause.STALE, detail));
    }

    private boolean requestIfStronger(Request requested) {
        // CAS loop: replace the current request only with a strictly stronger
        // one (see the class Javadoc, "Precedence"). The cause and its reason
        // travel in one record, so a reader never sees one without the other
        // and a losing request cannot overwrite the winner's reason.
        while (true) {
            var current = request.get();
            if (current != null && current.cause().rank >= requested.cause().rank) {
                return false;
            }
            if (request.compareAndSet(current, requested)) {
                latch.countDown();
                return true;
            }
        }
    }

    /**
     * Returns whether any cause has been requested.
     *
     * @return {@code true} once a termination, a stale stand-down or a shutdown
     *         was requested
     */
    public boolean isRequested() {
        return request.get() != null;
    }

    /**
     * Returns the requested cause.
     *
     * @return the current cause, or {@code null} if nothing was requested
     */
    public @Nullable Cause cause() {
        var current = request.get();
        return current == null ? null : current.cause();
    }

    /**
     * Returns the reason that accompanies the current cause.
     *
     * @return the operator-supplied reason of a {@link Cause#TERMINATED}
     *         request (or {@code null} if none was given), the detail of a
     *         {@link Cause#STALE} request, or {@code null} for
     *         {@link Cause#SHUTDOWN} or when nothing was requested
     */
    public @Nullable String reason() {
        var current = request.get();
        return current == null ? null : current.reason();
    }

    /**
     * Throws if — and only if — the run's workflow was terminated. Any other
     * cause is ignored here.
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
     * Throws if — and only if — the run was asked to stand down as stale.
     * Any other cause is ignored here.
     *
     * <p>The exception's {@code observedStatus} is {@link WorkflowStatus#RUNNING}:
     * the latch keeps only the detail of what made the run stale, not a
     * status, so the last status this run itself acted under is reported.
     *
     * @param workflowId the workflow ID, for the exception
     * @throws StaleRunException if the cause is {@link Cause#STALE}
     */
    public void throwIfStale(String workflowId) {
        var current = request.get();
        if (current != null && current.cause() == Cause.STALE) {
            throw stale(workflowId, current);
        }
    }

    /**
     * Throws the signal matching the requested cause, if any.
     *
     * @param workflowId the workflow ID, for the exception
     * @throws WorkflowTerminatedException if the cause is {@link Cause#TERMINATED}
     * @throws StaleRunException           if the cause is {@link Cause#STALE}
     * @throws ExecutorShutdownException   if the cause is {@link Cause#SHUTDOWN}
     */
    public void throwIfRequested(String workflowId) {
        var current = request.get();
        if (current == null) {
            return;
        }
        switch (current.cause()) {
            case TERMINATED -> throw new WorkflowTerminatedException(workflowId, current.reason());
            case STALE -> throw stale(workflowId, current);
            case SHUTDOWN -> throw new ExecutorShutdownException(
                    "Executor is shutting down — abandoning the retry backoff of workflow '%s'; the workflow "
                            .formatted(workflowId)
                            + "keeps its durable state and is recoverable");
        }
    }

    private static StaleRunException stale(String workflowId, Request current) {
        var detail = current.reason() != null ? current.reason() : "append collision";
        return new StaleRunException(workflowId, WorkflowStatus.RUNNING, detail);
    }

    /**
     * Waits up to {@code timeout} for any cause to be requested.
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
