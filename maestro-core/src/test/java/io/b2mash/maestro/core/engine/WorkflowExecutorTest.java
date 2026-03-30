package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowExecutor}.
 *
 * <p>Uses an in-memory {@link WorkflowStore} to verify the full lifecycle
 * of workflow execution, signal delivery, recovery, and shutdown.
 */
class WorkflowExecutorTest {

    private InMemoryWorkflowStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── Start and complete ─────────────────────────────────────────────

    @Test
    @DisplayName("Start workflow → run to completion → status COMPLETED")
    void startWorkflowCompletesSuccessfully() throws Exception {
        var latch = new CountDownLatch(1);

        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow(
                "order-1", "SimpleWorkflow", "default",
                "hello", workflow, method);

        assertNotNull(instanceId);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Workflow should complete within timeout");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-1");
            assertTrue(instance.isPresent(), "Instance should exist in store");
            assertEquals(WorkflowStatus.COMPLETED, instance.get().status());
            assertFalse(executor.isRunning("order-1"), "Workflow should no longer be running");
        });
    }

    @Test
    @DisplayName("Start workflow with no input → completes with null input")
    void startWorkflowNoInput() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new NoInputWorkflow(latch);
        var method = NoInputWorkflow.class.getMethod("run");

        executor.startWorkflow("order-2", "NoInputWorkflow", "default",
                null, workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-2");
            assertTrue(instance.isPresent());
            assertEquals(WorkflowStatus.COMPLETED, instance.get().status());
        });
    }

    // ── Failure handling ───────────────────────────────────────────────

    @Test
    @DisplayName("Workflow exception → status FAILED")
    void workflowExceptionResultsInFailed() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new FailingWorkflow(latch);
        var method = FailingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-3", "FailingWorkflow", "default",
                "input", workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-3");
            assertTrue(instance.isPresent());
            assertEquals(WorkflowStatus.FAILED, instance.get().status());
        });
    }

    // ── Signal delivery ────────────────────────────────────────────────

    @Test
    @DisplayName("Deliver signal → unparks waiting workflow")
    void deliverSignalUnparksWorkflow() throws Exception {
        var completedLatch = new CountDownLatch(1);
        var waitingLatch = new CountDownLatch(1);
        var workflow = new SignalWorkflow(waitingLatch, completedLatch);
        var method = SignalWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-4", "SignalWorkflow", "default",
                "input", workflow, method);

        // Wait for workflow to reach awaitSignal and park
        assertTrue(waitingLatch.await(5, TimeUnit.SECONDS), "Workflow should reach await point");
        await().atMost(Duration.ofSeconds(2)).until(() ->
                !store.getUnconsumedSignals("order-4", "payment.result").isEmpty()
                || executor.isRunning("order-4"));

        // Deliver the signal
        executor.deliverSignal("order-4", "payment.result", "paid");

        // Workflow should complete
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS), "Workflow should complete after signal");
    }

    // ── Shutdown ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Shutdown rejects new workflows")
    void shutdownRejectsNewWorkflows() throws Exception {
        executor.shutdown();

        var workflow = new SimpleWorkflow(new CountDownLatch(1));
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        assertThrows(IllegalStateException.class, () ->
                executor.startWorkflow("order-5", "SimpleWorkflow", "default",
                        "input", workflow, method));
    }

    @Test
    @DisplayName("isRunning() returns true while workflow is active")
    void isRunningReturnsTrueWhileActive() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new BlockingWorkflow(startedLatch, blockLatch);
        var method = BlockingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-6", "BlockingWorkflow", "default",
                "input", workflow, method);

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
        assertTrue(executor.isRunning("order-6"));

        blockLatch.countDown();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertFalse(executor.isRunning("order-6")));
    }

    @Test
    @DisplayName("Recovery re-invokes recoverable workflows")
    void recoverWorkflowsReInvokesRecoverable() throws Exception {
        // Pre-populate a recoverable instance
        var instanceId = UUID.randomUUID();
        var instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("recover-1")
                .runId(UUID.randomUUID())
                .workflowType("SimpleWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.createInstance(instance);

        var latch = new CountDownLatch(1);
        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);
        var reg = new WorkflowRegistration("SimpleWorkflow", "default", workflow, method);

        var count = executor.recoverWorkflows(Map.of("SimpleWorkflow", reg));

        assertEquals(1, count, "Should recover 1 workflow");
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Recovered workflow should complete");
    }

    @Test
    @DisplayName("Lifecycle events are published")
    void lifecycleEventsPublished() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-7", "SimpleWorkflow", "default",
                "hello", workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertFalse(messaging.events.isEmpty(), "Lifecycle events should have been published"));
    }

    // ── Saga compensation tests ──────────────────────────────────────

    @Test
    @DisplayName("Manual addCompensation runs compensations on failure (no @Saga required)")
    void manualCompensationRunsOnFailure() throws Exception {
        var compensationRan = new CountDownLatch(1);
        var failedLatch = new CountDownLatch(1);

        var workflow = new CompensatingWorkflow(failedLatch, compensationRan);
        var method = CompensatingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("comp-1", "CompensatingWorkflow", "default",
                "input", workflow, method);

        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        // Wait for workflow to fully complete (transition to FAILED)
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var inst = store.getInstance("comp-1");
            assertTrue(inst.isPresent());
            assertEquals(WorkflowStatus.FAILED, inst.get().status());
        });

        // Compensation should have run
        assertTrue(compensationRan.await(2, TimeUnit.SECONDS),
                "Compensation should have been executed");

        // COMPENSATION_STARTED and COMPENSATION_COMPLETED events should exist
        var events = store.events.stream()
                .filter(e -> e.workflowInstanceId().equals(
                        store.getInstance("comp-1").get().id()))
                .map(WorkflowEvent::eventType)
                .toList();
        assertTrue(events.contains(EventType.COMPENSATION_STARTED));
        assertTrue(events.contains(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("@Saga workflow runs compensations on failure")
    void sagaAnnotatedWorkflowRunsCompensationsOnFailure() throws Exception {
        var compensationRan = new CountDownLatch(1);
        var failedLatch = new CountDownLatch(1);

        var workflow = new SagaAnnotatedWorkflow(failedLatch, compensationRan);
        var method = SagaAnnotatedWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("saga-1", "SagaWorkflow", "default",
                "input", workflow, method);

        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var inst = store.getInstance("saga-1");
            assertTrue(inst.isPresent());
            assertEquals(WorkflowStatus.FAILED, inst.get().status());
        });

        assertTrue(compensationRan.await(2, TimeUnit.SECONDS),
                "Saga compensation should have been executed");
    }

    // ── Test workflow implementations ──────────────────────────────────

    /**
     * Workflow that registers a manual compensation and then fails.
     */
    public static class CompensatingWorkflow {
        private final CountDownLatch failedLatch;
        private final CountDownLatch compensationRan;

        public CompensatingWorkflow(CountDownLatch failedLatch, CountDownLatch compensationRan) {
            this.failedLatch = failedLatch;
            this.compensationRan = compensationRan;
        }

        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            workflow.addCompensation(() -> compensationRan.countDown());
            failedLatch.countDown();
            throw new RuntimeException("Intentional failure after compensation registration");
        }
    }

    /**
     * Workflow with @Saga annotation that registers compensation and fails.
     */
    public static class SagaAnnotatedWorkflow {
        private final CountDownLatch failedLatch;
        private final CountDownLatch compensationRan;

        public SagaAnnotatedWorkflow(CountDownLatch failedLatch, CountDownLatch compensationRan) {
            this.failedLatch = failedLatch;
            this.compensationRan = compensationRan;
        }

        @io.b2mash.maestro.core.annotation.Saga
        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            workflow.addCompensation("manual-comp", () -> compensationRan.countDown());
            failedLatch.countDown();
            throw new RuntimeException("Intentional saga failure");
        }
    }

    /**
     * Simple workflow that returns its input uppercased.
     */
    public static class SimpleWorkflow {
        private final CountDownLatch latch;

        public SimpleWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run(String input) {
            latch.countDown();
            return input != null ? input.toUpperCase() : "DONE";
        }
    }

    /**
     * Workflow with no input parameter.
     */
    public static class NoInputWorkflow {
        private final CountDownLatch latch;

        public NoInputWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run() {
            latch.countDown();
            return "completed";
        }
    }

    /**
     * Workflow that always throws.
     */
    public static class FailingWorkflow {
        private final CountDownLatch latch;

        public FailingWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run(String input) {
            latch.countDown();
            throw new RuntimeException("Intentional failure");
        }
    }

    /**
     * Workflow that waits for a signal.
     */
    public static class SignalWorkflow {
        private final CountDownLatch waitingLatch;
        private final CountDownLatch completedLatch;

        public SignalWorkflow(CountDownLatch waitingLatch, CountDownLatch completedLatch) {
            this.waitingLatch = waitingLatch;
            this.completedLatch = completedLatch;
        }

        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            waitingLatch.countDown();
            var result = workflow.awaitSignal("payment.result", String.class, Duration.ofSeconds(10));
            completedLatch.countDown();
            return result;
        }
    }

    /**
     * Workflow that blocks on a latch (for testing isRunning).
     */
    public static class BlockingWorkflow {
        private final CountDownLatch startedLatch;
        private final CountDownLatch blockLatch;

        public BlockingWorkflow(CountDownLatch startedLatch, CountDownLatch blockLatch) {
            this.startedLatch = startedLatch;
            this.blockLatch = blockLatch;
        }

        public String run(String input) {
            startedLatch.countDown();
            try {
                blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "unblocked";
        }
    }

    // ── In-memory WorkflowStore ────────────────────────────────────────

    /**
     * Simple in-memory implementation of {@link WorkflowStore} for testing.
     */
    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, WorkflowInstance> instancesById = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) {
                throw new WorkflowAlreadyExistsException(instance.workflowId());
            }
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
                    .filter(i -> i.status().isActive())
                    .toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
            instancesById.put(instance.id(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            events.add(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId)
                            && e.sequenceNumber() == sequenceNumber)
                    .findFirst();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId))
                    .toList();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            signals.add(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId)
                            && s.signalName().equals(signalName)
                            && !s.consumed())
                    .toList();
        }

        @Override
        public void markSignalConsumed(UUID signalId) {
            // Replace the signal with consumed=true
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId)) {
                    signals.set(i, new WorkflowSignal(
                            s.id(), s.workflowInstanceId(), s.workflowId(),
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
                    signals.set(i, new WorkflowSignal(
                            s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            timers.add(timer);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING && !t.fireAt().isAfter(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(
                            t.id(), t.workflowInstanceId(), t.workflowId(), t.timerId(),
                            t.fireAt(), TimerStatus.FIRED, t.createdAt()));
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
                    timers.set(i, new WorkflowTimer(
                            t.id(), t.workflowInstanceId(), t.workflowId(), t.timerId(),
                            t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return;
                }
            }
        }
    }

    // ── Recording WorkflowMessaging ────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {

        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {}

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
