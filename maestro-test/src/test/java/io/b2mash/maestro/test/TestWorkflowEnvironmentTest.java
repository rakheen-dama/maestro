package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TestWorkflowEnvironmentTest {

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    // ── Happy path: activity-based workflow ──────────────────────────────

    @Test
    void happyPathWorkflowCompletesWithResult() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(GreetingActivities.class, new GreetingActivitiesImpl());

        var handle = env.startWorkflow(GreetingWorkflow.class, "World");
        var result = handle.getResult(String.class, Duration.ofSeconds(5));

        assertEquals("Hello, World!", result);
        assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
    }

    @Test
    void eventLogContainsActivityEvents() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(GreetingActivities.class, new GreetingActivitiesImpl());

        var handle = env.startWorkflow(GreetingWorkflow.class, "Test");
        handle.getResult(String.class, Duration.ofSeconds(5));

        var events = handle.getEvents();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.ACTIVITY_COMPLETED));
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED));
    }

    // ── Signal delivery ─────────────────────────────────────────────────

    @Test
    void signalDeliveryResumesWorkflow() throws TimeoutException {
        env = TestWorkflowEnvironment.create();

        var handle = env.startWorkflow(SignalWorkflow.class, null);

        // Wait briefly for workflow to park on awaitSignal
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(2));

        // Deliver the signal
        handle.signal("approval", "approved");

        var result = handle.getResult(String.class, Duration.ofSeconds(5));
        assertEquals("Got: approved", result);
    }

    // ── Pre-delivered signal (self-recovery test) ────────────────────────

    @Test
    void preDeliveredSignalConsumedOnStart() throws TimeoutException {
        env = TestWorkflowEnvironment.create();

        // Pre-deliver signal before workflow starts
        var workflowId = "predelivery-test";
        env.preDeliverSignal(workflowId, "approval", "pre-approved");

        var handle = env.startWorkflow(workflowId, SignalWorkflow.class, null);
        var result = handle.getResult(String.class, Duration.ofSeconds(5));

        assertEquals("Got: pre-approved", result);
    }

    // ── Sleep / advanceTime ─────────────────────────────────────────────

    @Test
    void advanceTimeFiresSleepTimer() throws TimeoutException {
        env = TestWorkflowEnvironment.create();

        var handle = env.startWorkflow(SleepWorkflow.class, null);

        // Workflow sleeps for 5 minutes — should not complete yet
        waitForStatus(handle, WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(2));
        assertEquals(WorkflowStatus.WAITING_TIMER, handle.getStatus());

        // Advance time past the sleep duration
        env.advanceTime(Duration.ofMinutes(6));

        var result = handle.getResult(String.class, Duration.ofSeconds(5));
        assertEquals("slept", result);
    }

    @Test
    void advanceTimeFiresAwaitSignalTimeout() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(SevenDayApprovalWorkflow.class, "doc-1");
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        env.advanceTime(Duration.ofDays(7));
        assertEquals("timed-out", handle.getResult(String.class, Duration.ofSeconds(5)));
        assertEquals(1, handle.getEvents().stream().filter(e -> e.eventType() == EventType.SIGNAL_TIMEOUT).count());
    }

    @Test
    void signalBeforeTheAdvancedDeadlineWins() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(SevenDayApprovalWorkflow.class, "doc-2");
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        env.advanceTime(Duration.ofDays(6));
        handle.signal("approval", "approved");
        assertEquals("approved", handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    // ── advanceTime is exact: deadlines arm on the test clock (R59) ─────

    @Test
    void advanceTimeByExactlyTheTimeoutFiresAnAwaitDeadline() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(SevenDayApprovalWorkflow.class, "doc-exact");
        waitUntil(() -> timer(handle, "await-signal-1") != null, "the await to arm its deadline row");
        assertEquals(env.currentTime().plus(Duration.ofDays(7)), timer(handle, "await-signal-1").fireAt(),
                "the deadline is armed from the test clock, not the wall clock");

        env.advanceTime(Duration.ofDays(7));

        assertEquals("timed-out", handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    @Test
    void advanceTimeByExactlyTheDurationFiresASleep() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(SleepWorkflow.class, null);
        waitUntil(() -> timer(handle, "sleep-1") != null, "the sleep to arm its timer row");
        assertEquals(env.currentTime().plus(Duration.ofMinutes(5)), timer(handle, "sleep-1").fireAt());

        env.advanceTime(Duration.ofMinutes(5));

        assertEquals("slept", handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    @Test
    void anAwaitDeadlineDoesNotExpireOnTheWallClock() throws Exception {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(ShortAwaitWorkflow.class, null);
        waitUntil(() -> timer(handle, "await-signal-1") != null, "the await to arm its deadline row");

        // Several times the 200 ms timeout passes on the wall clock while the
        // test clock stands still: the await must still be parked.
        Thread.sleep(Duration.ofMillis(800));
        assertEquals(WorkflowStatus.WAITING_SIGNAL, handle.getStatus());
        assertTrue(handle.getEvents().stream().noneMatch(e -> e.eventType() == EventType.SIGNAL_TIMEOUT),
                "the await timed out on the wall clock although the test clock never moved");

        env.advanceTime(Duration.ofMillis(200));
        assertEquals("timed-out", handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    @Test
    void aTimerArmedWhileAdvanceTimeRunsIsNotFiredByThatCall() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        var handle = env.startWorkflow(ReparkingWorkflow.class, null);
        waitUntil(() -> timer(handle, "sleep-1") != null, "the first sleep to arm");

        // The first sleep fires; the workflow wakes and immediately re-parks on
        // a second sleep, armed during this advance from the advanced clock.
        env.advanceTime(Duration.ofMinutes(1));
        waitUntil(() -> timer(handle, "sleep-3") != null, "the re-park on the second sleep");
        var secondSleep = timer(handle, "sleep-3");
        assertEquals(env.currentTime().plus(Duration.ofMinutes(1)), secondSleep.fireAt());
        // A cascade at the same instant (what the loop inside advanceTime runs)
        // must not fire it.
        env.advanceTime(Duration.ZERO);
        assertEquals(TimerStatus.PENDING, timer(handle, "sleep-3").status());

        // Same for an await deadline armed by a workflow that wakes and re-parks.
        env.advanceTime(Duration.ofMinutes(1));
        waitUntil(() -> timer(handle, "await-signal-5") != null
                && handle.getStatus() == WorkflowStatus.WAITING_SIGNAL, "the re-park on the await");
        assertEquals(env.currentTime().plus(Duration.ofSeconds(30)), timer(handle, "await-signal-5").fireAt());
        env.advanceTime(Duration.ZERO);
        assertEquals(TimerStatus.PENDING, timer(handle, "await-signal-5").status());
        assertTrue(handle.getEvents().stream().noneMatch(e -> e.eventType() == EventType.SIGNAL_TIMEOUT));

        env.advanceTime(Duration.ofSeconds(30));
        assertEquals("timed-out", handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    // ── Query method ────────────────────────────────────────────────────

    @Test
    void queryReturnsWorkflowState() throws TimeoutException {
        env = TestWorkflowEnvironment.create();

        var handle = env.startWorkflow(QueryableWorkflow.class, null);

        // Wait for workflow to park on signal
        waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(2));

        var progress = handle.query("getProgress", null, String.class);
        assertEquals("waiting-for-signal", progress);

        // Complete the workflow and verify result
        handle.signal("continue", "go");
        var result = handle.getResult(String.class, Duration.ofSeconds(5));
        assertEquals("go", result);
    }

    @Test
    void secondInstanceOfAQueryableWorkflowTypeCanStart() {
        env = TestWorkflowEnvironment.create();

        // Query methods are registered per workflow type, not per start: a second
        // start of the same type must not fail with "already registered".
        var first = env.startWorkflow(QueryableWorkflow.class, null);
        var second = env.startWorkflow(QueryableWorkflow.class, null);
        waitForStatus(first, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(2));
        waitForStatus(second, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(2));

        assertEquals("waiting-for-signal", first.query("getProgress", null, String.class));
        assertEquals("waiting-for-signal", second.query("getProgress", null, String.class));
    }

    // ── @MaestroTest annotation ─────────────────────────────────────────

    @Test
    void workflowIdIsAccessible() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(GreetingActivities.class, new GreetingActivitiesImpl());

        var handle = env.startWorkflow("my-custom-id", GreetingWorkflow.class, "Test");

        assertEquals("my-custom-id", handle.getWorkflowId());
        assertNotNull(handle.getInstanceId());
    }

    @Test
    void autoDetectActivityRegistration() throws TimeoutException {
        env = TestWorkflowEnvironment.create();
        // Use varargs registration with auto-detection
        env.registerActivities(new GreetingActivitiesImpl());

        var handle = env.startWorkflow(GreetingWorkflow.class, "Auto");
        var result = handle.getResult(String.class, Duration.ofSeconds(5));

        assertEquals("Hello, Auto!", result);
    }

    @Test
    void missingActivityRegistrationThrows() {
        env = TestWorkflowEnvironment.create();
        // Don't register any activities

        assertThrows(IllegalStateException.class,
                () -> env.startWorkflow(GreetingWorkflow.class, "Missing"));
    }

    // ── Sample workflow definitions ─────────────────────────────────────

    @DurableWorkflow(name = "test-greeting", taskQueue = "test")
    public static class GreetingWorkflow {
        @ActivityStub(startToCloseTimeout = "PT5S")
        private GreetingActivities activities;

        @WorkflowMethod
        public String greet(String name) {
            return activities.buildGreeting(name);
        }
    }

    @DurableWorkflow(name = "test-signal", taskQueue = "test")
    public static class SignalWorkflow {
        @WorkflowMethod
        public String waitForApproval() {
            var workflow = WorkflowContext.current();
            String approval = workflow.awaitSignal("approval", String.class, Duration.ofMinutes(10));
            return "Got: " + approval;
        }
    }

    @DurableWorkflow(name = "test-sleep", taskQueue = "test")
    public static class SleepWorkflow {
        @WorkflowMethod
        public String sleepAndReturn() {
            var workflow = WorkflowContext.current();
            workflow.sleep(Duration.ofMinutes(5));
            return "slept";
        }
    }

    @DurableWorkflow(name = "test-seven-day-approval", taskQueue = "test")
    public static class SevenDayApprovalWorkflow {
        @WorkflowMethod
        public String awaitApproval(String documentId) {
            try {
                return WorkflowContext.current().awaitSignal("approval", String.class, Duration.ofDays(7));
            } catch (SignalTimeoutException e) {
                return "timed-out";
            }
        }
    }

    @DurableWorkflow(name = "test-short-await", taskQueue = "test")
    public static class ShortAwaitWorkflow {
        @WorkflowMethod
        public String run() {
            try {
                return WorkflowContext.current().awaitSignal("approval", String.class, Duration.ofMillis(200));
            } catch (SignalTimeoutException e) {
                return "timed-out";
            }
        }
    }

    /** Sleeps, re-parks on a second sleep, then re-parks on an await (seq 1, 3, 5). */
    @DurableWorkflow(name = "test-reparking", taskQueue = "test")
    public static class ReparkingWorkflow {
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            workflow.sleep(Duration.ofMinutes(1));
            workflow.sleep(Duration.ofMinutes(1));
            try {
                return workflow.awaitSignal("approval", String.class, Duration.ofSeconds(30));
            } catch (SignalTimeoutException e) {
                return "timed-out";
            }
        }
    }

    @DurableWorkflow(name = "test-queryable", taskQueue = "test")
    public static class QueryableWorkflow {
        private volatile String progress = "started";

        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            progress = "waiting-for-signal";
            String signal = workflow.awaitSignal("continue", String.class, Duration.ofMinutes(10));
            progress = "completed";
            return signal;
        }

        @QueryMethod(name = "getProgress")
        public String getProgress() {
            return progress;
        }
    }

    // ── Activity interface and implementation ────────────────────────────

    @Activity(name = "GreetingActivities")
    public interface GreetingActivities {
        String buildGreeting(String name);
    }

    public static class GreetingActivitiesImpl implements GreetingActivities {
        @Override
        public String buildGreeting(String name) {
            return "Hello, " + name + "!";
        }
    }

    // ── Test helpers ─────────────────────────────────────────────────────

    private @Nullable WorkflowTimer timer(TestWorkflowHandle handle, String timerId) {
        return env.getStore().findTimer(handle.getInstanceId(), timerId).orElse(null);
    }

    private static void waitUntil(BooleanSupplier condition, String what) {
        var deadline = java.time.Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            if (!java.time.Instant.now().isBefore(deadline)) {
                fail("timed out waiting for " + what);
            }
            try {
                Thread.sleep(Duration.ofMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static void waitForStatus(TestWorkflowHandle handle, WorkflowStatus expected,
                                      Duration timeout) {
        var deadline = java.time.Instant.now().plus(timeout);
        while (java.time.Instant.now().isBefore(deadline)) {
            if (handle.getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(Duration.ofMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        // Don't fail — the status might have already transitioned past the expected state.
        // The caller is responsible for asserting post-conditions.
    }
}
