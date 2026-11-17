package io.b2mash.maestro.samples.perf.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.samples.perf.activity.PerfDbActivities;

/**
 * Pool-pressure workload: runs {@link HoldInput#activities()} {@code hold}
 * activities one after another, each holding one connection of the
 * application's pool for {@link HoldInput#holdMillis()} — the host's own
 * database work competing with Maestro's store for the shared pool. Default
 * task queue.
 *
 * <p>Deterministic: the loop depends only on the input.
 */
@DurableWorkflow(name = "perf-hold")
public class PerfHoldWorkflow {

    // One attempt: a hold that times out under pool exhaustion (its attempt's
    // 30 s includes the wait for a connection) fails its run, which Scenario 5
    // sees (correctness.holdsCompleted), instead of re-running and adding load
    // the scenario does not control. The design keeps waits far below 30 s:
    // at most about 16 s (8 s of pool wait + the 8 s hold, measured max 16.0 s).
    @ActivityStub(startToCloseTimeout = "PT30S", retryPolicy = @RetryPolicy(maxAttempts = 1))
    private PerfDbActivities db;

    /**
     * Runs the holds.
     *
     * @param input how many holds, and how long each takes
     * @return the number of holds run
     */
    @WorkflowMethod
    public int run(HoldInput input) {
        for (var i = 0; i < input.activities(); i++) {
            db.hold(i, input.holdMillis());
        }
        return input.activities();
    }
}
