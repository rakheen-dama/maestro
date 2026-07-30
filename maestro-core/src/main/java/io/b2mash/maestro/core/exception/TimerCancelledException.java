package io.b2mash.maestro.core.exception;

/**
 * Thrown by {@code sleep(Duration)} when the durable timer it was waiting on
 * (or, on replay, previously waited on) is cancelled rather than fired.
 *
 * <p>Cancellation is arbitrated by the timer row's compare-and-set, exactly
 * as firing is: {@code WorkflowExecutor.cancelTimer} transitions the row
 * {@code PENDING → CANCELLED} and unparks the workflow, which reads the row
 * back and throws this exception at the {@code sleep()} call site — on the
 * live path and, identically, on every subsequent replay, because the
 * outcome is memoized as a {@code TIMER_CANCELLED} event at the same
 * sequence a {@code TIMER_FIRED} event would otherwise occupy.
 *
 * <p>Workflow code may catch this exception and continue, e.g. to take a
 * fallback branch when an operator skips a cooling-off period. Left
 * uncaught, it propagates out of the workflow method like any other
 * exception: saga compensation runs (if any is registered) and the instance
 * ends {@code FAILED}.
 *
 * @see io.b2mash.maestro.core.engine.WorkflowOperations#sleep(java.time.Duration)
 */
public final class TimerCancelledException extends MaestroException {

    private final String workflowId;
    private final String timerId;

    /**
     * @param workflowId the workflow ID whose sleep was cancelled
     * @param timerId    the logical timer ID that was cancelled
     */
    public TimerCancelledException(String workflowId, String timerId) {
        super("Timer '%s' was cancelled for workflow '%s'".formatted(timerId, workflowId));
        this.workflowId = workflowId;
        this.timerId = timerId;
    }

    /** Returns the workflow ID whose sleep was cancelled. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the logical timer ID that was cancelled. */
    public String timerId() {
        return timerId;
    }
}
