package io.b2mash.maestro.samples.perf.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.perf.activity.PerfActivities;

import java.time.Duration;

/**
 * Parked-population workload: one step, then a park — on the signal
 * {@value #RELEASE_SIGNAL} (released by a signal or by its timeout) or on a
 * durable sleep — then one more step. Default task queue.
 *
 * <p>Deterministic: the branch depends only on the input, and the outcome of
 * the park is recorded by the engine.
 */
@DurableWorkflow(name = "perf-park")
public class PerfParkWorkflow {

    /** The signal that releases a run parked in {@code signal} mode. */
    public static final String RELEASE_SIGNAL = "perf.release";

    @ActivityStub(startToCloseTimeout = "PT30S")
    private PerfActivities activities;

    /**
     * Runs the park.
     *
     * @param input the park mode and how long to park
     * @return {@code "released"} or {@code "timeout"} in {@code signal} mode,
     *         {@code "slept"} in {@code sleep} mode
     */
    @WorkflowMethod
    public String run(ParkInput input) {
        var workflow = WorkflowContext.current();
        var parkFor = Duration.ofSeconds(input.parkSeconds());

        activities.step(0, 0);
        String outcome;
        if (ParkInput.SLEEP.equals(input.parkMode())) {
            workflow.sleep(parkFor);
            outcome = "slept";
        } else {
            try {
                workflow.awaitSignal(RELEASE_SIGNAL, String.class, parkFor);
                outcome = "released";
            } catch (SignalTimeoutException e) {
                outcome = "timeout";
            }
        }
        activities.step(1, 0);
        return outcome;
    }
}
