package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the latency and enable/disable contract of lifecycle event publishing.
 *
 * <p>Issue 3: a {@code WorkflowMessaging} implementation is free to block
 * inside {@code publishLifecycleEvent} (Kafka's producer, for example, blocks
 * synchronously fetching metadata for a missing topic, up to
 * {@code max.block.ms}). That must never show up as latency on
 * {@code startWorkflow} or the workflow completion path — the SPI contract
 * already says lifecycle failures must not interrupt execution; this pins
 * that the same is true for latency.
 *
 * <p>Issue 6: {@code enabled=false} (threaded in via the executor
 * constructor) must stop lifecycle publishing entirely, not just tolerate
 * failures from it.
 */
@DisplayName("Lifecycle event publishing is off-thread and can be disabled")
class WorkflowExecutorLifecycleEventPublishingTest {

    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("startWorkflow returns promptly even though publishLifecycleEvent blocks for seconds")
    void startWorkflow_returnsPromptly_whenMessagingBlocks() throws Exception {
        var blockFor = Duration.ofSeconds(3);
        var messaging = new BlockingMessaging(blockFor);
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");

        var latch = new CountDownLatch(1);
        var workflow = new ImmediateWorkflow(latch);
        var method = ImmediateWorkflow.class.getMethod("run", String.class);

        var start = System.nanoTime();
        executor.startWorkflow("blocking-topic-1", "ImmediateWorkflow", "default",
                "hello", workflow, method);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofMillis(500)) < 0,
                "startWorkflow must not wait on a slow lifecycle publish, took " + elapsed);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "workflow body should still run to completion");

        // The blocking publish does eventually happen, off-thread.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(messaging.publishAttempts.get() > 0, "the publish must still happen, just not inline"));
    }

    @Test
    @DisplayName("enabled=false stops lifecycle publishing entirely")
    void lifecycleEventsDisabled_noPublishCalls() throws Exception {
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false);

        var latch = new CountDownLatch(1);
        var workflow = new ImmediateWorkflow(latch);
        var method = ImmediateWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("disabled-1", "ImmediateWorkflow", "default", "hello", workflow, method);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("disabled-1").orElseThrow().status()));

        // Give any (incorrect) async publish a chance to land before asserting absence.
        Thread.sleep(200);
        assertEquals(List.of(), messaging.events, "no lifecycle event may be published when disabled");
    }

    @Test
    @DisplayName("enabled defaults to true when unspecified")
    void lifecycleEventsEnabled_byDefault() throws Exception {
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");

        var latch = new CountDownLatch(1);
        var workflow = new ImmediateWorkflow(latch);
        var method = ImmediateWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("enabled-1", "ImmediateWorkflow", "default", "hello", workflow, method);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertTrue(messaging.events.size() >= 2,
                        "WORKFLOW_STARTED and WORKFLOW_COMPLETED should be published by default"));
    }

    @Test
    @DisplayName("shutdown does not hang waiting on lifecycle publishing")
    void shutdown_doesNotHangOnLifecyclePublisher() throws Exception {
        var messaging = new BlockingMessaging(Duration.ofSeconds(30));
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");

        var latch = new CountDownLatch(1);
        var workflow = new ImmediateWorkflow(latch);
        var method = ImmediateWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("shutdown-1", "ImmediateWorkflow", "default", "hello", workflow, method);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED, store.getInstance("shutdown-1").orElseThrow().status()));

        var start = System.nanoTime();
        executor.shutdown();
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0,
                "shutdown must not wait out a stalled lifecycle publisher (30s block), took " + elapsed);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    /** Completes immediately and counts down a latch so the test can synchronise. */
    public static class ImmediateWorkflow {
        private final CountDownLatch latch;

        public ImmediateWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run(String input) {
            latch.countDown();
            return input;
        }
    }

    /** Blocks every publishLifecycleEvent call for a fixed duration, like a stalled Kafka send(). */
    private static class BlockingMessaging implements WorkflowMessaging {
        private final Duration blockFor;
        final java.util.concurrent.atomic.AtomicInteger publishAttempts = new java.util.concurrent.atomic.AtomicInteger();

        BlockingMessaging(Duration blockFor) {
            this.blockFor = blockFor;
        }

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {}

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            publishAttempts.incrementAndGet();
            try {
                Thread.sleep(blockFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }

    /** Records every published lifecycle event. */
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

    // ── In-memory WorkflowStore ─────────────────────────────────────────

    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) {
                throw new WorkflowAlreadyExistsException(instance.workflowId());
            }
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            events.add(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == sequenceNumber)
                    .findFirst();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            signals.add(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId) && s.signalName().equals(signalName) && !s.consumed())
                    .toList();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return false;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
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
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return timers.stream()
                    .filter(t -> t.workflowInstanceId().equals(workflowInstanceId) && t.timerId().equals(timerId))
                    .findFirst();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return false;
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
        }
    }
}
