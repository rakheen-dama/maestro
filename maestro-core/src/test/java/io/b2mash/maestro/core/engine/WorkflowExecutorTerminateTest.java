package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code WorkflowExecutor.terminateWorkflow} — the engine half of the
 * dashboard's Terminate button.
 *
 * <h2>What terminate is</h2>
 * <p><b>Durable-first, then best-effort local eviction.</b> The instance row is
 * moved to {@code TERMINATED} by an optimistic compare-and-set that any node can
 * win, and only then is a locally-running thread evicted. That ordering is what
 * makes the command work from a node that does not own the workflow — the common
 * case, since the command arrives on whichever consumer-group member Kafka
 * happens to pick.
 *
 * <h2>What terminate is not</h2>
 * <p>It <b>marks and stops</b>: no compensation runs, and an in-flight activity
 * is not interrupted (the same policy shutdown has). Those are contract, not
 * omissions, so they are asserted here rather than left implied.
 */
@DisplayName("Terminating a workflow marks it TERMINATED and stops it without compensating")
class WorkflowExecutorTerminateTest {

    private VersionedInMemoryStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(
                store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30),
                true, Duration.ofSeconds(5), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── The owning node ────────────────────────────────────────────────

    @Test
    @DisplayName("a workflow parked on a signal is evicted promptly, with no compensation")
    void terminateParkedOnSignal_evictsWithoutCompensating() throws Exception {
        var workflow = new CompensatingAwaitWorkflow();
        executor.startWorkflow("term-signal", "CompensatingAwaitWorkflow", "default",
                null, workflow, CompensatingAwaitWorkflow.class.getMethod("run"));
        awaitStatus("term-signal", WorkflowStatus.WAITING_SIGNAL);

        var outcome = executor.terminateWorkflow("term-signal", "stuck on a signal that never came");

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED, outcome);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-signal"),
                        "the evicted thread must unwind promptly"));
        assertEquals(0, executor.runningCount());

        var instance = store.getInstance("term-signal").orElseThrow();
        assertEquals(WorkflowStatus.TERMINATED, instance.status());
        assertNotNull(instance.completedAt(), "a terminal status must carry completedAt");
        assertNotNull(instance.output(), "the reason must be recorded on the instance");
        assertEquals("stuck on a signal that never came", instance.output().get("reason").stringValue());
        assertEquals("admin", instance.output().get("terminatedBy").stringValue());

        assertFalse(workflow.compensated.get(),
                "terminate marks and stops — it must not run compensations");
        assertTrue(events(instance.id(), EventType.COMPENSATION_STARTED).isEmpty(),
                "no compensation event may be recorded");
        awaitLifecycleCount(LifecycleEventType.WORKFLOW_TERMINATED, 1,
                "WORKFLOW_TERMINATED must be published so the dashboard turns red");
    }

    @Test
    @DisplayName("a workflow parked on a timer is evicted promptly")
    void terminateParkedOnTimer_evictsPromptly() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("term-timer", "TerminateSleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("term-timer", WorkflowStatus.WAITING_TIMER);

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-timer", null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-timer")));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("term-timer").orElseThrow().status());
        assertFalse(workflow.pastSleep.get(), "the run must not continue past its sleep");
    }

    @Test
    @DisplayName("terminate with no reason still records who did it")
    void terminateWithoutReason_recordsTerminatedBy() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("term-noreason", "TerminateSleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("term-noreason", WorkflowStatus.WAITING_TIMER);

        executor.terminateWorkflow("term-noreason", null);

        var output = store.getInstance("term-noreason").orElseThrow().output();
        assertNotNull(output);
        assertTrue(output.get("reason").isNull(), "an absent reason stays absent, not invented");
        assertEquals("admin", output.get("terminatedBy").stringValue());
    }

    @Test
    @DisplayName("a park that registers just after the sweep is refused, so the run cannot re-park")
    void terminateRacingAPark_refusesTheLatePark() throws Exception {
        var workflow = new ReparkingWorkflow();
        executor.startWorkflow("term-repark", "ReparkingWorkflow", "default",
                null, workflow, ReparkingWorkflow.class.getMethod("run"));
        awaitStatus("term-repark", WorkflowStatus.WAITING_TIMER);

        // Let the workflow loop: it wakes and immediately parks again. Whichever
        // side of the sweep the next park lands on, the sticky poison stops it.
        executor.terminateWorkflow("term-repark", "no more sleeping");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-repark"),
                        "a re-parking workflow must still be stopped by the sticky poison"));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("term-repark").orElseThrow().status());
    }

    // ── A node that does not own the workflow ──────────────────────────

    @Test
    @DisplayName("terminate works on a node that is not running the workflow")
    void terminateFromANonOwningNode_marksTheRowAnyway() throws Exception {
        var workflow = new SleepingWorkflow();
        var owner = new WorkflowExecutor(store, null, messaging, null, serializer, "owner-node");
        try {
            owner.startWorkflow("term-remote", "TerminateSleepingWorkflow", "default",
                    null, workflow, SleepingWorkflow.class.getMethod("run"));
            awaitStatus("term-remote", WorkflowStatus.WAITING_TIMER);

            // This executor never ran it, so it has nothing local to evict —
            // the optimistic version, not the instance lock, is the arbiter.
            assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                    executor.terminateWorkflow("term-remote", "from elsewhere"));
            assertEquals(WorkflowStatus.TERMINATED,
                    store.getInstance("term-remote").orElseThrow().status());
        } finally {
            owner.shutdown();
        }
    }

    // ── Compensation in flight ─────────────────────────────────────────

    @Test
    @DisplayName("terminate during compensation stops the compensations that have not run")
    void terminateDuringCompensation_stopsRemainingCompensations() throws Exception {
        var workflow = new ParkingCompensationWorkflow();
        executor.startWorkflow("term-compensating", "ParkingCompensationWorkflow", "default",
                null, workflow, ParkingCompensationWorkflow.class.getMethod("run"));

        // The second (LIFO-first) compensation parks, so the run sits in
        // COMPENSATING with one compensation still pending.
        assertTrue(workflow.parkedInCompensation.await(5, TimeUnit.SECONDS),
                "the compensation must reach its park");

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-compensating", "stop unwinding"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-compensating")));
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("term-compensating").orElseThrow().status(),
                "terminate must win over the FAILED the compensation run was heading for");
        assertFalse(workflow.firstCompensationRan.get(),
                "the compensation that had not run yet must never run");
    }

    // ── In-flight activity ─────────────────────────────────────────────

    @Test
    @DisplayName("terminate mid-activity does not interrupt it, and the run stands down afterwards")
    void terminateMidActivity_letsTheActivityFinishThenStandsDown() throws Exception {
        var workflow = new BlockingWorkflow();
        executor.startWorkflow("term-activity", "BlockingWorkflow", "default",
                null, workflow, BlockingWorkflow.class.getMethod("run"));
        assertTrue(workflow.entered.await(5, TimeUnit.SECONDS), "the workflow must reach its block");

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-activity", "kill it"));
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("term-activity").orElseThrow().status());

        // Not interrupted: the body runs to its own end, exactly as it does
        // under shutdown. It is the final transition that stands down.
        workflow.release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-activity")));
        assertTrue(workflow.finished.get(), "the in-flight body must not be interrupted");
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("term-activity").orElseThrow().status(),
                "a body that finished after terminate must not overwrite TERMINATED");
    }

    @Test
    @DisplayName("terminate while a retried activity is parked propagates without being retried or wrapped")
    void terminateWithRetriedActivityParked_propagatesWithoutRetryOrWrap() throws Exception {
        // Mirrors WorkflowExecutorShutdownTest.shutdown_withRetriedActivityParked_
        // propagatesWithoutRetryOrWrap, reusing its fixtures: drives the FULL
        // activity proxy dispatch — Method.invoke() wrapping the park in
        // InvocationTargetException, ActivityInvocationHandler.invokeActivity's
        // cause-unwrap, and RetryExecutor.executeWithRetry — not just
        // RetryExecutor in isolation (see RetryExecutorTest for that).
        var invocations = new AtomicInteger();
        var activityImpl = new WorkflowExecutorShutdownTest.ParkingActivityImpl(
                invocations, WorkflowExecutorShutdownTest.RetryShutdownWorkflow.RESUME_SIGNAL);
        var proxy = new ActivityProxyFactory().createProxy(
                WorkflowExecutorShutdownTest.ParkingActivity.class, activityImpl, store, null, null,
                RetryPolicy.builder().maxAttempts(5).initialInterval(Duration.ofMillis(1))
                        .maxInterval(Duration.ofMillis(10)).backoffMultiplier(1.0).build(),
                Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new WorkflowExecutorShutdownTest.RetryShutdownWorkflow(proxy);
        var method = WorkflowExecutorShutdownTest.RetryShutdownWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("term-retry-activity", "RetryShutdownWorkflow", "default", "input",
                workflow, method);

        awaitStatus("term-retry-activity", WorkflowStatus.WAITING_SIGNAL);
        assertEquals(1, invocations.get(), "must not have been retried before terminate either");

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-retry-activity", "kill it mid-retry-park"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("term-retry-activity")));
        assertEquals(1, invocations.get(),
                "a terminate signal reaching a retried activity (maxAttempts=5) must not be retried");
        var instance = store.getInstance("term-retry-activity").orElseThrow();
        assertEquals(WorkflowStatus.TERMINATED, instance.status(),
                "must not be wrapped into ActivityExecutionException and recorded as this workflow's failure");
        assertTrue(events(instance.id(), EventType.ACTIVITY_FAILED).isEmpty(),
                "must not be recorded as an exhausted-retries activity failure");
        assertTrue(events(instance.id(), EventType.WORKFLOW_FAILED).isEmpty());
    }

    // ── Idempotency and validation ─────────────────────────────────────

    @Test
    @DisplayName("terminating an unknown workflow is an acknowledged no-op")
    void terminateUnknownWorkflow_isANoOp() {
        assertEquals(WorkflowExecutor.TerminateOutcome.NOT_FOUND,
                executor.terminateWorkflow("no-such-workflow", "nothing to do"));
        assertEquals(0, messaging.events.size(), "a no-op must publish nothing");
    }

    @Test
    @DisplayName("terminating an already-terminal workflow is an acknowledged no-op")
    void terminateTerminalWorkflow_isANoOp() throws Exception {
        var workflow = new ImmediateWorkflow();
        executor.startWorkflow("term-done", "ImmediateWorkflow", "default",
                "seed", workflow, ImmediateWorkflow.class.getMethod("run", String.class));
        awaitStatus("term-done", WorkflowStatus.COMPLETED);
        var completedAt = store.getInstance("term-done").orElseThrow().completedAt();

        assertEquals(WorkflowExecutor.TerminateOutcome.ALREADY_TERMINAL,
                executor.terminateWorkflow("term-done", "too late"));

        var instance = store.getInstance("term-done").orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                "a completed workflow must not be rewritten as TERMINATED");
        assertEquals(completedAt, instance.completedAt(), "the row must be untouched");
        assertTrue(lifecycleEvents(LifecycleEventType.WORKFLOW_TERMINATED).isEmpty());
    }

    @Test
    @DisplayName("a redelivered terminate converges instead of writing twice")
    void duplicateTerminate_converges() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("term-dup", "TerminateSleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("term-dup", WorkflowStatus.WAITING_TIMER);

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-dup", "first"));
        assertEquals(WorkflowExecutor.TerminateOutcome.ALREADY_TERMINAL,
                executor.terminateWorkflow("term-dup", "redelivered"),
                "at-least-once redelivery must stand down, not re-terminate");

        assertEquals("first", store.getInstance("term-dup").orElseThrow()
                        .output().get("reason").stringValue(),
                "the redelivery must not overwrite the original reason");
        awaitLifecycleCount(LifecycleEventType.WORKFLOW_TERMINATED, 1,
                "exactly one WORKFLOW_TERMINATED, however many times the command arrives");
    }

    @Test
    @DisplayName("a version conflict is retried against a fresh read, not surfaced")
    void terminateConvergesAcrossAVersionConflict() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("term-conflict", "TerminateSleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("term-conflict", WorkflowStatus.WAITING_TIMER);

        store.failNextUpdates(2);
        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("term-conflict", "persistent operator"),
                "a persistence conflict is not an outcome — it is retried");
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("term-conflict").orElseThrow().status());
    }

    @Test
    @DisplayName("a conflict that never clears propagates, so the transport can redeliver")
    void terminateWithExhaustedAttemptBudget_propagates() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("term-exhausted", "TerminateSleepingWorkflow", "default",
                null, workflow, SleepingWorkflow.class.getMethod("run"));
        awaitStatus("term-exhausted", WorkflowStatus.WAITING_TIMER);

        store.failNextUpdates(100);
        assertThrows(OptimisticLockException.class,
                () -> executor.terminateWorkflow("term-exhausted", "never lands"),
                "an unwritable terminate must not be silently acked");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(expected, store.getInstance(workflowId).orElseThrow().status()));
    }

    private List<WorkflowLifecycleEvent> lifecycleEvents(LifecycleEventType type) {
        return messaging.events.stream().filter(e -> e.eventType() == type).toList();
    }

    /**
     * Waits for a lifecycle event count, then holds it briefly to catch an
     * extra publish arriving late.
     *
     * <p>Lifecycle events are published off the calling thread (see
     * {@code LifecycleEventPublisher}), so asserting a count immediately after
     * {@code terminateWorkflow} returns races the publisher — and asserting
     * "exactly one" needs to survive a second event turning up a moment later,
     * which is precisely what the duplicate-command test is about.
     */
    private void awaitLifecycleCount(LifecycleEventType type, int expected, String message) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertEquals(expected, lifecycleEvents(type).size(), message));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(expected, lifecycleEvents(type).size(), message));
    }

    private List<EventType> events(java.util.UUID instanceId, EventType type) {
        return store.getEvents(instanceId).stream()
                .map(e -> e.eventType()).filter(t -> t == type).toList();
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** Parks on a signal with a compensation registered, so "no compensation ran" is provable. */
    @DurableWorkflow(name = "CompensatingAwaitWorkflow")
    public static class CompensatingAwaitWorkflow {

        final AtomicBoolean compensated = new AtomicBoolean();

        /** @return the signal payload, if the run ever gets that far */
        @WorkflowMethod
        @Saga
        public String run() {
            var ctx = WorkflowContext.current();
            ctx.addCompensation("undo", () -> compensated.set(true));
            return ctx.awaitSignal("approval", String.class, Duration.ofSeconds(30));
        }
    }

    /** Parks on a durable timer nothing ever fires. */
    @DurableWorkflow(name = "TerminateSleepingWorkflow")
    public static class SleepingWorkflow {

        final AtomicBoolean pastSleep = new AtomicBoolean();

        /** @return a fixed value, if the run ever gets that far */
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofSeconds(30));
            pastSleep.set(true);
            return "slept";
        }
    }

    /** Sleeps in a tight loop, so a terminate is very likely to race a fresh park. */
    @DurableWorkflow(name = "ReparkingWorkflow")
    public static class ReparkingWorkflow {

        /** @return never, in practice */
        @WorkflowMethod
        public String run() {
            var ctx = WorkflowContext.current();
            for (var i = 0; i < 10_000; i++) {
                ctx.sleep(Duration.ofMillis(1));
            }
            return "done";
        }
    }

    /**
     * Fails with two compensations registered, the LIFO-first of which parks —
     * leaving the run in {@code COMPENSATING} with one compensation pending.
     */
    @DurableWorkflow(name = "ParkingCompensationWorkflow")
    public static class ParkingCompensationWorkflow {

        final CountDownLatch parkedInCompensation = new CountDownLatch(1);
        final AtomicBoolean firstCompensationRan = new AtomicBoolean();

        /** @return never — always fails into compensation */
        @WorkflowMethod
        @Saga
        public String run() {
            var ctx = WorkflowContext.current();
            ctx.addCompensation("first", () -> firstCompensationRan.set(true));
            ctx.addCompensation("second", () -> {
                parkedInCompensation.countDown();
                ctx.sleep(Duration.ofSeconds(30));
            });
            throw new IllegalStateException("deliberate failure to reach compensation");
        }
    }

    /** Blocks in workflow code until released — stands in for an in-flight activity. */
    @DurableWorkflow(name = "BlockingWorkflow")
    public static class BlockingWorkflow {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();

        /**
         * @return a fixed value once released
         * @throws InterruptedException if the wait is interrupted
         */
        @WorkflowMethod
        public String run() throws InterruptedException {
            entered.countDown();
            release.await();
            finished.set(true);
            return "done";
        }
    }

    /** Completes immediately. */
    @DurableWorkflow(name = "ImmediateWorkflow")
    public static class ImmediateWorkflow {

        /**
         * @param input the seed
         * @return the seed, unchanged
         */
        @WorkflowMethod
        public String run(String input) {
            return input;
        }
    }

    // ── Recording messaging ────────────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {

        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        }
    }
}
