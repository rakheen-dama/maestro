package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test kit's crash/recover path: a run that dies part-way is re-adopted by
 * a fresh node that replays every persisted step through the same code the
 * production recovery uses.
 */
class CrashRecoveryTest {

    private TestWorkflowEnvironment env;
    private CountingSteps steps;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        steps = new CountingSteps();
        env.registerActivities(Steps.class, steps);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void crashAfterTwoActivities_recoveryReplaysBothAndRunsTheRestLive() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(ThreeSteps.class, "in");
        crash.awaitCrash(Duration.ofSeconds(10));
        var epochBefore = env.getStore().getInstance(handle.getWorkflowId()).orElseThrow().leaseEpoch();
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus(), "a crash writes nothing");

        assertEquals(1, crash.recover(), "the crashed workflow is the one recovery adopts");

        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(List.of("Steps.step1", "Steps.step2"), handle.replayedSteps());
        assertEquals(List.of("Steps.step1", "Steps.step2", "Steps.step3"), handle.liveSteps());
        assertEquals(1, steps.calls("step1"));
        assertEquals(1, steps.calls("step2"));
        assertEquals(1, steps.calls("step3"));
        assertEquals(1, handle.recoveries());
        assertTrue(env.getStore().getInstance(handle.getWorkflowId()).orElseThrow().leaseEpoch() > epochBefore,
                "the recovering node adopted the instance, moving its lease fence");

        assertTrue(crash.hasCrashed());
        assertEquals(handle.getWorkflowId(), crash.workflowId());
        var event = crash.event();
        assertNotNull(event);
        assertEquals(EventType.ACTIVITY_COMPLETED, event.eventType());
        assertEquals("Steps.step2", event.stepName());
    }

    @Test
    void crashBeforePersisting_theInFlightActivityRunsAgain() throws Exception {
        var crash = env.crashBeforePersisting(
                e -> e.eventType() == EventType.ACTIVITY_COMPLETED && "Steps.step2".equals(e.stepName()));
        var handle = env.startWorkflow(ThreeSteps.class, "in");

        crash.recover();

        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(2, steps.calls("step2"), "step 2 executed, but its result was never persisted");
        assertEquals(2, handle.liveExecutions("Steps.step2"));
        assertEquals(1, handle.replays("Steps.step1"));
        assertEquals(0, handle.replays("Steps.step2"));
        assertEquals(1, steps.calls("step1"));
    }

    @Test
    void crashAfterPersisting_matchesTheFirstEventThePredicateAccepts() throws Exception {
        var crash = env.crashAfterPersisting(
                e -> e.eventType() == EventType.ACTIVITY_COMPLETED && "Steps.step1".equals(e.stepName()));
        var handle = env.startWorkflow(ThreeSteps.class, "in");

        crash.recover();

        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(List.of("Steps.step1"), handle.replayedSteps());
        assertEquals(1, steps.calls("step1"));
    }

    @Test
    void crashBeforeTheTerminalWrite_recoveryReplaysEverythingAndCompletes() throws Exception {
        var crash = env.crashBeforePersisting(e -> e.eventType() == EventType.WORKFLOW_COMPLETED);
        var handle = env.startWorkflow(ThreeSteps.class, "in");
        crash.awaitCrash(Duration.ofSeconds(10));

        assertEquals(WorkflowStatus.RUNNING, handle.getStatus(), "the atomic terminal write never happened");
        assertTrue(handle.getEvents().stream().noneMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED));

        crash.recover();

        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(List.of("Steps.step1", "Steps.step2", "Steps.step3"), handle.replayedSteps());
        assertEquals(1, steps.calls("step1"));
        assertEquals(1, steps.calls("step2"));
        assertEquals(1, steps.calls("step3"));
    }

    @Test
    void crashAfterTheTerminalWrite_theWorkflowStaysCompletedAndNothingIsRecovered() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.WORKFLOW_COMPLETED);
        var handle = env.startWorkflow(ThreeSteps.class, "in");
        crash.awaitCrash(Duration.ofSeconds(10));

        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(0, crash.recover(), "a completed workflow is not recoverable");
        assertEquals(0, handle.recoveries());
    }

    @Test
    void withTwoWorkflowsRunning_nothingLandsOnceTheCrashHasFired() throws Exception {
        for (var round = 0; round < 5; round++) {
            var crash = env.crashAfter(40);
            var a = env.startWorkflow("loop-a-" + round, Looping.class, 60);
            var b = env.startWorkflow("loop-b-" + round, Looping.class, 60);

            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!crash.hasCrashed()) {
                assertTrue(System.nanoTime() < deadline, "the crash never fired");
                Thread.onSpinWait();
            }
            var atCrash = a.getEvents().size() + b.getEvents().size();

            crash.awaitCrash(Duration.ofSeconds(10));
            org.awaitility.Awaitility.await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2))
                    .until(() -> a.getEvents().size() + b.getEvents().size() == atCrash);
            assertTrue(a.getStatus().isActive() && b.getStatus().isActive(),
                    "neither workflow could finish on the dead node");

            crash.recover();
            assertEquals(60, a.getResult(Integer.class, Duration.ofSeconds(10)));
            assertEquals(60, b.getResult(Integer.class, Duration.ofSeconds(10)));
        }
    }

    @Test
    void afterRecovery_startQueryAndSignalReachTheNewNode() throws Exception {
        var parked = env.startWorkflow(Gate.class, null);
        parked.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        awaitQueryable(parked);

        env.crashNow().recover();

        // The recovered run answers queries from its own (new) workflow object.
        awaitQueryable(parked);
        assertEquals("waiting", parked.query("phase", null, String.class));

        var fresh = env.startWorkflow(Gate.class, null);
        fresh.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        awaitQueryable(fresh);
        assertEquals("waiting", fresh.query("phase", null, String.class));

        parked.signal("go", "a");
        fresh.signal("go", "b");
        assertEquals("got a", parked.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals("got b", fresh.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(1, parked.recoveries());
        assertEquals(0, fresh.recoveries());
    }

    @Test
    void armingASecondCrashWhileOneIsArmedThrows() {
        env.crashAfter(1);

        assertThrows(IllegalStateException.class, () -> env.crashAfter(1));
        assertThrows(IllegalStateException.class, () -> env.crashNow());
        assertThrows(IllegalStateException.class,
                () -> env.crashBeforePersisting(e -> true));
    }

    @Test
    void crashAfterRequiresAPositiveCount() {
        assertThrows(IllegalArgumentException.class, () -> env.crashAfter(0));
    }

    @Test
    void aCrashThatIsNeverReachedFailsLoudlyAndDisarms() throws Exception {
        var crash = env.crashAfter(5);
        var handle = env.startWorkflow(ThreeSteps.class, "in");
        assertEquals("in>1>2>3", handle.getResult(String.class, Duration.ofSeconds(10)));

        var error = assertThrows(AssertionError.class, () -> crash.awaitCrash(Duration.ofMillis(200)));
        assertTrue(error.getMessage().contains("crash armed but never reached"), error.getMessage());
        assertFalse(crash.hasCrashed());
        assertNull(crash.event());

        // Disarmed: another crash can be armed.
        env.crashAfter(1);
    }

    @Test
    void startingAWorkflowOnADeadNodeThrows() {
        env.crashNow();

        var error = assertThrows(IllegalStateException.class,
                () -> env.startWorkflow(ThreeSteps.class, "in"));
        assertTrue(error.getMessage().contains("recover()"), error.getMessage());
    }

    @Test
    void recoverWithoutACrashIsARestart() throws Exception {
        var parked = env.startWorkflow(Gate.class, null);
        parked.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));

        assertEquals(1, env.recover());

        parked.signal("go", "x");
        assertEquals("got x", parked.getResult(String.class, Duration.ofSeconds(10)));
    }

    @Test
    void shutdownClosesEveryNodeIncludingRetiredOnes() throws Exception {
        env.crashNow().recover();
        env.recover();
        var nodes = env.nodeExecutors();
        assertEquals(3, nodes.size());

        env.shutdown();

        var method = ThreeSteps.class.getMethod("run", String.class);
        for (var executor : nodes) {
            assertEquals(0, executor.runningCount());
            assertThrows(IllegalStateException.class,
                    () -> executor.startWorkflow("x", "t", "default", null, Object::new, method),
                    "every node, retired or current, is shut down");
        }
    }

    private static void awaitQueryable(TestWorkflowHandle handle) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).ignoreExceptions()
                .until(() -> "waiting".equals(handle.query("phase", null, String.class)));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Steps {
        String step1(String in);

        String step2(String in);

        String step3(String in);
    }

    static final class CountingSteps implements Steps {
        private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        int calls(String step) {
            var count = calls.get(step);
            return count == null ? 0 : count.get();
        }

        private String record(String step, String in, String suffix) {
            calls.computeIfAbsent(step, _ -> new AtomicInteger()).incrementAndGet();
            return in + suffix;
        }

        @Override
        public String step1(String in) {
            return record("step1", in, ">1");
        }

        @Override
        public String step2(String in) {
            return record("step2", in, ">2");
        }

        @Override
        public String step3(String in) {
            return record("step3", in, ">3");
        }
    }

    @DurableWorkflow(name = "three-steps")
    public static class ThreeSteps {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run(String in) {
            var a = steps.step1(in);
            var b = steps.step2(a);
            return steps.step3(b);
        }
    }

    /** Runs {@code n} activities in a row, so two of them keep a node busy writing. */
    @DurableWorkflow(name = "looping")
    public static class Looping {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public int run(int n) {
            for (var i = 0; i < n; i++) {
                steps.step1("x" + i);
            }
            return n;
        }
    }

    @DurableWorkflow(name = "gate")
    public static class Gate {
        private volatile String phase = "starting";

        @WorkflowMethod
        public String run() {
            phase = "waiting";
            var value = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
            phase = "done";
            return "got " + value;
        }

        @QueryMethod
        public String phase() {
            return phase;
        }
    }
}
