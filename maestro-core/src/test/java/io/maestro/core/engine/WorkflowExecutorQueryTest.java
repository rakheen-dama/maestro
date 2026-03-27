package io.maestro.core.engine;

import io.maestro.core.annotation.QueryMethod;
import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.QueryNotDefinedException;
import io.maestro.core.exception.WorkflowAlreadyExistsException;
import io.maestro.core.exception.WorkflowNotFoundException;
import io.maestro.core.exception.WorkflowNotQueryableException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.TimerStatus;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowInstance;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowStatus;
import io.maestro.core.model.WorkflowTimer;
import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the query dispatch system in {@link WorkflowExecutor}.
 */
class WorkflowExecutorQueryTest {

    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        // null = no distributed lock provider needed for single-node tests
        executor = new WorkflowExecutor(store, null, new NoOpMessaging(), serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── Query a parked (waiting) workflow ──────────────────────────────

    @Test
    @DisplayName("Query a workflow parked on awaitSignal → returns current state")
    void queryParkedWorkflow() throws Exception {
        var waitingLatch = new CountDownLatch(1);
        var workflow = new QueryableWorkflow(waitingLatch);
        var method = QueryableWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("QueryableWorkflow", QueryableWorkflow.class);
        executor.startWorkflow("order-q1", "QueryableWorkflow", "default",
                "processing", workflow, method);

        // Wait for workflow to reach awaitSignal and park
        assertTrue(waitingLatch.await(5, TimeUnit.SECONDS), "Workflow should reach await point");

        // Query the parked workflow
        var status = executor.queryWorkflow("order-q1", "getStatus", null, String.class);
        assertEquals("waiting-for-signal", status);
    }

    // ── Query an active (running) workflow ─────────────────────────────

    @Test
    @DisplayName("Query a workflow that is actively running → returns current state")
    void queryActiveWorkflow() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new BlockingQueryableWorkflow(startedLatch, blockLatch);
        var method = BlockingQueryableWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("BlockingQueryableWorkflow", BlockingQueryableWorkflow.class);
        executor.startWorkflow("order-q2", "BlockingQueryableWorkflow", "default",
                "active", workflow, method);

        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
            var step = executor.queryWorkflow("order-q2", "getCurrentStep", null, String.class);
            assertEquals("step-1", step);
        } finally {
            blockLatch.countDown();
        }
    }

    // ── Query with argument ───────────────────────────────────────────

    @Test
    @DisplayName("Query with argument → argument passed to query method")
    void queryWithArgument() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new WorkflowWithArgQuery(startedLatch, blockLatch);
        var method = WorkflowWithArgQuery.class.getMethod("run", String.class);

        executor.registerQueries("WorkflowWithArgQuery", WorkflowWithArgQuery.class);
        executor.startWorkflow("order-q3", "WorkflowWithArgQuery", "default",
                "input", workflow, method);

        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
            var result = executor.queryWorkflow("order-q3", "getItemByIndex", 2, String.class);
            assertEquals("item-2", result);
        } finally {
            blockLatch.countDown();
        }
    }

    // ── Query with custom name ──────���─────────────────────────────────

    @Test
    @DisplayName("Query using custom @QueryMethod(name) → dispatches correctly")
    void queryWithCustomName() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new WorkflowWithCustomQueryName(startedLatch, blockLatch);
        var method = WorkflowWithCustomQueryName.class.getMethod("run", String.class);

        executor.registerQueries("CustomNameWorkflow", WorkflowWithCustomQueryName.class);
        executor.startWorkflow("order-q4", "CustomNameWorkflow", "default",
                "input", workflow, method);

        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
            var count = executor.queryWorkflow("order-q4", "progress", null, Integer.class);
            assertEquals(42, count);
        } finally {
            blockLatch.countDown();
        }
    }

    // ── Error cases ────��──────────────────────────────────────────────

    @Test
    @DisplayName("Query non-existent workflow → WorkflowNotFoundException")
    void queryNonExistentWorkflow() {
        executor.registerQueries("SomeWorkflow", BlockingQueryableWorkflow.class);

        assertThrows(WorkflowNotFoundException.class, () ->
                executor.queryWorkflow("does-not-exist", "getCurrentStep", null, String.class));
    }

    @Test
    @DisplayName("Query workflow not in-memory → WorkflowNotQueryableException")
    void queryWorkflowNotInMemory() {
        // Create a completed workflow in the store
        var instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("completed-wf")
                .runId(UUID.randomUUID())
                .workflowType("SomeWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.COMPLETED)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.createInstance(instance);

        var ex = assertThrows(WorkflowNotQueryableException.class, () ->
                executor.queryWorkflow("completed-wf", "getStatus", null, String.class));
        assertEquals(WorkflowStatus.COMPLETED, ex.status());
    }

    @Test
    @DisplayName("Query undefined query name → QueryNotDefinedException")
    void queryUndefinedQueryName() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new BlockingQueryableWorkflow(startedLatch, blockLatch);
        var method = BlockingQueryableWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("BlockingQueryableWorkflow", BlockingQueryableWorkflow.class);
        executor.startWorkflow("order-q5", "BlockingQueryableWorkflow", "default",
                "input", workflow, method);

        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
            var ex = assertThrows(QueryNotDefinedException.class, () ->
                    executor.queryWorkflow("order-q5", "nonExistentQuery", null, String.class));
            assertEquals("nonExistentQuery", ex.queryName());
            assertEquals("BlockingQueryableWorkflow", ex.workflowType());
        } finally {
            blockLatch.countDown();
        }
    }

    @Test
    @DisplayName("Query after workflow completes → WorkflowNotQueryableException")
    void queryAfterCompletion() throws Exception {
        var completedLatch = new CountDownLatch(1);
        var workflow = new FastQueryableWorkflow(completedLatch);
        var method = FastQueryableWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("FastQueryableWorkflow", FastQueryableWorkflow.class);
        executor.startWorkflow("order-q6", "FastQueryableWorkflow", "default",
                "input", workflow, method);

        // Wait for workflow to complete and be removed from runningWorkflows
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> !executor.isRunning("order-q6"));

        assertThrows(WorkflowNotQueryableException.class, () ->
                executor.queryWorkflow("order-q6", "getStatus", null, String.class));
    }

    // ── Test workflow implementations ──────────────────────────────────

    /**
     * Workflow that parks on awaitSignal, allowing queries while parked.
     * Uses volatile fields for thread-safe reads from the query thread.
     */
    public static class QueryableWorkflow {
        private final CountDownLatch waitingLatch;
        private volatile String status = "init";

        public QueryableWorkflow(CountDownLatch waitingLatch) {
            this.waitingLatch = waitingLatch;
        }

        public String run(String input) {
            var workflow = WorkflowContext.current();
            status = "waiting-for-signal";
            waitingLatch.countDown();
            workflow.awaitSignal("some.signal", String.class, Duration.ofSeconds(30));
            status = "completed";
            return status;
        }

        @QueryMethod
        public String getStatus() {
            return status;
        }
    }

    /**
     * Workflow that blocks on a latch (simulating active work), queryable.
     */
    public static class BlockingQueryableWorkflow {
        private final CountDownLatch startedLatch;
        private final CountDownLatch blockLatch;
        private volatile String currentStep = "init";

        public BlockingQueryableWorkflow(CountDownLatch startedLatch, CountDownLatch blockLatch) {
            this.startedLatch = startedLatch;
            this.blockLatch = blockLatch;
        }

        public String run(String input) {
            currentStep = "step-1";
            startedLatch.countDown();
            try {
                blockLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            currentStep = "done";
            return "completed";
        }

        @QueryMethod
        public String getCurrentStep() {
            return currentStep;
        }
    }

    /**
     * Workflow with a query method that accepts an argument.
     */
    public static class WorkflowWithArgQuery {
        private final CountDownLatch startedLatch;
        private final CountDownLatch blockLatch;

        public WorkflowWithArgQuery(CountDownLatch startedLatch, CountDownLatch blockLatch) {
            this.startedLatch = startedLatch;
            this.blockLatch = blockLatch;
        }

        public String run(String input) {
            startedLatch.countDown();
            try {
                blockLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }

        @QueryMethod
        public String getItemByIndex(int index) {
            return "item-" + index;
        }
    }

    /**
     * Workflow with a custom query name.
     */
    public static class WorkflowWithCustomQueryName {
        private final CountDownLatch startedLatch;
        private final CountDownLatch blockLatch;

        public WorkflowWithCustomQueryName(CountDownLatch startedLatch, CountDownLatch blockLatch) {
            this.startedLatch = startedLatch;
            this.blockLatch = blockLatch;
        }

        public String run(String input) {
            startedLatch.countDown();
            try {
                blockLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }

        @QueryMethod(name = "progress")
        public int getItemsProcessed() {
            return 42;
        }
    }

    /**
     * Workflow that completes immediately (for testing post-completion queries).
     */
    public static class FastQueryableWorkflow {
        private final CountDownLatch completedLatch;

        public FastQueryableWorkflow(CountDownLatch completedLatch) {
            this.completedLatch = completedLatch;
        }

        public String run(String input) {
            completedLatch.countDown();
            return "done";
        }

        @QueryMethod
        public String getStatus() {
            return "should-not-be-reachable";
        }
    }

    // ── In-memory WorkflowStore ────────────────────────────────────────

    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, WorkflowInstance> instancesById = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            instancesById.put(instance.id(), instance);
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream()
                    .filter(i -> i.status().isActive()).toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
            instancesById.put(instance.id(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) { events.add(event); }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId)
                            && e.sequenceNumber() == sequenceNumber)
                    .findFirst();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) { signals.add(signal); }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId)
                            && s.signalName().equals(signalName) && !s.consumed())
                    .toList();
        }

        @Override
        public void markSignalConsumed(UUID signalId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId)) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return;
                }
            }
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.workflowId().equals(workflowId) && s.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }

        @Override
        public void saveTimer(WorkflowTimer timer) { timers.add(timer); }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING && !t.fireAt().isAfter(now))
                    .limit(batchSize).toList();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.FIRED, t.createdAt()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return;
                }
            }
        }
    }

    private static class NoOpMessaging implements WorkflowMessaging {
        @Override public void publishTask(String taskQueue, TaskMessage message) {}
        @Override public void publishSignal(String serviceName, SignalMessage message) {}
        @Override public void publishLifecycleEvent(WorkflowLifecycleEvent event) {}
        @Override public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}
        @Override public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
