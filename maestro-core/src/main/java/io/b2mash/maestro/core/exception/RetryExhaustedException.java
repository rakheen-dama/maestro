package io.b2mash.maestro.core.exception;

/**
 * Thrown when a {@code retryUntil} loop exhausts all attempts or exceeds
 * its maximum duration without the predicate being satisfied.
 */
public final class RetryExhaustedException extends MaestroException {

    private final String workflowId;
    private final int attempts;

    /**
     * @param workflowId the workflow ID where retries were exhausted
     * @param attempts   the number of attempts made before giving up
     */
    public RetryExhaustedException(String workflowId, int attempts) {
        super("retryUntil exhausted after %d attempt(s) for workflow '%s'"
                .formatted(attempts, workflowId));
        this.workflowId = workflowId;
        this.attempts = attempts;
    }

    /** Returns the workflow ID where retries were exhausted. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the number of attempts made. */
    public int attempts() {
        return attempts;
    }
}
