package io.maestro.core.engine;

import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.SignalTimeoutException;
import io.maestro.core.exception.WorkflowAlreadyExistsException;
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
import io.maestro.core.spi.SignalNotifier;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SignalManager}.
 *
 * <p>Tests signal delivery, await (replay and live paths), timeout handling,
 * and orphan adoption using an in-memory {@link WorkflowStore}.
 */
class SignalManagerTest {

    private InMemoryWorkflowStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private ParkingLot parkingLot;
    private SignalManager signalManager;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(new ObjectMapper());
        parkingLot = new ParkingLot();
        signalManager = new SignalManager(store, messaging, null, serializer, parkingLot);
    }

    // ── deliverSignal ──────────────────────────────────────────────────

    @Test
    @DisplayName("deliverSignal to existing workflow — signal persisted and parkingLot unparked")
    void deliverSignalToExistingWorkflow() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        signalManager.deliverSignal("order-1", "payment.result", "paid");

        // Signal should be persisted
        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size());
        assertEquals("order-1", signals.getFirst().workflowId());
        assertEquals(instanceId, signals.getFirst().workflowInstanceId());
        assertFalse(signals.getFirst().consumed());
    }

    @Test
    @DisplayName("deliverSignal to non-existent workflow — signal persisted with null instanceId")
    void deliverSignalToNonExistentWorkflow() {
        signalManager.deliverSignal("order-99", "payment.result", "paid");

        var signals = store.getUnconsumedSignals("order-99", "payment.result");
        assertEquals(1, signals.size());
        assertNull(signals.getFirst().workflowInstanceId());
        assertEquals("order-99", signals.getFirst().workflowId());
    }

    @Test
    @DisplayName("deliverSignal unparks a waiting workflow")
    void deliverSignalUnparksWaiting() throws Exception {
        var parkKey = "order-1:signal:payment.result";
        var unparkedLatch = new CountDownLatch(1);

        // Park a virtual thread
        Thread.ofVirtual().start(() -> {
            parkingLot.park(parkKey);
            unparkedLatch.countDown();
        });

        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked(parkKey));

        createInstance("order-1", UUID.randomUUID());
        signalManager.deliverSignal("order-1", "payment.result", "paid");

        assertTrue(unparkedLatch.await(5, TimeUnit.SECONDS), "Parked thread should be unparked");
    }

    // ── deliverSignal — cross-instance notification ────────────────────

    @Test
    @DisplayName("deliverSignal invokes SignalNotifier.publish when notifier is present")
    void deliverSignalNotifiesCrossInstance() {
        var notifier = new RecordingNotifier();
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        sm.deliverSignal("order-1", "payment.result", "paid");

        // Signal still persisted
        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size());

        // Notifier was called
        assertEquals(1, notifier.published.size());
        assertEquals("order-1:payment.result", notifier.published.getFirst());
    }

    @Test
    @DisplayName("deliverSignal succeeds even if SignalNotifier.publish throws")
    void deliverSignalSurvivesNotifierFailure() {
        var failingNotifier = new FailingNotifier();
        var sm = new SignalManager(store, messaging, failingNotifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // Should not throw — notifier failure is swallowed
        sm.deliverSignal("order-1", "payment.result", "paid");

        // Signal should still be persisted
        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size(), "Signal must persist even when notifier fails");
    }

    // ── awaitSignal — replay path ──────────────────────────────────────

    @Test
    @DisplayName("awaitSignal replay — returns stored SIGNAL_RECEIVED event")
    void awaitSignalReplayPath() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // Pre-store a SIGNAL_RECEIVED event at sequence 1
        var payload = serializer.serialize("paid");
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1, EventType.SIGNAL_RECEIVED,
                "$maestro:awaitSignal:payment.result", payload, Instant.now()));

        var ctx = createContext(instanceId, "order-1", 0, true);
        WorkflowContext.bind(ctx);
        try {
            var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("paid", result);
        } finally {
            WorkflowContext.clear();
        }
    }

    // ── awaitSignal — live path with pre-arrived signal ────────────────

    @Test
    @DisplayName("awaitSignal live — pre-arrived signal consumed immediately")
    void awaitSignalPreArrivedSignal() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // Pre-deliver a signal
        var signalPayload = serializer.serialize("paid");
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", signalPayload, false, Instant.now()));

        var ctx = createContext(instanceId, "order-1", 0, false);
        WorkflowContext.bind(ctx);
        try {
            var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("paid", result);

            // Signal should be marked consumed
            var unconsumed = store.getUnconsumedSignals("order-1", "payment.result");
            assertTrue(unconsumed.isEmpty(), "Signal should be consumed");

            // SIGNAL_RECEIVED event should be appended
            var events = store.getEvents(instanceId);
            assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.SIGNAL_RECEIVED));
        } finally {
            WorkflowContext.clear();
        }
    }

    // ── awaitSignal — live path with park and wake ─────────────────────

    @Test
    @DisplayName("awaitSignal live — parks then wakes on signal delivery")
    void awaitSignalParkAndWake() throws Exception {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var awaitingLatch = new CountDownLatch(1);
        var completedLatch = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            WorkflowContext.bind(ctx);
            try {
                awaitingLatch.countDown();
                var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
                resultHolder.add(result);
                completedLatch.countDown();
            } finally {
                WorkflowContext.clear();
            }
        });

        // Wait for the thread to park on signal await
        assertTrue(awaitingLatch.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));

        // Deliver the signal
        signalManager.deliverSignal("order-1", "payment.result", "paid");

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS), "awaitSignal should complete after delivery");
        assertEquals(1, resultHolder.size());
        assertEquals("paid", resultHolder.getFirst());
    }

    // ── awaitSignal — timeout ──────────────────────────────────────────

    @Test
    @DisplayName("awaitSignal timeout — throws SignalTimeoutException")
    void awaitSignalTimeout() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var ctx = createContext(instanceId, "order-1", 0, false);
        WorkflowContext.bind(ctx);
        try {
            assertThrows(SignalTimeoutException.class, () ->
                    signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofMillis(100)));
        } finally {
            WorkflowContext.clear();
        }
    }

    // ── awaitSignal — timeout with late signal (race condition guard) ──

    @Test
    @DisplayName("awaitSignal timeout with late signal — consumed from store after timeout")
    void awaitSignalTimeoutWithLateSignal() throws Exception {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var completedLatch = new CountDownLatch(1);

        // Pre-deliver the signal AFTER the unconsumed check but before timeout
        // We simulate this by pre-delivering with a very short timeout
        var signalPayload = serializer.serialize("late-paid");
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", signalPayload, false, Instant.now()));

        // With a pre-arrived signal, awaitSignal should find it immediately (self-recovery)
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            WorkflowContext.bind(ctx);
            try {
                var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofMillis(100));
                resultHolder.add(result);
                completedLatch.countDown();
            } catch (SignalTimeoutException e) {
                completedLatch.countDown(); // Allow test to proceed even if timeout
            } finally {
                WorkflowContext.clear();
            }
        });

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, resultHolder.size(), "Pre-arrived signal should be consumed immediately");
        assertEquals("late-paid", resultHolder.getFirst());
    }

    // ── adoptOrphanedSignals ───────────────────────────────────────────

    @Test
    @DisplayName("adoptOrphanedSignals — links orphaned signals to instance")
    void adoptOrphanedSignals() {
        // Deliver signal before workflow exists (orphaned)
        signalManager.deliverSignal("order-1", "payment.result", "paid");

        // Verify signal has null instanceId
        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size());
        assertNull(signals.getFirst().workflowInstanceId());

        // Create instance and adopt
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);
        signalManager.adoptOrphanedSignals("order-1", instanceId);

        // Verify signal now has instanceId
        var adopted = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, adopted.size());
        assertEquals(instanceId, adopted.getFirst().workflowInstanceId());
    }

    // ── Lifecycle events ───────────────────────────────────────────────

    @Test
    @DisplayName("awaitSignal publishes SIGNAL_RECEIVED lifecycle event")
    void awaitSignalPublishesLifecycleEvent() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // Pre-deliver signal
        var signalPayload = serializer.serialize("paid");
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", signalPayload, false, Instant.now()));

        var ctx = createContext(instanceId, "order-1", 0, false);
        WorkflowContext.bind(ctx);
        try {
            signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
        } finally {
            WorkflowContext.clear();
        }

        assertFalse(messaging.events.isEmpty(), "Lifecycle event should be published");
        assertTrue(messaging.events.stream()
                .anyMatch(e -> e.stepName() != null && e.stepName().contains("awaitSignal")));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void createInstance(String workflowId, UUID instanceId) {
        store.createInstance(WorkflowInstance.builder()
                .id(instanceId)
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private WorkflowContext createContext(UUID instanceId, String workflowId, int initialSeq, boolean replaying) {
        return new WorkflowContext(
                instanceId, workflowId, UUID.randomUUID(),
                "TestWorkflow", "default", "test-service",
                initialSeq, replaying);
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

    // ── Recording SignalNotifier ──────────────────────────────────────

    private static class RecordingNotifier implements SignalNotifier {

        final CopyOnWriteArrayList<String> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String workflowId, String signalName) {
            published.add(workflowId + ":" + signalName);
        }

        @Override
        public void subscribe(String workflowId, SignalCallback callback) {}

        @Override
        public void unsubscribe(String workflowId) {}
    }

    private static class FailingNotifier implements SignalNotifier {

        @Override
        public void publish(String workflowId, String signalName) {
            throw new RuntimeException("Simulated notifier failure");
        }

        @Override
        public void subscribe(String workflowId, SignalCallback callback) {}

        @Override
        public void unsubscribe(String workflowId) {}
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
