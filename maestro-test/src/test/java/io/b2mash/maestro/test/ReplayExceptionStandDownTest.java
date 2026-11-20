package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 89: an exception that workflow code throws while its run is still
 * replaying recorded history (a code change made it throw inside the recorded
 * prefix) stands the run down as {@link StandDownReason#REPLAY_EXCEPTION}:
 * nothing written, nothing failed, nothing compensated, and the run finishes
 * once code that matches its history is deployed. An exception thrown once the
 * lane has caught up — live, or a recorded failure replayed at the end of the
 * history — still fails the run and compensates, as before.
 */
@DisplayName("Issue 89 — an exception thrown while replaying recorded history stands down")
class ReplayExceptionStandDownTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;
    private RecordingSteps steps;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        steps = new RecordingSteps();
        env.registerActivities(Steps.class, steps);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    @DisplayName("new code throws inside a saga's recorded prefix: REPLAY_EXCEPTION (was nondeterministic_history), nothing written or compensated; the old code then completes the run")
    void anExceptionInsideTheRecordedPrefixStandsDown() throws Exception {
        var handle = env.startWorkflow("wf-prefix", ParkedV1.class, "k1");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        var history = handle.getEvents();

        env.recover(ThrowsInsideThePrefix.class);

        var standDown = handle.awaitStandDown(BOUND);
        assertEquals(StandDownReason.REPLAY_EXCEPTION, standDown.reason());
        assertEquals("seq=2 stored=Steps.b type=ACTIVITY_COMPLETED threw=java.lang.IllegalStateException: "
                + "the new code rejects k1", standDown.detail());
        assertNothingRecorded(handle, history, WorkflowStatus.WAITING_SIGNAL);

        env.recover(ParkedV1.class);
        handle.signal("go", "ok");
        assertEquals("a:k1|b:k1|ok|c:k1", handle.getResult(String.class, BOUND));
        assertEquals(List.of("a:k1", "b:k1", "c:k1"), steps.calls, "nothing ran twice, nothing compensated");
    }

    @Test
    @DisplayName("minSupported raised above a parked run's recorded version: REPLAY_EXCEPTION (was stale_run), not FAILED")
    void aRaisedVersionFloorStandsDown() throws Exception {
        var handle = env.startWorkflow("wf-floor", VersionedV1.class, "k2");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        var history = handle.getEvents();

        env.recover(VersionedRaisedFloor.class);

        var standDown = handle.awaitStandDown(BOUND);
        assertEquals(StandDownReason.REPLAY_EXCEPTION, standDown.reason());
        assertTrue(standDown.detail() != null && standDown.detail().startsWith(
                "seq=3 stored=Steps.b type=ACTIVITY_COMPLETED "
                        + "threw=io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException: "),
                standDown.detail());
        assertNothingRecorded(handle, history, WorkflowStatus.WAITING_SIGNAL);

        env.recover(VersionedV1.class);
        handle.signal("go", "ok");
        assertEquals("a:k2|b:k2|ok|v1", handle.getResult(String.class, BOUND));
        assertEquals(List.of("a:k2", "b:k2"), steps.calls, "nothing ran twice");
    }

    @Test
    @DisplayName("(a) new code throws after the replay caught up: FAILED and compensated, as before")
    void anExceptionAfterTheReplayCaughtUpFailsAndCompensates() throws Exception {
        var handle = env.startWorkflow("wf-live", ParkedV1.class, "k3");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        env.recover(ThrowsAfterTheSignal.class);
        handle.signal("go", "ok");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        assertEquals(IllegalStateException.class.getName(), handle.getFailure().orElseThrow().exceptionType());
        assertEquals(List.of("a:k3", "b:k3", "undoA:a:k3"), steps.calls, "the compensation ran");
        assertTrue(handle.standDowns().isEmpty(), handle.standDowns().toString());
    }

    @Test
    @DisplayName("(b) a recorded ACTIVITY_FAILED at the end of the history, replayed and uncaught: FAILED and compensated, as before")
    void aReplayedFailureAtTheEndOfTheHistoryFails() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.ACTIVITY_FAILED);
        var handle = env.startWorkflow("wf-refail", FailsOnB.class, "k4");
        crash.awaitCrash(BOUND);
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus());

        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        assertEquals(List.of("a:k4", "fail:k4", "undoA:a:k4"), steps.calls,
                "the failure was replayed, not run again, and the compensation ran once");
        assertTrue(handle.standDowns().isEmpty(), handle.standDowns().toString());
    }

    @Test
    @DisplayName("(b) a recorded SIGNAL_TIMEOUT at the end of the history, replayed and uncaught: FAILED, as before")
    void aReplayedSignalTimeoutAtTheEndOfTheHistoryFails() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.SIGNAL_TIMEOUT);
        var handle = env.startWorkflow("wf-timeout", TimesOut.class, "k5");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        env.advanceTime(Duration.ofMinutes(2));
        crash.awaitCrash(BOUND);

        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        assertTrue(handle.standDowns().isEmpty(), handle.standDowns().toString());
    }

    @Test
    @DisplayName("(c) a branch throws inside its recorded prefix while a sibling is parked: REPLAY_EXCEPTION, no compensation")
    void aBranchThrowingInsideItsRecordedPrefixStandsDown() throws Exception {
        var handle = env.startWorkflow("wf-branch", ParallelV1.class, "k6");
        until(() -> steps.calls.size() == 2 && handle.getStatus() == WorkflowStatus.WAITING_SIGNAL);

        env.recover(BranchThrowsInsideItsPrefix.class);
        handle.signal("go", "ok");

        var standDown = handle.awaitStandDown(BOUND);
        assertEquals(StandDownReason.REPLAY_EXCEPTION, standDown.reason());
        assertTrue(standDown.detail() != null && standDown.detail().startsWith("seq=2002 stored=Steps.b "),
                standDown.detail());
        assertTrue(handle.getStatus().isActive(), "not failed: " + handle.getStatus());
        assertEquals(List.of("a:k6", "b:k6"), steps.calls, "nothing compensated");
        assertTrue(handle.getEvents().stream().noneMatch(e -> e.eventType() == EventType.WORKFLOW_FAILED
                || e.eventType() == EventType.COMPENSATION_STARTED));

        env.recover(ParallelV1.class);
        assertEquals("a:k6|ok", handle.getResult(String.class, BOUND));
    }

    @Test
    @DisplayName("(c) a branch throws after consuming its recorded history while a sibling replays: FAILED and compensated, as before")
    void aBranchThrowingAfterItsHistoryFailsAndCompensates() throws Exception {
        var handle = env.startWorkflow("wf-branch-live", ParallelV1.class, "k7");
        until(() -> steps.calls.size() == 2 && handle.getStatus() == WorkflowStatus.WAITING_SIGNAL);

        env.recover(BranchThrowsAfterItsHistory.class);
        handle.signal("go", "ok");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        assertEquals(List.of("a:k7", "b:k7", "undoA:a:k7"), steps.calls, "the compensation ran");
        assertTrue(handle.standDowns().isEmpty(), handle.standDowns().toString());
    }

    private static void assertNothingRecorded(TestWorkflowHandle handle, List<WorkflowEvent> history,
                                              WorkflowStatus status) throws Exception {
        assertEquals(status, handle.getStatus());
        assertEquals(history, handle.getEvents(), "a stand-down writes nothing");
        assertTrue(handle.getFailure().isEmpty());
    }

    private static void until(BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(BOUND);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + BOUND);
            }
            Thread.sleep(10);
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Steps {
        @Compensate("undoA")
        String a(String k);

        void undoA(String k);

        String b(String k);

        String c(String k);

        String fail(String k);
    }

    static final class RecordingSteps implements Steps {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override public String a(String k) { return record("a", k); }
        @Override public void undoA(String k) { record("undoA", k); }
        @Override public String b(String k) { return record("b", k); }
        @Override public String c(String k) { return record("c", k); }

        @Override
        public String fail(String k) {
            record("fail", k);
            throw new IllegalStateException("fail " + k);
        }

        private String record(String step, String k) {
            calls.add(step + ":" + k);
            return step + ":" + k;
        }
    }

    /** {@code a} (compensated), {@code b}, park on {@code go}, {@code c}. */
    @DurableWorkflow(name = "replay-exc")
    public static class ParkedV1 {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            var a = steps.a(k);
            var b = steps.b(k);
            var go = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(k);
        }
    }

    /** {@link ParkedV1} edited to throw after {@code a} — inside the recorded prefix. */
    @DurableWorkflow(name = "replay-exc")
    public static class ThrowsInsideThePrefix {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            steps.a(k);
            throw new IllegalStateException("the new code rejects " + k);
        }
    }

    /** {@link ParkedV1} edited to throw once the signal arrived — after the recorded history. */
    @DurableWorkflow(name = "replay-exc")
    public static class ThrowsAfterTheSignal {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            steps.a(k);
            steps.b(k);
            var go = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
            throw new IllegalStateException("the new code rejects " + go);
        }
    }

    /** {@code a}, {@code version("path", DEFAULT, 1)}, {@code b}, park on {@code go} (not a saga). */
    @DurableWorkflow(name = "replay-exc-version")
    public static class VersionedV1 {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @WorkflowMethod
        public String run(String k) {
            var ctx = WorkflowContext.current();
            var a = steps.a(k);
            int v = ctx.version("path", WorkflowContext.DEFAULT_VERSION, 1);
            var b = steps.b(k);
            var go = ctx.awaitSignal("go", String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|v" + v;
        }
    }

    /** {@link VersionedV1} with the floor raised to 2 while the run recorded 1. */
    @DurableWorkflow(name = "replay-exc-version")
    public static class VersionedRaisedFloor {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @WorkflowMethod
        public String run(String k) {
            var ctx = WorkflowContext.current();
            var a = steps.a(k);
            int v = ctx.version("path", 2, 2);
            var b = steps.b(k);
            var go = ctx.awaitSignal("go", String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|v" + v;
        }
    }

    /** {@code a} (compensated), then {@code fail} throws. */
    @DurableWorkflow(name = "replay-exc-fail")
    public static class FailsOnB {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            steps.a(k);
            return steps.fail(k);
        }
    }

    /** Awaits {@code go} for a minute and lets the timeout escape. */
    @DurableWorkflow(name = "replay-exc-timeout")
    public static class TimesOut {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @WorkflowMethod
        public String run(String k) {
            steps.a(k);
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(1));
        }
    }

    /** Branch 0: {@code a} (compensated), {@code b}. Branch 1: park on {@code go}. */
    @DurableWorkflow(name = "replay-exc-parallel")
    public static class ParallelV1 {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            List<Callable<String>> branches = List.of(
                    () -> {
                        var a = steps.a(k);
                        steps.b(k);
                        return a;
                    },
                    () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1)));
            var results = WorkflowContext.current().parallel(branches);
            return results.get(0) + "|" + results.get(1);
        }
    }

    /** {@link ParallelV1} with branch 0 throwing after {@code a} — inside its recorded prefix. */
    @DurableWorkflow(name = "replay-exc-parallel")
    public static class BranchThrowsInsideItsPrefix {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            List<Callable<String>> branches = List.of(
                    () -> {
                        steps.a(k);
                        throw new IllegalStateException("branch 0 rejects " + k);
                    },
                    () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1)));
            WorkflowContext.current().parallel(branches);
            return "unreachable";
        }
    }

    /** {@link ParallelV1} with branch 0 throwing after {@code b} — once its recorded history is consumed. */
    @DurableWorkflow(name = "replay-exc-parallel")
    public static class BranchThrowsAfterItsHistory {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Steps steps;

        @Saga
        @WorkflowMethod
        public String run(String k) {
            List<Callable<String>> branches = List.of(
                    () -> {
                        steps.a(k);
                        steps.b(k);
                        throw new IllegalStateException("branch 0 rejects " + k);
                    },
                    () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1)));
            WorkflowContext.current().parallel(branches);
            return "unreachable";
        }
    }
}
