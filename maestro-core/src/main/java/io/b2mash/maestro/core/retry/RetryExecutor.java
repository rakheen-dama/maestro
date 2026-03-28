package io.b2mash.maestro.core.retry;

import io.b2mash.maestro.core.exception.ActivityExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Executes a callable with retry logic according to a {@link RetryPolicy}.
 *
 * <p>Uses exponential backoff between attempts. On virtual threads, the
 * backoff sleep yields the carrier thread — no platform thread is blocked.
 *
 * <p><b>Thread safety:</b> This class is stateless and thread-safe.
 * Multiple virtual threads may call {@link #executeWithRetry} concurrently.
 */
public final class RetryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(RetryExecutor.class);

    /**
     * Executes the given task with retry logic.
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
     * @return the task result on success
     * @throws ActivityExecutionException if all retries are exhausted
     */
    public <T> T executeWithRetry(
            RetryPolicy policy,
            Callable<T> task,
            String activityName,
            String workflowId
    ) throws ActivityExecutionException {
        Throwable lastException = null;

        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            try {
                return task.call();
            } catch (Throwable e) {
                var unwrapped = unwrap(e);

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
                    sleep(backoff, activityName, workflowId);
                } else {
                    logger.error("Activity '{}' exhausted all {} attempts in workflow '{}'",
                            activityName, policy.maxAttempts(), workflowId, unwrapped);
                }
            }
        }

        throw new ActivityExecutionException(workflowId, activityName, lastException);
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
     * Sleeps for the given duration. On virtual threads, this yields the
     * carrier thread rather than blocking it.
     */
    private static void sleep(Duration duration, String activityName, String workflowId) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityExecutionException(
                    workflowId, activityName,
                    new RuntimeException("Retry sleep interrupted", e));
        }
    }
}
