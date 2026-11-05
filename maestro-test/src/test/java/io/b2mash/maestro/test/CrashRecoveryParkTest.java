package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crashes while a workflow is parked — on a signal or a durable timer — and
 * the signals and timers that land while the service is down.
 */
class CrashRecoveryParkTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void signalDeliveredWhileTheNodeIsDown_isConsumedByTheRecoveredRun() throws Exception {
        var handle = env.startWorkflow(AwaitWorkflow.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));

        var crash = env.crashNow();
        handle.signal("approval", "yes");   // persisted; the dead node cannot consume it

        assertTrue(env.getStore().getUnconsumedSignals(handle.getWorkflowId(), "approval").size() == 1,
                "the signal waits in the store while the node is down");

        crash.recover();

        assertEquals("approved: yes", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(1, handle.recoveries());
    }

    @Test
    void signalDeliveredAfterRecovery_isConsumed() throws Exception {
        var handle = env.startWorkflow(AwaitWorkflow.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));

        env.crashNow().recover();
        handle.signal("approval", "later");

        assertEquals("approved: later", handle.getResult(String.class, Duration.ofSeconds(10)));
    }

    @Test
    void sleepingWorkflow_recoversAndWakesOnTheNextAdvance() throws Exception {
        var handle = env.startWorkflow(SleepWorkflow.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(5));

        env.crashNow().recover();
        env.advanceTime(Duration.ofHours(1));

        assertEquals("rested", handle.getResult(String.class, Duration.ofSeconds(10)));
    }

    @Test
    void timerDueWhileTheNodeIsDown_firesAndTheRecoveredRunHealsTheWake() throws Exception {
        var handle = env.startWorkflow(SleepWorkflow.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(5));

        var crash = env.crashNow();
        env.advanceTime(Duration.ofHours(1));   // the timer row fires; the dead node records nothing
        assertTrue(env.getStore().getAllTimers().stream().allMatch(t -> t.status() == TimerStatus.FIRED));
        assertTrue(handle.getEvents().stream().noneMatch(e -> e.eventType() == EventType.TIMER_FIRED));

        crash.recover();

        assertEquals("rested", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(1, handle.getEvents().stream().filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
    }

    @Test
    void crashRightAfterTheTimerIsScheduled_replayFindsTheRowAndHeals() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.TIMER_SCHEDULED);
        var handle = env.startWorkflow(SleepWorkflow.class, null);
        crash.awaitCrash(Duration.ofSeconds(10));
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus(),
                "the crash landed between the TIMER_SCHEDULED memo and the WAITING_TIMER write");

        env.advanceTime(Duration.ofHours(1));   // fires the row while the node is down
        crash.recover();

        assertEquals("rested", handle.getResult(String.class, Duration.ofSeconds(10)));
    }

    @Test
    void crashRightAfterTheTimerIsScheduled_recoveredRunReParksUntilTheTimerFires() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.TIMER_SCHEDULED);
        var handle = env.startWorkflow(SleepWorkflow.class, null);

        crash.recover();
        // Fired whether or not the recovered run has re-parked yet: the row is
        // the arbiter, so an early fire is found by the replay's timer-row read.
        env.advanceTime(Duration.ofHours(1));

        assertEquals("rested", handle.getResult(String.class, Duration.ofSeconds(10)));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @DurableWorkflow(name = "await-approval")
    public static class AwaitWorkflow {
        @WorkflowMethod
        public String run() {
            var answer = WorkflowContext.current().awaitSignal("approval", String.class, Duration.ofDays(1));
            return "approved: " + answer;
        }
    }

    @DurableWorkflow(name = "nap")
    public static class SleepWorkflow {
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofHours(1));
            return "rested";
        }
    }
}
