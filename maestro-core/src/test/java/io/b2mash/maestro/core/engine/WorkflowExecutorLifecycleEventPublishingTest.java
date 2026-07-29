package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.LifecycleEventType;
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
 *
 * <p>Fix round 2 (QA Gate 5): {@code enabled=false} was only honoured by this
 * class's own {@code WORKFLOW_*} events. {@link ActivityInvocationHandler}
 * ({@code ACTIVITY_*}), {@link SignalManager} ({@code SIGNAL_RECEIVED}),
 * {@link DefaultWorkflowOperations} ({@code TIMER_*}), and {@link
 * io.b2mash.maestro.core.saga.SagaManager} ({@code COMPENSATION_*}) each
 * checked only {@code messaging != null}, never the flag — a live E2E run
 * with the flag set leaked 247 non-workflow-level events to Kafka. The
 * activity/timer/signal/compensation tests below drive a workflow through
 * all four of those paths and assert zero events reach the spy when
 * disabled, so this class of regression is caught here rather than by live
 * inspection of a Kafka topic.
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

        // Deterministic, not a timing guess: publishLifecycleEvent short-circuits
        // before submitting anything when disabled, so there is no async publish to
        // race. shutdown() additionally drains the lifecycle publisher (waits for
        // queued/in-flight work, see LifecycleEventPublisher#shutdown) before
        // returning, so this assertion stays deterministic even against a future
        // regression that submits-then-drops instead of never submitting at all.
        executor.shutdown();
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

    // ── Fix round 2: enabled=false must gate EVERY lifecycle event type ────

    @Test
    @DisplayName("enabled=false stops activity, timer, and signal lifecycle events too — not just workflow-level ones")
    void lifecycleEventsDisabled_stopsActivityTimerAndSignalEventsToo() throws Exception {
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false);

        // Activity proxies are not built by WorkflowExecutor — in production
        // ActivityStubBeanPostProcessor builds them independently, resolving its
        // own WorkflowMessaging bean straight from the Spring context. A caller
        // that wants activity lifecycle events gated therefore wraps messaging
        // with the same GatedWorkflowMessaging seam WorkflowExecutor uses
        // internally, before handing it to ActivityProxyFactory.
        var gatedMessaging = GatedWorkflowMessaging.wrap(messaging, false);
        var activities = new ActivityProxyFactory().createProxy(
                GreetingActivities.class, new GreetingActivitiesImpl(),
                store, null, gatedMessaging, RetryPolicy.defaultPolicy(), Duration.ofSeconds(5),
                serializer, new RetryExecutor());

        var workflow = new FullLifecycleWorkflow(activities);
        var method = FullLifecycleWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("full-lifecycle-disabled", "FullLifecycleWorkflow", "default",
                "world", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("full-lifecycle-disabled")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));
        // The activity call is the workflow's first operation (sequence 1);
        // sleep() is the second (sequence 2) — see DefaultWorkflowOperations.sleep.
        var timer = store.findTimer(instanceId, "sleep-2").orElseThrow();
        executor.fireTimer("full-lifecycle-disabled", "sleep-2", timer.id());

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("full-lifecycle-disabled")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));
        executor.deliverSignal("full-lifecycle-disabled", "go", "approved");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("full-lifecycle-disabled").orElseThrow().status()));

        // Deterministic, not a timing guess: shutdown() drains the lifecycle
        // publisher (see LifecycleEventPublisher#shutdown) before returning.
        executor.shutdown();
        assertEquals(List.of(), messaging.events,
                "no lifecycle event of ANY type may reach messaging when disabled — activity, "
                        + "timer, and signal events must be gated exactly like workflow-level ones");
    }

    @Test
    @DisplayName("enabled=false stops saga compensation lifecycle events too")
    void lifecycleEventsDisabled_stopsCompensationEventsToo() throws Exception {
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false);

        var workflow = new CompensatingWorkflow();
        var method = CompensatingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("comp-disabled", "CompensatingWorkflow", "default", "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.FAILED, store.getInstance("comp-disabled").orElseThrow().status()));

        executor.shutdown();
        assertEquals(List.of(), messaging.events,
                "no COMPENSATION_* lifecycle event may reach messaging when disabled");
    }

    @Test
    @DisplayName("enabled=true (the default) still publishes activity, timer, and signal events — the positive control")
    void lifecycleEventsEnabled_publishesActivityTimerAndSignalEventsToo() throws Exception {
        // Without this control, the disabled-case test above could pass
        // vacuously if the workflow shape itself never generated non-workflow-
        // level events (e.g. a typo in a signal/timer name that never fires).
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");

        var gatedMessaging = GatedWorkflowMessaging.wrap(messaging, true);
        var activities = new ActivityProxyFactory().createProxy(
                GreetingActivities.class, new GreetingActivitiesImpl(),
                store, null, gatedMessaging, RetryPolicy.defaultPolicy(), Duration.ofSeconds(5),
                serializer, new RetryExecutor());

        var workflow = new FullLifecycleWorkflow(activities);
        var method = FullLifecycleWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("full-lifecycle-enabled", "FullLifecycleWorkflow", "default",
                "world", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("full-lifecycle-enabled")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));
        var timer = store.findTimer(instanceId, "sleep-2").orElseThrow();
        executor.fireTimer("full-lifecycle-enabled", "sleep-2", timer.id());

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("full-lifecycle-enabled")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));
        executor.deliverSignal("full-lifecycle-enabled", "go", "approved");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("full-lifecycle-enabled").orElseThrow().status()));

        executor.shutdown();
        var types = messaging.events.stream().map(WorkflowLifecycleEvent::eventType).toList();
        assertTrue(types.contains(LifecycleEventType.WORKFLOW_STARTED));
        assertTrue(types.contains(LifecycleEventType.ACTIVITY_STARTED));
        assertTrue(types.contains(LifecycleEventType.ACTIVITY_COMPLETED));
        assertTrue(types.contains(LifecycleEventType.TIMER_SCHEDULED));
        assertTrue(types.contains(LifecycleEventType.TIMER_FIRED));
        assertTrue(types.contains(LifecycleEventType.SIGNAL_RECEIVED));
        assertTrue(types.contains(LifecycleEventType.WORKFLOW_COMPLETED));
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

    /** A trivial activity, invoked through a real memoizing proxy so ACTIVITY_* events are published. */
    public interface GreetingActivities {
        String greet(String name);
    }

    /** @see GreetingActivities */
    public static class GreetingActivitiesImpl implements GreetingActivities {
        @Override
        public String greet(String name) {
            return "hello " + name;
        }
    }

    /**
     * Drives an activity call, a durable sleep, and a signal await in one run —
     * every lifecycle-event source {@link WorkflowExecutor} touches except saga
     * compensation (see {@link CompensatingWorkflow} for that one).
     */
    public static class FullLifecycleWorkflow {
        private final GreetingActivities activities;

        public FullLifecycleWorkflow(GreetingActivities activities) {
            this.activities = activities;
        }

        public String run(String input) {
            var greeting = activities.greet(input);
            WorkflowContext.current().sleep(Duration.ofMillis(50));
            var signal = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(10));
            return greeting + ":" + signal;
        }
    }

    /** Registers a compensation, then fails — exercises SagaManager's COMPENSATION_* events. */
    public static class CompensatingWorkflow {
        public String run(String input) {
            var wf = WorkflowContext.current();
            wf.addCompensation("undo-" + input, () -> {});
            throw new IllegalStateException("deliberate failure for the compensation-gating test");
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
            for (var i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId) && !s.consumed()) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return true;
                }
            }
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
            for (var i = 0; i < timers.size(); i++) {
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
        }
    }
}
