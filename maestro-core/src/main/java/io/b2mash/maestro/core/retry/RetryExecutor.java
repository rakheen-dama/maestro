package io.b2mash.maestro.core.retry;

import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.engine.NodeErrors;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Executes a callable with retry logic according to a {@link RetryPolicy}.
 *
 * <p>Uses exponential backoff between attempts. On virtual threads, the
 * backoff wait yields the carrier thread — no platform thread is blocked.
 *
 * <p><b>Cancellation.</b> The run's {@link RunCancellation} is honoured at
 * three points. A termination, then a stale-run stand-down, is checked before
 * <em>every</em> attempt (including the first) — terminate means stop now, and
 * a run another runner owns must not execute the next side effect — and again
 * as soon as an attempt fails, so a failed attempt after either request is
 * neither retried nor surfaced as an {@link ActivityExecutionException} (which
 * the proxy would persist as {@code ACTIVITY_FAILED}). The backoff wait returns
 * as soon as any cause is requested, raising the matching signal. A shutdown is
 * deliberately not checked before an attempt, so in-flight work drains. The
 * attempt that is already executing is never interrupted by cancellation;
 * {@code startToCloseTimeout} (enforced inside the task by the activity proxy,
 * not here) does interrupt an attempt.
 *
 * <p><b>Node errors.</b> A node-level error ({@link NodeErrors#isNodeError}:
 * a {@code LinkageError}, or a {@code VirtualMachineError} other than
 * {@code StackOverflowError}) is rethrown at once, unwrapped and unretried: it
 * says this node is broken, not that the activity failed, and must reach
 * {@code WorkflowExecutor}'s node-error arm intact rather than be recorded as
 * an activity failure and compensated.
 *
 * <p><b>Thread safety:</b> This class is stateless and thread-safe; the
 * {@link RunCancellation} is caller-owned (and itself thread-safe).
 * Multiple virtual threads may call {@link #executeWithRetry} concurrently.
 */
public final class RetryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(RetryExecutor.class);

    /**
     * Executes the given task with retry logic and no cancellation source.
     *
     * <p>Equivalent to
     * {@link #executeWithRetry(RetryPolicy, Callable, String, String, RunCancellation)}
     * with a fresh {@link RunCancellation} that is never requested.
     *
     * @param <T>          the return type of the task
     * @param policy       the retry policy to apply
     * @param task         the callable to execute
     * @param activityName the activity name (for logging and exception context)
     * @param workflowId   the workflow ID (for logging and exception context)
     * @return the task result on success
     * @throws ActivityExecutionException if all retries are exhausted
     */
    public <T> T executeWithRetry(
            RetryPolicy policy,
            Callable<T> task,
            String activityName,
            String workflowId
    ) throws ActivityExecutionException {
        return executeWithRetry(policy, task, activityName, workflowId, new RunCancellation());
    }

    /**
     * Executes the given task with retry logic, honouring the run's
     * {@link RunCancellation}.
     *
     * <p>If the task throws a retryable exception and retries remain, the
     * executor waits (with exponential backoff) and tries again. When retries
     * are exhausted, an {@link ActivityExecutionException} is thrown wrapping
     * the last failure.
     *
     * @param <T>          the return type of the task
     * @param policy       the retry policy to apply
     * @param task         the callable to execute
     * @param activityName the activity name (for logging and exception context)
     * @param workflowId   the workflow ID (for logging and exception context)
     * @param cancellation the run's cancellation latch
     * @return the task result on success
     * @throws ActivityExecutionException  if all retries are exhausted
     * @throws WorkflowTerminatedException if the run's workflow was terminated
     *                                     before an attempt, while a failing attempt
     *                                     ran, or during a backoff wait
     * @throws StaleRunException           if the run was asked to stand down as
     *                                     stale, at the same points
     * @throws ExecutorShutdownException   if this node began shutting down during a
     *                                     backoff wait
     */
    public <T> T executeWithRetry(
            RetryPolicy policy,
            Callable<T> task,
            String activityName,
            String workflowId,
            RunCancellation cancellation
    ) throws ActivityExecutionException {
        Throwable lastException = null;

        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            // Terminate means stop now: no attempt starts — not even the first —
            // once an operator terminated the workflow; and a run that already
            // lost an append race to another runner must not execute the next
            // side effect, even if the author swallowed the duplicate (R29).
            // Shutdown is deliberately not checked here; in-flight work drains.
            throwIfStopRequested(cancellation, workflowId);
            try {
                return task.call();
            } catch (MaestroControlFlowError e) {
                // None of the engine's control-flow signals is a retryable
                // failure — this node is shutting down, an operator terminated
                // the workflow, or a replay read met history this build cannot
                // interpret. Retrying (with a backoff sleep) delays the unwind
                // for no reason, and re-reading an unreadable row will not make
                // it readable.
                //
                // Wrapping is the dangerous half: ActivityExecutionException is
                // an ordinary Exception, so a wrapped signal reaches
                // executeWorkflow's catch (Exception) and the workflow is
                // recorded FAILED *with compensation* — reinstating, one layer
                // down, the exact bug each signal exists to prevent.
                //
                // One catch for all three: compensation actions run through the
                // activity proxy and therefore through this executor, so a
                // nested replay read here can raise a stand-down. Enumerating
                // the types individually is what let that case slip through
                // when it was added.
                throw e;
            } catch (Throwable e) {
                var unwrapped = unwrap(e);
                // A node-level error (R28) is this node's problem, not the
                // activity's: rethrown untouched — never retried, never wrapped
                // into a catchable ActivityExecutionException (which the proxy
                // would persist as ACTIVITY_FAILED and the workflow would
                // compensate) — so WorkflowExecutor abandons the run as
                // NODE_ERROR. It sits beside the control-flow arm above, ahead
                // of everything else here, for the same reason.
                if (NodeErrors.isNodeError(unwrapped)) {
                    throw (Error) unwrapped;
                }
                // The attempt failed after a terminate (or a stale stand-down)
                // was requested while it ran (R30): retrying is ruled out by the
                // pre-attempt check anyway, and surfacing the failure would
                // persist ACTIVITY_FAILED — and run the workflow's failure path
                // — on a run that must stop.
                throwIfStopRequested(cancellation, workflowId);

                if (!isRetryable(policy, unwrapped)) {
                    logger.error("Activity '{}' failed with non-retryable exception in workflow '{}'",
                            activityName, workflowId, unwrapped);
                    throw new ActivityExecutionException(workflowId, activityName, unwrapped);
                }

                lastException = unwrapped;

                if (attempt < policy.maxAttempts() - 1) {
                    var backoff = calculateBackoff(policy, attempt);
                    logger.warn("Activity '{}' attempt {}/{} failed in workflow '{}', retrying in {}",
                            activityName, attempt + 1, policy.maxAttempts(),
                            workflowId, backoff, unwrapped);
                    awaitBackoff(backoff, cancellation, activityName, workflowId);
                } else {
                    logger.error("Activity '{}' exhausted all {} attempts in workflow '{}'",
                            activityName, policy.maxAttempts(), workflowId, unwrapped);
                }
            }
        }

        throw new ActivityExecutionException(workflowId, activityName, lastException);
    }

    /**
     * Throws if the run was terminated, then if it was asked to stand down as
     * stale — terminate first, as the stronger request. A shutdown is not
     * checked.
     */
    private static void throwIfStopRequested(RunCancellation cancellation, String workflowId) {
        cancellation.throwIfTerminated(workflowId);
        cancellation.throwIfStale(workflowId);
    }

    /**
     * Unwraps {@link InvocationTargetException} to get the actual cause.
     * This is necessary because {@code Method.invoke()} wraps checked
     * exceptions thrown by the target method.
     */
    private static Throwable unwrap(Throwable e) {
        if (e instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return e;
    }

    /**
     * Determines if an exception is retryable according to the policy.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>If {@code nonRetryableExceptions} matches → not retryable (takes precedence)</li>
     *   <li>If {@code retryableExceptions} is non-empty and matches → retryable</li>
     *   <li>If {@code retryableExceptions} is non-empty and does not match → not retryable</li>
     *   <li>If both lists are empty → retryable (retry everything)</li>
     * </ol>
     */
    private static boolean isRetryable(RetryPolicy policy, Throwable exception) {
        // Non-retryable takes precedence
        for (var nonRetryable : policy.nonRetryableExceptions()) {
            if (nonRetryable.isInstance(exception)) {
                return false;
            }
        }

        // If retryable list is specified, exception must match
        if (!policy.retryableExceptions().isEmpty()) {
            for (var retryable : policy.retryableExceptions()) {
                if (retryable.isInstance(exception)) {
                    return true;
                }
            }
            return false;
        }

        // Both lists empty → retry everything
        return true;
    }

    /**
     * Calculates backoff duration: {@code min(initial × multiplier^attempt, max)}.
     */
    private static Duration calculateBackoff(RetryPolicy policy, int attempt) {
        var delayMs = (long) (policy.initialInterval().toMillis()
                * Math.pow(policy.backoffMultiplier(), attempt));
        var cappedMs = Math.min(delayMs, policy.maxInterval().toMillis());
        return Duration.ofMillis(Math.max(cappedMs, 0));
    }

    /**
     * Waits out a retry backoff, returning early — by throwing the matching
     * signal — as soon as a termination, a stale stand-down or a shutdown is
     * requested on the run. On virtual threads the wait yields the carrier
     * thread rather than blocking it.
     *
     * <p>The engine never interrupts a workflow thread, so an
     * {@link InterruptedException} here means a foreign interrupter: the flag is
     * restored, a cancellation that raced the interrupt still wins, and only if
     * nothing was requested is the interrupt reported as an activity failure,
     * exactly as before.
     */
    private static void awaitBackoff(
            Duration duration, RunCancellation cancellation, String activityName, String workflowId
    ) {
        try {
            if (cancellation.awaitRequested(duration)) {
                cancellation.throwIfRequested(workflowId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancellation.throwIfRequested(workflowId);
            throw new ActivityExecutionException(
                    workflowId, activityName,
                    new RuntimeException("Retry sleep interrupted", e));
        }
    }
}
