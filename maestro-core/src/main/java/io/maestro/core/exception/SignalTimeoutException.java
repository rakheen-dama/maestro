package io.maestro.core.exception;

import java.time.Duration;

/**
 * Thrown when a workflow's signal await exceeds the configured timeout.
 */
public final class SignalTimeoutException extends MaestroException {

    private final String workflowId;
    private final String signalName;
    private final Duration timeout;

    /**
     * @param workflowId the workflow ID that was awaiting the signal
     * @param signalName the signal name that was not received in time
     * @param timeout    the timeout duration that elapsed
     */
    public SignalTimeoutException(String workflowId, String signalName, Duration timeout) {
        super("Signal '%s' timed out after %s for workflow '%s'"
                .formatted(signalName, timeout, workflowId));
        this.workflowId = workflowId;
        this.signalName = signalName;
        this.timeout = timeout;
    }

    /** Returns the workflow ID that was awaiting the signal. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the signal name that was not received in time. */
    public String signalName() {
        return signalName;
    }

    /** Returns the timeout duration that elapsed. */
    public Duration timeout() {
        return timeout;
    }
}
