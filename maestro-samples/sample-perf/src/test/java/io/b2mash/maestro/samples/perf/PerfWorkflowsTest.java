package io.b2mash.maestro.samples.perf;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.perf.activity.PerfActivities;
import io.b2mash.maestro.samples.perf.workflow.ChainInput;
import io.b2mash.maestro.samples.perf.workflow.ParkInput;
import io.b2mash.maestro.samples.perf.workflow.PerfChainWorkflow;
import io.b2mash.maestro.samples.perf.workflow.PerfParkWorkflow;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * In-memory tests for the two load-test workflows, {@link PerfChainWorkflow}
 * and {@link PerfParkWorkflow}, on {@link TestWorkflowEnvironment}: every
 * chain step runs once, each park mode ends with its own outcome, and a crash
 * mid-chain replays the finished steps instead of re-running them.
 */
class PerfWorkflowsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        activities = new RecordingActivities();
        env.registerActivities(PerfActivities.class, activities);
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void chainRunsEveryActivity() throws TimeoutException {
        var h = env.startWorkflow("chain-1", PerfChainWorkflow.class, new ChainInput(5, 0));

        assertEquals(5, h.getResult(Integer.class, WAIT));
        assertEquals(List.of(0, 1, 2, 3, 4), activities.indices);
    }

    @Test
    void parkSignalReleases() throws TimeoutException {
        var h = env.startWorkflow("park-signal-1", PerfParkWorkflow.class, new ParkInput("signal", 7200));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        h.signal("perf.release", "go");

        assertEquals("released", h.getResult(String.class, WAIT));
        assertEquals(List.of(0, 1), activities.indices);
    }

    @Test
    void parkSignalTimesOut() throws TimeoutException {
        var h = env.startWorkflow("park-timeout-1", PerfParkWorkflow.class, new ParkInput("signal", 1));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        env.advanceTime(Duration.ofSeconds(1));

        assertEquals("timeout", h.getResult(String.class, WAIT));
        assertEquals(List.of(0, 1), activities.indices);
    }

    @Test
    void parkSleepCompletes() throws TimeoutException {
        var h = env.startWorkflow("park-sleep-1", PerfParkWorkflow.class, new ParkInput("sleep", 60));
        h.awaitStatus(WorkflowStatus.WAITING_TIMER, WAIT);

        env.advanceTime(Duration.ofSeconds(60));

        assertEquals("slept", h.getResult(String.class, WAIT));
        assertEquals(List.of(0, 1), activities.indices);
    }

    @Test
    void chainSurvivesCrash() throws TimeoutException {
        var crash = env.crashAfter(2);
        var h = env.startWorkflow("chain-crash-1", PerfChainWorkflow.class, new ChainInput(5, 0));
        crash.recover();

        assertEquals(5, h.getResult(Integer.class, WAIT));
        // Exactly once each: crashAfter(2) kills the node on the very write that
        // persists step 1's result (the crashing thread throws from that write),
        // so step 2 never starts on the dying node; steps 0 and 1 replay, and
        // 2..4 run live once on the recovered node.
        assertEquals(List.of(0, 1, 2, 3, 4), activities.indices);
        assertEquals(List.of("PerfActivities.step", "PerfActivities.step"), h.replayedSteps());
        assertEquals(1, h.recoveries());
    }

    /** Records the index of every live {@code step} call; sleeps for nothing. */
    static final class RecordingActivities implements PerfActivities {

        final List<Integer> indices = new CopyOnWriteArrayList<>();

        @Override
        public int step(int index, long millis) {
            indices.add(index);
            return index + 1;
        }
    }
}
