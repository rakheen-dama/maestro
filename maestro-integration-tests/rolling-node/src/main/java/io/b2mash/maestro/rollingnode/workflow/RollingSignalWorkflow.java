package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;

/** One step, a park on signal {@value #SIGNAL} (one-hour timeout), one more step. */
@DurableWorkflow(name = RollingSignalWorkflow.TYPE)
public class RollingSignalWorkflow {

    /** The workflow type. */
    public static final String TYPE = "rolling-signal";

    /** The signal the run parks on. */
    public static final String SIGNAL = "go";

    @ActivityStub(startToCloseTimeout = "PT1M")
    private RollingActivities activities;

    /**
     * @param key the workflow's key
     * @return {@code "signalled:" + payload}
     */
    @WorkflowMethod
    public String run(String key) {
        var workflow = WorkflowContext.current();
        activities.step(key, "before");
        String payload = workflow.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
        activities.step(key, "after");
        return "signalled:" + payload;
    }
}
