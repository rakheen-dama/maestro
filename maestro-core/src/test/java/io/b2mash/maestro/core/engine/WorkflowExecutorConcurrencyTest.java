package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * F8 / Issue 25: {@code maestro.worker.task-queues[].concurrency} and
 * {@code activity-concurrency} bound execution end to end through the
 * executor — not merely bind.
 *
 * <p>Every workflow here calls {@link BlockingActivities#work()}, which counts
 * its live entry in {@code started} and then blocks on {@code latch}, so
 * {@code started} is exactly the number of runs (or activities) that got past
 * the slot under test.
 */
@DisplayName("Per-task-queue workflow and activity concurrency")
class WorkflowExecutorConcurrencyTest {

    private static final Duration DURING = Duration.ofMillis(500);

    private final AtomicInteger started = new AtomicInteger();
    private final CountDownLatch latch = new CountDownLatch(1);

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        latch.countDown();
        if (executor != null) executor.shutdown();
    }

    @Test
    void workflowConcurrencyBoundsSimultaneousRuns() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(2, 10)));
        var activities = proxy();
        for (var i = 0; i < 3; i++) {
            start("wf-" + i, "orders", new WorkWorkflow(activities));
        }

        await().atMost(5, SECONDS).until(() -> started.get() == 2);
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 2); // the third waits for a slot
        assertEquals(OptionalInt.of(0), executor.taskQueueGovernor().availableWorkflowSlots("orders"));

        latch.countDown();
        await().atMost(5, SECONDS).until(() -> started.get() == 3);
        for (var i = 0; i < 3; i++) {
            awaitStatus("wf-" + i, WorkflowStatus.COMPLETED);
        }
        assertEquals(OptionalInt.of(2), executor.taskQueueGovernor().availableWorkflowSlots("orders"),
                "every finished run gave its slot back");
    }

    @Test
    void activityConcurrencyBoundsLiveActivities() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(10, 1)));
        var activities = proxy();
        start("wf-a", "orders", new WorkWorkflow(activities));
        start("wf-b", "orders", new WorkWorkflow(activities));

        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1);

        latch.countDown();
        await().atMost(5, SECONDS).until(() -> started.get() == 2);
        awaitStatus("wf-a", WorkflowStatus.COMPLETED);
        awaitStatus("wf-b", WorkflowStatus.COMPLETED);
        assertEquals(OptionalInt.of(1), executor.taskQueueGovernor().availableActivitySlots("orders"));
    }

    @Test
    void parkedWorkflowReleasesItsSlot() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(1, 10)));
        var activities = proxy();

        // A parks first: its slot must pass to B while it waits.
        start("wf-a", "orders", new AwaitThenWorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 1);
        start("wf-b", "orders", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1); // B got A's slot and is working

        // A is woken while B holds the only slot: A must wait for it.
        executor.deliverSignal("wf-a", "go", "now");
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1);
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-b").orElseThrow().status());

        latch.countDown();
        awaitStatus("wf-b", WorkflowStatus.COMPLETED);
        awaitStatus("wf-a", WorkflowStatus.COMPLETED);
        assertEquals(2, started.get());
        assertEquals(OptionalInt.of(1), executor.taskQueueGovernor().availableWorkflowSlots("orders"));
    }

    @Test
    void aSleepingWorkflowReleasesItsSlot() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(1, 10)));
        var activities = proxy();

        start("wf-sleeper", "orders", new SleepWorkflow());
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 1);
        start("wf-b", "orders", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1); // B got the sleeper's slot

        latch.countDown();
        awaitStatus("wf-b", WorkflowStatus.COMPLETED);
        assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance("wf-sleeper").orElseThrow().status());
    }

    @Test
    void unconfiguredQueueIsUnboundedEndToEnd() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(1, 1)));
        var activities = proxy();
        for (var i = 0; i < 5; i++) {
            start("wf-" + i, "other", new WorkWorkflow(activities));
        }
        await().atMost(5, SECONDS).until(() -> started.get() == 5);
    }

    @Test
    void aRunStillWaitingForASlotAtShutdownStandsDownRecoverable() throws Exception {
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, Duration.ofMillis(200), Duration.ofMillis(200), EngineObserver.NOOP,
                Map.of("orders", new TaskQueueLimits(1, 10)));
        var activities = proxy();
        start("wf-running", "orders", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        start("wf-queued", "orders", new WorkWorkflow(activities));

        executor.shutdown(); // wf-running overruns the drain; wf-queued is still waiting
        latch.countDown(); // wf-running finishes and hands its slot over

        awaitStatus("wf-running", WorkflowStatus.COMPLETED);
        await().atMost(5, SECONDS).until(() -> !executor.isRunning("wf-queued"));
        assertEquals(1, started.get(), "a run that got its slot only after shutdown began must not start");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-queued").orElseThrow().status(),
                "it stays recoverable, not failed");
        assertFalse(executor.isRunning("wf-queued"));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void newExecutor(Map<String, TaskQueueLimits> limits) {
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, Duration.ofSeconds(5), Duration.ofMillis(200), EngineObserver.NOOP, limits);
    }

    private BlockingActivities proxy() {
        return new ActivityProxyFactory().createProxy(
                BlockingActivities.class, new BlockingActivitiesImpl(), store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, EngineObserver.NOOP,
                executor.taskQueueGovernor());
    }

    private void start(String workflowId, String taskQueue, Object workflow) throws Exception {
        Method method = workflow.getClass().getMethod("run");
        executor.startWorkflow(workflowId, workflow.getClass().getSimpleName(), taskQueue, null,
                () -> workflow, method);
    }

    private void awaitStatus(String workflowId, WorkflowStatus status) {
        await().atMost(5, SECONDS).until(() ->
                store.getInstance(workflowId).orElseThrow().status() == status);
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** The activity under test. */
    @Activity(name = "blocking")
    public interface BlockingActivities {
        /** @return a marker once the latch opens */
        String work();
    }

    /** Counts each live entry, then blocks until the test opens the latch. */
    private final class BlockingActivitiesImpl implements BlockingActivities {
        @Override
        public String work() {
            started.incrementAndGet();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "done";
        }
    }

    /** One activity, then done. */
    @DurableWorkflow(name = "WorkWorkflow")
    public static final class WorkWorkflow {
        private final BlockingActivities activities;

        WorkWorkflow(BlockingActivities activities) {
            this.activities = activities;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return activities.work();
        }
    }

    /** Sleeps far longer than any test runs. */
    @DurableWorkflow(name = "SleepWorkflow")
    public static final class SleepWorkflow {
        /** @return a marker, if the run ever wakes */
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofHours(1));
            return "woke";
        }
    }

    /** Parks on a signal, then runs the activity. */
    @DurableWorkflow(name = "AwaitThenWorkWorkflow")
    public static final class AwaitThenWorkWorkflow {
        private final BlockingActivities activities;

        AwaitThenWorkWorkflow(BlockingActivities activities) {
            this.activities = activities;
        }

        /** @return the signal payload plus the activity's result */
        @WorkflowMethod
        public String run() {
            var go = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
            return go + activities.work();
        }
    }
}
