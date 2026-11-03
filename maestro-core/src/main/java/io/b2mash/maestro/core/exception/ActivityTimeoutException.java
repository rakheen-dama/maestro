package io.b2mash.maestro.core.exception;

import java.time.Duration;

/**
 * Thrown when a single live attempt of an activity exceeds its
 * {@code startToCloseTimeout}.
 *
 * <h2>What the timeout bounds</h2>
 * <p>One <em>attempt</em>, measured on the wall clock from the moment the
 * activity method is invoked to the moment it returns or throws. It does not
 * span the retry schedule: every attempt gets the full timeout afresh, and the
 * backoff waits between attempts are not counted against it.
 *
 * <h2>What happens to the attempt</h2>
 * <p>Each attempt runs on its own virtual thread. When the timeout elapses the
 * engine cancels the attempt with {@code Future.cancel(true)}, which
 * <b>interrupts</b> its thread, and raises this exception on the workflow
 * thread without waiting for the attempt to stop. Interruption is cooperative:
 * an activity that ignores it keeps running in the background, but whatever it
 * eventually returns or throws is <b>discarded</b> — it is never persisted and
 * never seen by the workflow. Activities must therefore be idempotent (as they
 * already must be for crash recovery).
 *
 * <h2>Retryability</h2>
 * <p>A timeout is not special-cased: it is an ordinary activity failure, and
 * the activity's {@link io.b2mash.maestro.core.retry.RetryPolicy RetryPolicy}
 * decides whether it is retried exactly as for any other exception. With the
 * default policy (both exception lists empty) a timed-out attempt is retried;
 * listing {@code ActivityTimeoutException} in {@code nonRetryableExceptions}
 * makes the first timeout terminal. When retries are exhausted the activity
 * fails with an {@link ActivityExecutionException} whose cause is this
 * exception; use
 * {@link ActivityExecutionException#causedBy(Class)
 * causedBy(ActivityTimeoutException.class)} to detect it — that check also
 * holds on replay, where the recorded failure is rebuilt as a
 * {@link ReconstructedActivityFailure} naming this class.
 *
 * <p><b>Thread safety:</b> immutable.
 */
public final class ActivityTimeoutException extends MaestroException {

    private final String workflowId;
    private final String activityName;
    private final Duration timeout;

    /**
     * @param workflowId   the workflow ID that owns the activity
     * @param activityName the step name of the activity whose attempt timed out
     * @param timeout      the {@code startToCloseTimeout} the attempt exceeded
     */
    public ActivityTimeoutException(String workflowId, String activityName, Duration timeout) {
        super("Activity '%s' in workflow '%s' exceeded its startToCloseTimeout of %s"
                .formatted(activityName, workflowId, timeout));
        this.workflowId = workflowId;
        this.activityName = activityName;
        this.timeout = timeout;
    }

    /** Returns the workflow ID that owns the activity. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the step name of the activity whose attempt timed out. */
    public String activityName() {
        return activityName;
    }

    /** Returns the {@code startToCloseTimeout} the attempt exceeded. */
    public Duration timeout() {
        return timeout;
    }
}
