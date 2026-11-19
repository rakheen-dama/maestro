package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@link RollingSignalWorkflow}'s steps inside branch 0 of a two-branch
 * {@code parallel()}: one step, a park on signal
 * {@value RollingSignalWorkflow#SIGNAL}, one more step, all in the branch's own
 * sequence block (the fork is the run's first step, sequence 1, so branch 0
 * allocates from 2000). Branch 1 returns at once and records nothing. Two
 * branches, because {@code parallel()} runs a single task inline, with no fork
 * and no branch block.
 */
@DurableWorkflow(name = RollingForkWorkflow.TYPE)
public class RollingForkWorkflow {

    /** The workflow type. */
    public static final String TYPE = "rolling-fork";

    @ActivityStub(startToCloseTimeout = "PT1M")
    private RollingActivities activities;

    /**
     * @param key the workflow's key
     * @return {@code "signalled:" + payload}
     */
    @WorkflowMethod
    public String run(String key) {
        var workflow = WorkflowContext.current();
        List<Callable<String>> branches = List.of(() -> {
            activities.step(key, "before");
            String payload = workflow.awaitSignal(RollingSignalWorkflow.SIGNAL, String.class, Duration.ofHours(1));
            activities.step(key, "after");
            return payload;
        }, () -> "idle");
        return "signalled:" + workflow.parallel(branches).getFirst();
    }
}
