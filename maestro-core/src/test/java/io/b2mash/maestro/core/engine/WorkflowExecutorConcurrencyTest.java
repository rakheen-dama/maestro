package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowInstance;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

    /**
     * R49: at concurrency 1, a {@code parallel()} of two equal sleeps whose
     * timers fire in one batch must complete. Both branches wake while
     * another run holds the only slot, so both wait to reacquire; once it is
     * freed the run must take it once and let <em>both</em> branches carry
     * on. Before the per-run lock the second branch queued on the semaphore
     * for the permit its own run had just taken, the first finished its
     * branch, and the parent's join never returned.
     */
    @Test
    void parallelSleepsFiredTogetherCompleteAtConcurrencyOne() throws Exception {
        newExecutor(Map.of("orders", new TaskQueueLimits(1, 10)));
        var activities = proxy();

        start("wf-parallel", "orders", new ParallelSleepWorkflow());
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 2); // both branches asleep
        start("wf-blocker", "orders", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1); // the blocker holds the only slot

        var due = store.getDueTimers(Instant.now().plus(Duration.ofDays(1)), 10);
        assertEquals(2, due.size());
        for (var timer : due) { // one batch, as the timer poller fires it
            executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
        }
        // Both branches are awake and waiting for the slot the blocker holds.
        await().during(DURING).atMost(5, SECONDS).until(() -> executor.parkedCount() == 0);

        latch.countDown(); // the blocker finishes and frees the slot
        awaitStatus("wf-blocker", WorkflowStatus.COMPLETED);
        awaitStatus("wf-parallel", WorkflowStatus.COMPLETED);
        assertEquals(OptionalInt.of(1), executor.taskQueueGovernor().availableWorkflowSlots("orders"));
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

    @Test
    void aRunStillWaitingForAnActivitySlotAtShutdownStandsDownRecoverable() throws Exception {
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, Duration.ofMillis(200), Duration.ofMillis(200), EngineObserver.NOOP,
                Map.of("orders", new TaskQueueLimits(10, 1)));
        var activities = proxy();
        start("wf-holding", "orders", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        start("wf-waiting", "orders", new WorkWorkflow(activities));
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1); // queued on the activity slot

        executor.shutdown(); // wf-holding overruns the drain; wf-waiting still waits for the slot
        latch.countDown(); // wf-holding's activity returns and hands the slot over

        awaitStatus("wf-holding", WorkflowStatus.COMPLETED);
        await().atMost(5, SECONDS).until(() -> !executor.isRunning("wf-waiting"));
        assertEquals(1, started.get(), "an activity that got its slot only after shutdown began must not run");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-waiting").orElseThrow().status(),
                "the run stays recoverable, not failed");
        assertEquals(OptionalInt.of(1), executor.taskQueueGovernor().availableActivitySlots("orders"),
                "the slot it took is given back");
    }

    // ── default bound for unconfigured queues (maestro.worker.default-*) ──

    @Test
    void anUnconfiguredQueueIsBoundedByTheDefaultLimits() throws Exception {
        newExecutor(Map.of(), new TaskQueueLimits(2, 10));
        var activities = proxy();
        for (var i = 0; i < 3; i++) {
            start("wf-" + i, "other", new WorkWorkflow(activities));
        }

        await().atMost(5, SECONDS).until(() -> started.get() >= 2);
        assertEquals(OptionalInt.of(0), executor.taskQueueGovernor().availableWorkflowSlots("other"));
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 2); // the third waits for a slot

        latch.countDown();
        for (var i = 0; i < 3; i++) {
            awaitStatus("wf-" + i, WorkflowStatus.COMPLETED);
        }
        assertEquals(3, started.get());
        assertEquals(OptionalInt.of(2), executor.taskQueueGovernor().availableWorkflowSlots("other"));
    }

    /**
     * A {@code parallel()} with more branches than the queue has activity
     * slots must not deadlock on itself: the branches queue for the slots and
     * run at most {@code activityConcurrency} at a time, while the run holds
     * its one workflow slot throughout.
     */
    @Test
    void parallelBranchesBeyondTheDefaultActivityBoundQueueAndComplete() throws Exception {
        newExecutor(Map.of(), new TaskQueueLimits(1, 2));
        var tracking = new TrackingActivitiesImpl();
        var activities = new ActivityProxyFactory().createProxy(
                TrackingActivities.class, tracking, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, EngineObserver.NOOP,
                executor.taskQueueGovernor());

        start("wf-fan-out", "fan-out", new ParallelActivitiesWorkflow(activities, 5));

        awaitStatus("wf-fan-out", WorkflowStatus.COMPLETED);
        assertEquals(5, tracking.calls.get());
        assertEquals(2, tracking.maxConcurrent.get(), "at most the default activity bound executes at once");
        assertEquals(OptionalInt.of(2), executor.taskQueueGovernor().availableActivitySlots("fan-out"));
        assertEquals(OptionalInt.of(1), executor.taskQueueGovernor().availableWorkflowSlots("fan-out"));
    }

    /**
     * Recovery launches every adopted run on its own virtual thread and the
     * run takes its slot there, so a recovery backlog larger than the default
     * bound never blocks the recovering thread (at startup: the
     * {@code ApplicationRunner}) — the runs beyond the bound queue on their
     * own threads instead (Issue 30 is that they still hold their instance
     * lock while they queue).
     */
    @Test
    void recoveryOfMoreRunsThanTheDefaultBoundReturnsWithoutWaitingForSlots() throws Exception {
        newExecutor(Map.of(), new TaskQueueLimits(1, 1));
        var activities = proxy();
        for (var i = 0; i < 5; i++) {
            seedRecoverable("wf-" + i, "orders");
        }
        var registrations = Map.of("WorkWorkflow", new WorkflowRegistration(
                "WorkWorkflow", "orders", () -> new WorkWorkflow(activities),
                WorkWorkflow.class.getMethod("run")));

        var recovered = CompletableFuture.supplyAsync(() -> executor.recoverWorkflows(registrations),
                Executors.newVirtualThreadPerTaskExecutor());

        assertEquals(5, recovered.get(5, SECONDS), "every run was adopted without waiting for a slot");
        await().atMost(5, SECONDS).until(() -> started.get() >= 1);
        assertEquals(OptionalInt.of(0), executor.taskQueueGovernor().availableWorkflowSlots("orders"));
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1); // the other four queue

        latch.countDown();
        for (var i = 0; i < 5; i++) {
            awaitStatus("wf-" + i, WorkflowStatus.COMPLETED);
        }
        assertEquals(5, started.get());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void newExecutor(Map<String, TaskQueueLimits> limits, TaskQueueLimits defaults) {
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, Duration.ofSeconds(5), Duration.ofMillis(200), EngineObserver.NOOP, limits, defaults);
    }

    private void seedRecoverable(String workflowId, String taskQueue) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("WorkWorkflow")
                .taskQueue(taskQueue)
                .status(WorkflowStatus.RUNNING)
                .serviceName("node-a")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

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

    /** Records how many executions overlap. */
    @Activity(name = "tracking")
    public interface TrackingActivities {
        /** @param branch the calling branch's index @return the index */
        int track(int branch);
    }

    private static final class TrackingActivitiesImpl implements TrackingActivities {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public int track(int branch) {
            calls.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200); // long enough for every branch admitted to overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                concurrent.decrementAndGet();
            }
            return branch;
        }
    }

    /** A {@code parallel()} of {@code branches} branches, each one activity call. */
    @DurableWorkflow(name = "ParallelActivitiesWorkflow")
    public static final class ParallelActivitiesWorkflow {
        private final TrackingActivities activities;
        private final int branches;

        ParallelActivitiesWorkflow(TrackingActivities activities, int branches) {
            this.activities = activities;
            this.branches = branches;
        }

        /** @return the branch results, joined */
        @WorkflowMethod
        public String run() {
            var calls = new ArrayList<Callable<Integer>>();
            for (var i = 0; i < branches; i++) {
                var branch = i;
                calls.add(() -> activities.track(branch));
            }
            return WorkflowContext.current().parallel(calls).toString();
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

    /** Two {@code parallel()} branches, each sleeping the same duration. */
    @DurableWorkflow(name = "ParallelSleepWorkflow")
    public static final class ParallelSleepWorkflow {
        /** @return the branch results, once both timers fired */
        @WorkflowMethod
        public String run() {
            List<Callable<String>> branches = List.of(
                    () -> {
                        WorkflowContext.current().sleep(Duration.ofHours(1));
                        return "a";
                    },
                    () -> {
                        WorkflowContext.current().sleep(Duration.ofHours(1));
                        return "b";
                    });
            return String.join(",", WorkflowContext.current().parallel(branches));
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
