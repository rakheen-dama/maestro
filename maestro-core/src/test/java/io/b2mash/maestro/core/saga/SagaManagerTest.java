package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SagaManager}.
 */
class SagaManagerTest {

    private InMemoryStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private SagaManager sagaManager;

    private WorkflowInstance instance;
    private WorkflowContext ctx;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(new ObjectMapper());
        sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        // Create a workflow instance
        var instanceId = UUID.randomUUID();
        instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("test-workflow")
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(5)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0)
                .build();
        store.createInstance(instance);

        ctx = new WorkflowContext(
                instanceId, "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service",
                5, false
        );
    }

    @Test
    @DisplayName("Sequential compensation runs in LIFO order")
    void sequentialCompensationRunsInLifoOrder() {
        var order = new ArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-A", () -> order.add("A"));
        stack.push("step-B", () -> order.add("B"));
        stack.push("step-C", () -> order.add("C"));

        sagaManager.compensate(ctx, instance, stack, false);

        // LIFO: C, B, A
        assertEquals(List.of("C", "B", "A"), order);
    }

    @Test
    @DisplayName("Status transitions to COMPENSATING during compensation")
    void statusTransitionsToCompensating() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> {});

        sagaManager.compensate(ctx, instance, stack, false);

        // The instance should have been set to COMPENSATING (SagaManager doesn't set FAILED)
        var latest = store.getInstance("test-workflow").orElseThrow();
        assertEquals(WorkflowStatus.COMPENSATING, latest.status());
    }

    @Test
    @DisplayName("Events: COMPENSATION_STARTED, then COMPENSATION_COMPLETED")
    void compensationEventsRecorded() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> {});

        sagaManager.compensate(ctx, instance, stack, false);

        var events = store.getEvents(instance.id());
        var types = events.stream().map(WorkflowEvent::eventType).toList();

        assertTrue(types.contains(EventType.COMPENSATION_STARTED));
        assertTrue(types.contains(EventType.COMPENSATION_COMPLETED));

        // STARTED should come before COMPLETED
        var startedIdx = types.indexOf(EventType.COMPENSATION_STARTED);
        var completedIdx = types.indexOf(EventType.COMPENSATION_COMPLETED);
        assertTrue(startedIdx < completedIdx);
    }

    @Test
    @DisplayName("Failed compensation is logged but doesn't stop remaining compensations")
    void failedCompensationDoesNotStopOthers() {
        var order = new ArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-A", () -> order.add("A"));
        stack.push("step-B", () -> { throw new RuntimeException("B failed"); });
        stack.push("step-C", () -> order.add("C"));

        // Partial failure throws CompensationException
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                CompensationException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(List.of("step-B"), ex.failedCompensations());

        // LIFO: C, then B (fails), then A (still runs)
        assertEquals(List.of("C", "A"), order);

        // COMPENSATION_STEP_FAILED event should be recorded
        var events = store.getEvents(instance.id());
        var failedEvents = events.stream()
                .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
        assertEquals("step-B", failedEvents.getFirst().stepName());
    }

    @Test
    @DisplayName("Parallel compensation runs all compensations concurrently")
    void parallelCompensationRunsConcurrently() {
        var counter = new AtomicInteger(0);
        var stack = new CompensationStack();
        // Push 5 compensations
        for (int i = 0; i < 5; i++) {
            stack.push("step-" + i, () -> {
                counter.incrementAndGet();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            });
        }

        sagaManager.compensate(ctx, instance, stack, true);

        // All 5 should have run
        assertEquals(5, counter.get());
    }

    @Test
    @DisplayName("Empty stack is a no-op")
    void emptyStackIsNoop() {
        var stack = new CompensationStack();

        sagaManager.compensate(ctx, instance, stack, false);

        // No status change, no events
        var latest = store.getInstance("test-workflow").orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, latest.status());

        var events = store.getEvents(instance.id());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("Parallel compensation: failed step doesn't prevent others")
    void parallelFailedStepDoesNotPreventOthers() {
        var counter = new AtomicInteger(0);
        var stack = new CompensationStack();
        stack.push("step-ok-1", counter::incrementAndGet);
        stack.push("step-fail", () -> { throw new RuntimeException("fail"); });
        stack.push("step-ok-2", counter::incrementAndGet);

        // Partial failure throws CompensationException
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                CompensationException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));

        assertEquals(1, ex.failedCompensations().size());

        // The 2 successful compensations should have run despite the failure
        assertEquals(2, counter.get());

        // COMPENSATION_STEP_FAILED event should be recorded
        var failedEvents = store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
    }

    // ── In-memory store ───────────────────────────────────────────────

    private static class InMemoryStore implements WorkflowStore {
        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            return instance;
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
        }

        @Override public void appendEvent(WorkflowEvent event) { events.add(event); }

        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == seq)
                    .findFirst();
        }

        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override public void saveSignal(WorkflowSignal signal) {}
        @Override public List<WorkflowSignal> getUnconsumedSignals(String wfId, String name) { return List.of(); }
        @Override public void markSignalConsumed(UUID signalId) {}
        @Override public void adoptOrphanedSignals(String wfId, UUID instanceId) {}
        @Override public void saveTimer(WorkflowTimer timer) {}
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { return List.of(); }
        @Override public boolean markTimerFired(UUID timerId) { return false; }
        @Override public void markTimerCancelled(UUID timerId) {}
    }

    // ── Recording messaging ───────────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {
        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override public void publishTask(String taskQueue, TaskMessage message) {}
        @Override public void publishSignal(String serviceName, SignalMessage message) {}
        @Override public void publishLifecycleEvent(WorkflowLifecycleEvent event) { events.add(event); }
        @Override public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}
        @Override public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
