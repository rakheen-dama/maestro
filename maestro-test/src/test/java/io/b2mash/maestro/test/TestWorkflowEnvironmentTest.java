package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

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
        // The test clock starts at create(); the deadline is set a few ms later
        // when the await parks, so step just past exactly 7 days (as the sleep
        // test above steps 6 minutes for a 5-minute sleep).
        env.advanceTime(Duration.ofDays(7).plusSeconds(1));
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
