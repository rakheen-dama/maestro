package io.b2mash.maestro.samples.perf.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.perf.activity.PerfActivities;

import java.time.Duration;

/**
 * Signal-throughput workload: one step, then {@link SignalsInput#signals()}
 * awaits in turn, each on its own signal name {@code perf.s<k>} (so every
 * delivery is a distinct {@code (workflowId, signalName)} key), then one more
 * step. A signal that arrives before its await is stored and consumed when
 * the await is reached. Default task queue.
 *
 * <p>Deterministic: the loop depends only on the input, and each await's
 * outcome is recorded by the engine.
 */
@DurableWorkflow(name = "perf-signals")
public class PerfSignalsWorkflow {

    /** The prefix of the signal names; the k-th await is on {@code perf.s<k>}. */
    public static final String SIGNAL_PREFIX = "perf.s";

    @ActivityStub(startToCloseTimeout = "PT30S")
    private PerfActivities activities;

    /**
     * Runs the awaits.
     *
     * @param input how many signals, and the timeout of each await
     * @return {@link SignalsInput#signals()}, or {@code -1} as soon as one
     *         await times out (the closing step is then not run)
     */
    @WorkflowMethod
    public int run(SignalsInput input) {
        var workflow = WorkflowContext.current();
        var timeout = Duration.ofSeconds(input.parkSeconds());

        activities.step(0, 0);
        for (var k = 0; k < input.signals(); k++) {
            try {
                workflow.awaitSignal(SIGNAL_PREFIX + k, String.class, timeout);
            } catch (SignalTimeoutException e) {
                return -1;
            }
        }
        activities.step(1, 0);
        return input.signals();
    }
}
