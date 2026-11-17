package io.b2mash.maestro.samples.perf.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.samples.perf.activity.PerfActivities;

/**
 * Throughput workload: runs {@link ChainInput#activities()} {@code step}
 * activities one after another and completes. Each step is one memoized
 * activity, so the run's store cost scales with the knob. Default task queue.
 *
 * <p>Deterministic: the loop depends only on the input.
 */
@DurableWorkflow(name = "perf-chain")
public class PerfChainWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S")
    private PerfActivities activities;

    /**
     * Runs the chain.
     *
     * @param input how many steps, and how long each takes
     * @return the number of steps run
     */
    @WorkflowMethod
    public int run(ChainInput input) {
        var steps = 0;
        for (var i = 0; i < input.activities(); i++) {
            activities.step(i, input.activityMillis());
            steps++;
        }
        return steps;
    }
}
