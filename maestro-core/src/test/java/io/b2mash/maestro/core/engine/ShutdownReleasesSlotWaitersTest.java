package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 29 (WP5 Task 2): a run waiting for a task-queue slot when the executor
 * shuts down — a fresh start, a {@code parallel()} branch, a compensation, a
 * park's reacquire — leaves before {@code shutdown()} returns. It stands down
 * recoverable, writes nothing, and hands its instance lock back at once
 * instead of leaving it to the TTL. Nothing is interrupted: a run executing
 * an activity overruns the drain and keeps going.
 *
 * <p>Every activity here is {@link BlockingActivities#work()}: it counts its
 * live entry in {@code started} and blocks on {@code latch}.
 */
@DisplayName("Shutdown releases runs queued for a task-queue slot")
class ShutdownReleasesSlotWaitersTest {

    private static final Duration DURING = Duration.ofMillis(300);

    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger undone = new AtomicInteger();
    private final CountDownLatch latch = new CountDownLatch(1);
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final MapLock lock = new MapLock();

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
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("a run queued for a workflow slot leaves at shutdown and releases its instance lock at once (Issue 29)")
    void aRunQueuedForAWorkflowSlotLeavesAndReleasesItsLock() throws Exception {
        newExecutor(new TaskQueueLimits(1, 10));
        var activities = proxy(BlockingActivities.class, new BlockingActivitiesImpl());
        start("wf-running", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        start("wf-queued", new WorkWorkflow(activities));
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1);

        executor.shutdown();

        assertAll(
                () -> assertFalse(executor.isRunning("wf-queued"), "the queued run left before shutdown() returned"),
                () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-queued"),
                        "its lock was released, not left to the TTL"),
                () -> assertEquals(WorkflowStatus.RUNNING, status("wf-queued")),
                () -> assertEquals(1, started.get(), "the queued run never started"),
                () -> assertTrue(executor.isRunning("wf-running"),
                        "the holder overruns the drain and is not interrupted"),
                () -> assertEquals(List.of(AbandonReason.SHUTDOWN), abandonReasonsOf("wf-queued")));
    }

    @Test
    @DisplayName("a run queued for an activity slot leaves at shutdown and releases its instance lock at once (Issue 29)")
    void aRunQueuedForAnActivitySlotLeavesAndReleasesItsLock() throws Exception {
        newExecutor(new TaskQueueLimits(10, 1));
        var activities = proxy(BlockingActivities.class, new BlockingActivitiesImpl());
        start("wf-holding", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        start("wf-waiting", new WorkWorkflow(activities));
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1);

        executor.shutdown();

        assertAll(
                () -> assertFalse(executor.isRunning("wf-waiting"), "the queued run left before shutdown() returned"),
                () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-waiting"),
                        "its lock was released, not left to the TTL"),
                () -> assertEquals(WorkflowStatus.RUNNING, status("wf-waiting")),
                () -> assertEquals(1, started.get(), "the queued activity never started"),
                () -> assertTrue(executor.isRunning("wf-holding"),
                        "the holder overruns the drain and is not interrupted"),
                () -> assertEquals(List.of(AbandonReason.SHUTDOWN), abandonReasonsOf("wf-waiting")));
    }

    @Test
    @DisplayName("parallel() branches queued for an activity slot leave at shutdown and the run is not failed")
    void queuedParallelBranchesLeaveAndTheRunIsNotFailed() throws Exception {
        newExecutor(new TaskQueueLimits(10, 1));
        var activities = proxy(BlockingActivities.class, new BlockingActivitiesImpl());
        var left = new AtomicInteger();
        start("wf-parallel", new ParallelWorkWorkflow(activities, 3, left));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        await().during(DURING).atMost(5, SECONDS).until(() -> started.get() == 1); // two branches queue

        executor.shutdown();

        assertEquals(2, left.get(), "both queued branches left before shutdown() returned");
        latch.countDown(); // the holder branch finishes
        awaitNotRunning("wf-parallel");
        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, status("wf-parallel")),
                () -> assertEquals(0, count("wf-parallel", EventType.ACTIVITY_FAILED)),
                () -> assertEquals(0, count("wf-parallel", EventType.WORKFLOW_FAILED)),
                () -> assertTrue(observer.failed().isEmpty(), observer.failed().toString()));
    }

    @Test
    @DisplayName("a compensation queued for an activity slot leaves at shutdown; the run stays COMPENSATING")
    void aCompensationQueuedForAnActivitySlotLeavesTheRunCompensating() throws Exception {
        newExecutor(new TaskQueueLimits(10, 1));
        var activities = proxy(BlockingActivities.class, new BlockingActivitiesImpl());
        var steps = proxy(SagaSteps.class, new SagaStepsImpl());
        start("wf-holding", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        start("wf-saga", new CompensatingWorkflow(steps));
        await().atMost(5, SECONDS).until(() -> status("wf-saga") == WorkflowStatus.COMPENSATING);
        await().during(DURING).atMost(5, SECONDS).until(() -> executor.isRunning("wf-saga") && undone.get() == 0);

        executor.shutdown();

        assertAll(
                () -> assertFalse(executor.isRunning("wf-saga"),
                        "the queued compensation left before shutdown() returned"),
                () -> assertEquals(WorkflowStatus.COMPENSATING, status("wf-saga")),
                () -> assertEquals(0, undone.get()),
                () -> assertEquals(0, count("wf-saga", EventType.COMPENSATION_STEP_FAILED)),
                () -> assertEquals(0, count("wf-saga", EventType.WORKFLOW_FAILED)),
                () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-saga")));
    }

    @Test
    @DisplayName("a park woken into a full queue leaves at shutdown with its signal unconsumed")
    void aParkWokenIntoAFullQueueLeavesWithItsSignalUnconsumed() throws Exception {
        newExecutor(new TaskQueueLimits(1, 10));
        var activities = proxy(BlockingActivities.class, new BlockingActivitiesImpl());
        start("wf-a", new AwaitThenWorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 1);
        start("wf-b", new WorkWorkflow(activities));
        await().atMost(5, SECONDS).until(() -> started.get() == 1); // wf-b took wf-a's slot
        executor.deliverSignal("wf-a", "go", "now");
        await().during(DURING).atMost(5, SECONDS).until(() -> executor.isRunning("wf-a") && started.get() == 1);

        executor.shutdown();

        assertAll(
                () -> assertFalse(executor.isRunning("wf-a"), "the woken run left before shutdown() returned"),
                () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, status("wf-a")),
                () -> assertEquals(1, store.getUnconsumedSignals("wf-a", "go").size(),
                        "the signal stays for recovery"));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void newExecutor(TaskQueueLimits limits) {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .distributedLock(lock)
                .observer(observer)
                .shutdownTimeout(Duration.ofMillis(300))
                .wakeRecheckInterval(Duration.ofMillis(200))
                .taskQueueLimits(Map.of("orders", limits))
                .build();
    }

    private <T> T proxy(Class<T> type, T impl) {
        return new ActivityProxyFactory().createProxy(
                type, impl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer,
                executor.taskQueueGovernor());
    }

    private void start(String workflowId, Object workflow) throws Exception {
        var method = workflow.getClass().getMethod("run");
        executor.startWorkflow(workflowId, workflow.getClass().getSimpleName(), "orders", null,
                () -> workflow, method);
    }

    private WorkflowStatus status(String workflowId) {
        return store.getInstance(workflowId).orElseThrow().status();
    }

    private long count(String workflowId, EventType type) {
        var instance = store.getInstance(workflowId).orElseThrow();
        return store.getEvents(instance.id()).stream().filter(e -> e.eventType() == type).count();
    }

    private List<AbandonReason> abandonReasonsOf(String workflowId) {
        return observer.abandoned().stream()
                .filter(a -> a.info().workflowId().equals(workflowId))
                .map(RecordingEngineObserver.AbandonCall::reason)
                .toList();
    }

    private void awaitNotRunning(String workflowId) {
        await().atMost(5, SECONDS).until(() -> !executor.isRunning(workflowId));
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

    /** The saga's compensation step. */
    @Activity(name = "saga-steps")
    public interface SagaSteps {
        /** Undoes the (notional) forward step. */
        void undo();
    }

    /** Counts each live {@code undo}. */
    private final class SagaStepsImpl implements SagaSteps {
        @Override
        public void undo() {
            undone.incrementAndGet();
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

    /** A {@code parallel()} of {@code n} branches, each one {@code work()}; every branch counts its exit in {@code left}. */
    @DurableWorkflow(name = "ParallelWorkWorkflow")
    public static final class ParallelWorkWorkflow {
        private final BlockingActivities activities;
        private final int n;
        private final AtomicInteger left;

        ParallelWorkWorkflow(BlockingActivities activities, int n, AtomicInteger left) {
            this.activities = activities;
            this.n = n;
            this.left = left;
        }

        /** @return the branch results, joined */
        @WorkflowMethod
        public String run() {
            var calls = new ArrayList<Callable<String>>();
            for (var i = 0; i < n; i++) {
                calls.add(() -> {
                    try {
                        return activities.work();
                    } finally {
                        left.incrementAndGet();
                    }
                });
            }
            return String.join(",", WorkflowContext.current().parallel(calls));
        }
    }

    /**
     * Registers a compensation that needs an activity slot, then fails, so
     * the compensation runs — and, with the queue's only activity slot held
     * elsewhere, queues for it.
     */
    @DurableWorkflow(name = "CompensatingWorkflow")
    public static final class CompensatingWorkflow {
        private final SagaSteps steps;

        CompensatingWorkflow(SagaSteps steps) {
            this.steps = steps;
        }

        /** @return never returns */
        @Saga
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().addCompensation("undo", steps::undo);
            throw new IllegalStateException("Intentional failure to trigger compensation");
        }
    }

    /** Minimal in-memory {@link DistributedLock}; thread-safe. */
    private static final class MapLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        boolean isHeld(String key) {
            return locks.containsKey(key);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }
}
