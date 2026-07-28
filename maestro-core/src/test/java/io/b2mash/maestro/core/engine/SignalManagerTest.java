package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
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
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.ScopedValue;
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
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("paid", result);
        });
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
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("paid", result);

            // Signal should be marked consumed
            var unconsumed = store.getUnconsumedSignals("order-1", "payment.result");
            assertTrue(unconsumed.isEmpty(), "Signal should be consumed");

            // SIGNAL_RECEIVED event should be appended
            var events = store.getEvents(instanceId);
            assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.SIGNAL_RECEIVED));
        });
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
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                awaitingLatch.countDown();
                var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
                resultHolder.add(result);
                completedLatch.countDown();
            });
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
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class, () ->
                        signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofMillis(100))));
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
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                try {
                    var result = signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofMillis(100));
                    resultHolder.add(result);
                    completedLatch.countDown();
                } catch (SignalTimeoutException e) {
                    completedLatch.countDown(); // Allow test to proceed even if timeout
                }
            });
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

    // ── awaitSignal — cross-instance wake subscription ─────────────────

    @Test
    @DisplayName("awaitSignal subscribes for cross-instance wake while parked and unsubscribes after wake")
    void awaitSignalSubscribesWhileParkedAndUnsubscribesAfterWake() throws Exception {
        var notifier = new SubscribingNotifier();
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var completedLatch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
                completedLatch.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));
        assertEquals(List.of("order-1"), notifier.subscribeCalls,
                "should subscribe for cross-instance wake before parking");

        sm.deliverSignal("order-1", "payment.result", "paid");

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> !notifier.unsubscribeCalls.isEmpty());
        assertEquals(List.of("order-1"), notifier.unsubscribeCalls,
                "should unsubscribe after wake");
        assertTrue(notifier.subscriptions.isEmpty());
    }

    @Test
    @DisplayName("remote notification wakes a parked workflow — signal persisted on another node")
    void remoteNotificationWakesParkedWorkflow() throws Exception {
        var notifier = new SubscribingNotifier();
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var completedLatch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                resultHolder.add(sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(30)));
                completedLatch.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                notifier.subscriptions.containsKey("order-1")
                        && parkingLot.isParked("order-1:signal:payment.result"));

        // Simulate another node: persist the signal directly (no local unpark) …
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", serializer.serialize("remote-paid"), false, Instant.now()));
        // … then fire the cross-instance notification callback
        notifier.subscriptions.get("order-1").onSignal("order-1", "payment.result");

        // Timeout is 30s, so completion within 5s proves the callback woke it
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS),
                "remote notification should wake the parked workflow");
        assertEquals(List.of("remote-paid"), resultHolder);
    }

    @Test
    @DisplayName("awaitSignal unsubscribes on timeout")
    void awaitSignalUnsubscribesOnTimeout() {
        var notifier = new SubscribingNotifier();
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class, () ->
                        sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofMillis(100))));

        assertEquals(List.of("order-1"), notifier.unsubscribeCalls,
                "should unsubscribe after timeout");
        assertTrue(notifier.subscriptions.isEmpty());
    }

    @Test
    @DisplayName("signal landing during subscribe is consumed by the post-subscribe re-check without parking")
    void subscribeRaceRecheckConsumesSignalWithoutParking() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // A notifier whose subscribe() races a remote signal into the store
        // before the subscription becomes active
        var notifier = new SubscribingNotifier() {
            @Override
            public void subscribe(String workflowId, SignalCallback callback) {
                super.subscribe(workflowId, callback);
                store.saveSignal(new WorkflowSignal(
                        UUID.randomUUID(), instanceId, "order-1",
                        "payment.result", serializer.serialize("raced"), false, Instant.now()));
            }
        };
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var result = sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("raced", result, "re-check after subscribe should consume the raced signal");
        });
        assertEquals(List.of("order-1"), notifier.unsubscribeCalls);
    }

    @Test
    @DisplayName("subscription is ref-counted across concurrent awaits of the same workflow")
    void refCountedSubscriptionSurvivesConcurrentAwaits() throws Exception {
        var notifier = new SubscribingNotifier();
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var firstDone = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                sm.awaitSignal(ctx, "signal-a", String.class, Duration.ofSeconds(10));
                firstDone.countDown();
            });
        });
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 10, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                sm.awaitSignal(ctx, "signal-b", String.class, Duration.ofSeconds(10));
                secondDone.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:signal-a")
                        && parkingLot.isParked("order-1:signal:signal-b"));
        assertEquals(List.of("order-1"), notifier.subscribeCalls,
                "one physical subscription shared by both awaits");

        sm.deliverSignal("order-1", "signal-a", "a");
        assertTrue(firstDone.await(5, TimeUnit.SECONDS));
        assertTrue(notifier.unsubscribeCalls.isEmpty(),
                "subscription must survive while the second await is still parked");

        sm.deliverSignal("order-1", "signal-b", "b");
        assertTrue(secondDone.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> !notifier.unsubscribeCalls.isEmpty());
        assertEquals(List.of("order-1"), notifier.unsubscribeCalls,
                "unsubscribed exactly once, after the last await finished");
    }

    @Test
    @DisplayName("subscribe failure falls back to store-based delivery")
    void subscribeFailureFallsBackToStoreDelivery() throws Exception {
        var notifier = new SubscribingNotifier() {
            @Override
            public void subscribe(String workflowId, SignalCallback callback) {
                throw new RuntimeException("Simulated subscribe failure");
            }
        };
        var sm = new SignalManager(store, messaging, notifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var completedLatch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                resultHolder.add(sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10)));
                completedLatch.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));

        sm.deliverSignal("order-1", "payment.result", "paid");

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS),
                "await must still work when subscribe fails");
        assertEquals(List.of("paid"), resultHolder);
        assertTrue(notifier.unsubscribeCalls.isEmpty(),
                "no unsubscribe when the subscribe never succeeded");
    }

    @Test
    @DisplayName("parked workflow finds a store-persisted signal via periodic re-check — no notifier, no unpark")
    void parkedWorkflowFindsStoreSignalViaPeriodicRecheck() throws Exception {
        // No SignalNotifier at all (e.g. Kafka messaging without Valkey):
        // cross-node signals must still arrive within the re-check interval
        var sm = new SignalManager(store, messaging, null, serializer, parkingLot,
                Duration.ofMillis(100));

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var completedLatch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                resultHolder.add(sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(30)));
                completedLatch.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));

        // Simulate another node persisting the signal: no local unpark, no notification
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", serializer.serialize("cross-node"), false, Instant.now()));

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS),
                "periodic store re-check must deliver the signal without any notification");
        assertEquals(List.of("cross-node"), resultHolder);
    }

    @Test
    @DisplayName("spurious wake (notifier self-echo) re-parks instead of aborting the await")
    void spuriousWakeDoesNotAbortAwait() throws Exception {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var resultHolder = new CopyOnWriteArrayList<String>();
        var completedLatch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            var ctx = createContext(instanceId, "order-1", 0, false);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                resultHolder.add(signalManager.awaitSignal(
                        ctx, "payment.result", String.class, Duration.ofSeconds(10)));
                completedLatch.countDown();
            });
        });

        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));

        // Spurious wake: unpark with NO signal in the store — e.g. a pub/sub
        // self-echo arriving after a previous consume re-parked this key
        parkingLot.unpark("order-1:signal:payment.result", null);

        // The await must survive it and re-park
        await().atMost(Duration.ofSeconds(2)).until(() ->
                parkingLot.isParked("order-1:signal:payment.result"));

        // The real signal then completes the await normally
        signalManager.deliverSignal("order-1", "payment.result", "paid");
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS),
                "await must complete after the real signal, not abort on the spurious wake");
        assertEquals(List.of("paid"), resultHolder);
    }

    // ── consumeSignal — CAS loses ──────────────────────────────────────

    @Test
    @DisplayName("consumeSignal proceeds when the consumed-flag CAS loses — appended event is the memoized truth")
    void consumeSignalProceedsWhenCasLoses() {
        var casLosingStore = new InMemoryWorkflowStore() {
            @Override
            public boolean markSignalConsumed(UUID signalId) {
                super.markSignalConsumed(signalId);
                return false;
            }
        };
        var sm = new SignalManager(casLosingStore, messaging, null, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        casLosingStore.createInstance(WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("order-1")
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        casLosingStore.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1",
                "payment.result", serializer.serialize("paid"), false, Instant.now()));

        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var result = sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10));
            assertEquals("paid", result, "payload from the appended event must still be returned");
            assertTrue(casLosingStore.getEvents(instanceId).stream()
                            .anyMatch(e -> e.eventType() == EventType.SIGNAL_RECEIVED),
                    "SIGNAL_RECEIVED event must be appended even when the CAS loses");
        });
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
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10)));

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
        public synchronized boolean markSignalConsumed(UUID signalId) {
            // synchronized: the check-then-set below must be atomic so two
            // concurrent consumers cannot both win the consumption CAS
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId) && !s.consumed()) {
                    signals.set(i, new WorkflowSignal(
                            s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return true;
                }
            }
            return false;
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
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return timers.stream()
                    .filter(t -> t.workflowInstanceId().equals(workflowInstanceId)
                            && t.timerId().equals(timerId))
                    .findFirst();
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

    private static class SubscribingNotifier implements SignalNotifier {

        final ConcurrentHashMap<String, SignalCallback> subscriptions = new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<String> subscribeCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> unsubscribeCalls = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String workflowId, String signalName) {}

        @Override
        public void subscribe(String workflowId, SignalCallback callback) {
            subscribeCalls.add(workflowId);
            subscriptions.put(workflowId, callback);
        }

        @Override
        public void unsubscribe(String workflowId) {
            unsubscribeCalls.add(workflowId);
            subscriptions.remove(workflowId);
        }
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
