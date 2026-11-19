package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;

/**
 * One step, a one-hour durable sleep, one more step. The test does not wait the
 * hour: it moves the timer row's {@code fire_at} back to the database's
 * {@code now()}, which is the clock the timer poller reads.
 */
@DurableWorkflow(name = RollingTimerWorkflow.TYPE)
public class RollingTimerWorkflow {

    /** The workflow type. */
    public static final String TYPE = "rolling-timer";

    @ActivityStub(startToCloseTimeout = "PT1M")
    private RollingActivities activities;

    /**
     * @param key the workflow's key
     * @return {@code "slept"}
     */
    @WorkflowMethod
    public String run(String key) {
        var workflow = WorkflowContext.current();
        activities.step(key, "before");
        workflow.sleep(Duration.ofHours(1));
        activities.step(key, "after");
        return "slept";
    }
}
