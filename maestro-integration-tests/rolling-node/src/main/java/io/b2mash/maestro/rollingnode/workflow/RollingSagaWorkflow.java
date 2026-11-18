package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;

/**
 * Reserve and charge (each with a compensation), park on signal
 * {@value #SIGNAL}, then ship on {@code "ship"} — which always fails, so the
 * saga compensates: refund, then release, and the run ends {@code FAILED} with
 * {@link RollingBusinessFailure}. Any other decision completes with
 * {@code "kept"}.
 */
@DurableWorkflow(name = RollingSagaWorkflow.TYPE)
public class RollingSagaWorkflow {

    /** The workflow type. */
    public static final String TYPE = "rolling-saga";

    /** The signal the run parks on. */
    public static final String SIGNAL = "decide";

    @ActivityStub(startToCloseTimeout = "PT10M", retryPolicy = @RetryPolicy(maxAttempts = 1))
    private RollingSagaActivities saga;

    /**
     * @param key the workflow's key
     * @return {@code "kept"} unless the decision is {@code "ship"}
     */
    @Saga
    @WorkflowMethod
    public String run(String key) {
        var workflow = WorkflowContext.current();
        saga.reserve(key);
        saga.charge(key);
        String decision = workflow.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
        if ("ship".equals(decision)) {
            return saga.ship(key);
        }
        return "kept";
    }
}
