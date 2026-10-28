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
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.TraceContextHolder;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.ScopedValue;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.b2mash.maestro.core.TestEventLogs.removeFailureEvents;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        // Notifier was called — off the sender's thread, so await it (Issue 32)
        await().atMost(Duration.ofSeconds(5)).until(() -> !notifier.published.isEmpty());
        assertEquals(1, notifier.published.size());
        assertEquals("order-1:payment.result", notifier.published.getFirst());
    }

    @Test
    @DisplayName("deliverSignal succeeds even if SignalNotifier.publish throws")
    void deliverSignalSurvivesNotifierFailure() throws Exception {
        var failingNotifier = new FailingNotifier();
        var sm = new SignalManager(store, messaging, failingNotifier, serializer, parkingLot);

        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // Should not throw — notifier failure is swallowed
        sm.deliverSignal("order-1", "payment.result", "paid");

        // Signal should still be persisted
        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size(), "Signal must persist even when notifier fails");

        // The throw happens on the hand-off thread: wait for it to run and end,
        // then check the engine caught it rather than letting it escape.
        await().atMost(Duration.ofSeconds(5)).until(() -> failingNotifier.publishThread.get() != null);
        var handOff = failingNotifier.publishThread.get();
        assertTrue(handOff.join(Duration.ofSeconds(5)), "the hand-off thread must finish");
        assertEquals(List.of(), failingNotifier.escaped,
                "the notifier's RuntimeException must be caught on the hand-off thread");
    }

    @Test
    @DisplayName("R58: at most MAX_IN_FLIGHT_NOTIFICATIONS hand-offs run at once; the excess is dropped, not queued")
    void notificationsBeyondTheInFlightCapAreDropped() throws Exception {
        var entered = new java.util.concurrent.atomic.AtomicInteger();
        var release = new CountDownLatch(1);
        var blocking = new SignalNotifier() {
            @Override
            public void publish(String workflowId, String signalName) {
                entered.incrementAndGet();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void subscribe(String workflowId, SignalCallback callback) {}

            @Override
            public void unsubscribe(String workflowId) {}
        };
        var sm = new SignalManager(store, messaging, blocking, serializer, parkingLot);
        createInstance("order-1", UUID.randomUUID());
        var cap = SignalManager.MAX_IN_FLIGHT_NOTIFICATIONS;

        try {
            for (int i = 0; i < cap + 5; i++) {
                sm.deliverSignal("order-1", "payment.result", "paid-" + i);
            }
            assertEquals(cap + 5, store.getUnconsumedSignals("order-1", "payment.result").size(),
                    "every signal is persisted — only its notification may be dropped");
            await().atMost(Duration.ofSeconds(10)).until(() -> entered.get() == cap);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                    .until(() -> entered.get() == cap);
        } finally {
            release.countDown();
        }

        // Once the stalled hand-offs finish, notifications flow again.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            sm.deliverSignal("order-1", "payment.result", "after");
            return entered.get() > cap;
        });
    }

    @Test
    @DisplayName("Issue 32: deliverSignal returns promptly when SignalNotifier.publish blocks — one row, notified off-thread")
    void deliverSignalDoesNotWaitForABlockingNotifier() throws Exception {
        // A notifier stuck on a brownout: publish parks until released (Lettuce's
        // sync PUBLISH against a paused Valkey sits for its 60 s command timeout).
        var publishEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publishThread = new AtomicReference<Thread>();
        var blocking = new SignalNotifier() {
            @Override
            public void publish(String workflowId, String signalName) {
                publishThread.set(Thread.currentThread());
                publishEntered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void subscribe(String workflowId, SignalCallback callback) {}

            @Override
            public void unsubscribe(String workflowId) {}
        };
        var sm = new SignalManager(store, messaging, blocking, serializer, parkingLot);
        createInstance("order-1", UUID.randomUUID());

        try {
            var started = System.nanoTime();
            sm.deliverSignal("order-1", "payment.result", "paid");
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0,
                    "deliverSignal must not wait for the notifier; took " + elapsed);
            assertEquals(1, store.getUnconsumedSignals("order-1", "payment.result").size(),
                    "exactly one signal row — the store is the truth, the notification is extra");
            // The notification is still sent — on another thread, never awaited.
            assertTrue(publishEntered.await(5, TimeUnit.SECONDS), "publish must still be invoked");
            assertFalse(publishThread.get() == Thread.currentThread(),
                    "publish must not run on the signal sender's thread");
        } finally {
            release.countDown();
        }
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

    // ── awaitSignal — durable deadline (C12 / F4) ──────────────────────

    @Test
    @DisplayName("a parked awaitSignal persists an await-signal-<seq> timer row and memoizes its deadline")
    void awaitSignalParks_createsDurableTimerRowAndMemoizesDeadline() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var ctx = createContext(instanceId, "order-1", 0, false);
        var before = Instant.now();
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), new AtomicReference<>());
        await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
        var row = store.findTimer(instanceId, "await-signal-1").orElseThrow();
        assertEquals(TimerStatus.PENDING, row.status());
        assertFalse(row.fireAt().isBefore(before.plus(Duration.ofHours(1)).minusSeconds(5)));
        var memo = store.getEventBySequence(instanceId, 1).orElseThrow();
        assertEquals(EventType.TIMER_SCHEDULED, memo.eventType());
        assertEquals("$maestro:awaitSignal:payment.result", memo.stepName());
        assertEquals("await-signal-1", memo.payload().get("timerId").stringValue());
        assertEquals(row.fireAt(), Instant.parse(memo.payload().get("deadline").stringValue()));
        assertTrue(store.getEventBySequence(instanceId, 2).isEmpty());
        worker.interrupt();
    }

    @Test
    @DisplayName("a parked awaitSignal times out when its timer row transitions — not on the wall clock")
    void awaitSignalTimeout_isDrivenByTheTimerTransition_notTheWallClock() throws Exception {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var ctx = createContext(instanceId, "order-1", 0, false);
        var outcome = new AtomicReference<Object>();
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
        await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
        var row = store.findTimer(instanceId, "await-signal-1").orElseThrow();
        assertTrue(store.markTimerFired(row.id()));
        signalManager.onTimerTransition("order-1", "await-signal-1");
        worker.join(5_000);
        assertInstanceOf(SignalTimeoutException.class, outcome.get());
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    }

    // ── awaitSignal — the deadline's clock is injectable (R59) ─────────

    @Test
    @DisplayName("every shorter constructor arms deadlines on the system clock")
    void defaultInstantSource_isTheSystemClock() {
        assertSame(InstantSource.system(), signalManager.instantSource());
        assertSame(InstantSource.system(), new SignalManager(store, messaging, null, serializer, parkingLot,
                Duration.ofSeconds(1), EngineObserver.NOOP, TaskQueueGovernor.UNBOUNDED).instantSource());
    }

    @Test
    @DisplayName("a live await arms its deadline from the injected InstantSource and decides it there, not on the wall clock")
    void liveAwait_armsAndDecidesItsDeadlineOnTheInstantSource() throws Exception {
        // Frozen at the wall clock's "now": wall time then runs past the 100 ms
        // deadline while the source does not, so deciding the deadline on the
        // wall clock would time the await out.
        var armedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        signalManager = new SignalManager(store, messaging, null, serializer, parkingLot, Duration.ofMillis(50),
                EngineObserver.NOOP, TaskQueueGovernor.UNBOUNDED, InstantSource.fixed(armedAt));
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var ctx = createContext(instanceId, "order-1", 0, false);
        var outcome = new AtomicReference<Object>();
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofMillis(100), outcome);
        await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());

        var row = store.findTimer(instanceId, "await-signal-1").orElseThrow();
        assertEquals(armedAt.plusMillis(100), row.fireAt());
        assertEquals(armedAt.plusMillis(100), Instant.parse(
                store.getEventBySequence(instanceId, 1).orElseThrow().payload().get("deadline").stringValue()));

        // Five timeouts' worth of wall time, re-checked every 50 ms: the source
        // stands still, so the deadline has not passed and the await stays parked.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> outcome.get() == null);
        assertTrue(store.getEventBySequence(instanceId, 2).isEmpty(), "timed out on the wall clock");

        assertTrue(store.markTimerFired(row.id()));
        signalManager.onTimerTransition("order-1", "await-signal-1");
        worker.join(5_000);
        assertInstanceOf(SignalTimeoutException.class, outcome.get());
    }

    @Test
    @DisplayName("replay of a parked await resumes from the memoized deadline and reuses the durable row")
    void awaitSignalReplay_resumesFromTheMemoizedDeadline_withoutASecondTimerRow() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var deadline = Instant.now().plus(Duration.ofHours(1));
        var rowId = seedAwaitMemo(instanceId, 1, "payment.result", deadline, TimerStatus.PENDING); // appends TIMER_SCHEDULED at 1 + saves the row
        var ctx = createContext(instanceId, "order-1", 0, true);
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), new AtomicReference<>());
        await().atMost(Duration.ofSeconds(5)).until(() -> parkingLot.isParked("order-1:signal:payment.result"));
        assertEquals(1, store.getAllTimers().size(), "replay must reuse the durable row, never re-arm");
        assertEquals(rowId, store.getAllTimers().getFirst().id());
        worker.interrupt();
    }

    @Test
    @DisplayName("replay of an await whose row FIRED but whose outcome was never written heals to a timeout")
    void awaitSignalReplay_firedRowWithoutOutcome_healsToTimeout() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        seedAwaitMemo(instanceId, 1, "payment.result", Instant.now().minusSeconds(1), TimerStatus.FIRED);
        var ctx = createContext(instanceId, "order-1", 0, true);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class,
                        () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    }

    @Test
    @DisplayName("replay of an await whose row FIRED with no outcome — a visible signal still wins the open slot")
    void awaitSignalReplay_firedRowWithoutOutcome_visibleSignalWins() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        seedAwaitMemo(instanceId, 1, "payment.result", Instant.now().minusSeconds(1), TimerStatus.FIRED);
        signalManager.deliverSignal("order-1", "payment.result", "paid-late");
        var ctx = createContext(instanceId, "order-1", 0, true);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertEquals("paid-late", signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
        assertEquals(EventType.SIGNAL_RECEIVED, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
        assertTrue(store.getAllSignals().getFirst().consumed());
    }

    @Test
    @DisplayName("replay of a fully memoized await (TIMER_SCHEDULED + SIGNAL_TIMEOUT) needs no timer row")
    void awaitSignalReplay_memoizedOutcome_needsNoTimerRow() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 1, EventType.TIMER_SCHEDULED,
                "$maestro:awaitSignal:payment.result", awaitDetail("payment.result", "await-signal-1", Instant.EPOCH), Instant.now()));
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 2, EventType.SIGNAL_TIMEOUT,
                "$maestro:awaitSignal:payment.result", null, Instant.now()));
        var ctx = createContext(instanceId, "order-1", 0, true);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class,
                        () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
        assertEquals(0, store.getAllTimers().size());
        assertEquals(2, ctx.currentSequence());
    }

    @Test
    @DisplayName("awaitSignal with a zero timeout and no signal times out at once without a timer row (Review Focus 2)")
    void awaitSignalZeroTimeout_timesOutWithoutATimerRow() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class,
                        () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ZERO)));
        assertEquals(0, store.getAllTimers().size());
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 1).orElseThrow().eventType());
    }

    @Test
    @DisplayName("cancelling an await-signal-* timer row ends the await with SignalTimeoutException (Review Focus 3)")
    void cancelledAwaitTimer_timesOutTheAwait() throws Exception {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var ctx = createContext(instanceId, "order-1", 0, false);
        var outcome = new AtomicReference<Object>();
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
        await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
        assertTrue(store.markTimerCancelled(store.findTimer(instanceId, "await-signal-1").orElseThrow().id()));
        signalManager.onTimerTransition("order-1", "await-signal-1");
        worker.join(5_000);
        assertInstanceOf(SignalTimeoutException.class, outcome.get());
    }

    @Test
    @DisplayName("a live await reuses an existing row for its timer id and that row's fireAt — never a second row (M2)")
    void liveAwait_reusesAnExistingTimerRow_andItsDeadline() {
        // A crash between saveTimer and the TIMER_SCHEDULED memo (or a runner
        // race) left a PENDING row for this await's timer id with no memo.
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var seededFireAt = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MICROS);
        var rowId = UUID.randomUUID();
        store.saveTimer(new WorkflowTimer(rowId, instanceId, "order-1", "await-signal-1", seededFireAt,
                TimerStatus.PENDING, Instant.now()));
        var ctx = createContext(instanceId, "order-1", 0, false);

        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), new AtomicReference<>());
        await().atMost(Duration.ofSeconds(5)).until(() -> parkingLot.isParked("order-1:signal:payment.result"));

        assertEquals(1, store.getAllTimers().size(), "the existing row must be reused, not duplicated");
        assertEquals(rowId, store.getAllTimers().getFirst().id());
        var memo = store.getEventBySequence(instanceId, 1).orElseThrow();
        assertEquals(seededFireAt, Instant.parse(memo.payload().get("deadline").stringValue()),
                "the memoized deadline is the existing row's fireAt");
        worker.interrupt();
    }

    @Test
    @DisplayName("replay resumes from the MEMOIZED deadline, not Instant.now() + timeout (item 3)")
    void replay_timesOutAtTheMemoizedDeadline_notAFreshOne() throws Exception {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var rowId = seedAwaitMemo(instanceId, 1, "payment.result",
                Instant.now().plusMillis(200).truncatedTo(ChronoUnit.MICROS), TimerStatus.PENDING);
        var ctx = createContext(instanceId, "order-1", 0, true);
        var outcome = new AtomicReference<Object>();

        // The call's own timeout (1 h) must be ignored on replay.
        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
        worker.join(2_000);

        assertInstanceOf(SignalTimeoutException.class, outcome.get(),
                "the await must end at the memoized deadline (~200 ms), not in an hour");
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
        assertEquals(TimerStatus.FIRED, store.getAllTimers().stream()
                .filter(t -> t.id().equals(rowId)).findFirst().orElseThrow().status(),
                "the deadline self-fires its still-PENDING row");
    }

    @Test
    @DisplayName("replay of a past-deadline await with a PENDING row self-fires the row and times out at once (item 3)")
    void replay_pastDeadlinePendingRow_selfFiresAndTimesOut() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        seedAwaitMemo(instanceId, 1, "payment.result", Instant.now().minusSeconds(5), TimerStatus.PENDING);
        var ctx = createContext(instanceId, "order-1", 0, true);

        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertThrows(SignalTimeoutException.class,
                        () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));

        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
        assertEquals(TimerStatus.FIRED, store.getAllTimers().getFirst().status());
    }

    @Test
    @DisplayName("replay whose timer row is MISSING re-arms a row at the memoized deadline and keeps waiting (item 4)")
    void replay_missingTimerRow_rearmsAtTheMemoizedDeadline() {
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var deadline = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 1, EventType.TIMER_SCHEDULED,
                "$maestro:awaitSignal:payment.result", awaitDetail("payment.result", "await-signal-1", deadline),
                Instant.now()));
        var ctx = createContext(instanceId, "order-1", 0, true);
        var outcome = new AtomicReference<Object>();

        var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
        await().atMost(Duration.ofSeconds(5)).until(() -> parkingLot.isParked("order-1:signal:payment.result"));

        assertNull(outcome.get(), "a missing row is not a spent deadline — the await must keep waiting");
        assertTrue(store.getEventBySequence(instanceId, 2).isEmpty());
        var row = store.findTimer(instanceId, "await-signal-1").orElseThrow(
                () -> new AssertionError("the deadline row must be re-armed"));
        assertEquals(TimerStatus.PENDING, row.status());
        assertEquals(deadline, row.fireAt(), "re-armed at the memoized deadline");

        // ...and the re-armed row arbitrates like the original.
        assertTrue(store.markTimerFired(row.id()));
        signalManager.onTimerTransition("order-1", "await-signal-1");
        await().atMost(Duration.ofSeconds(5)).until(() -> outcome.get() != null);
        assertInstanceOf(SignalTimeoutException.class, outcome.get());
        worker.interrupt();
    }

    // ── awaitSignal — store errors inside the timeout decision (R39) ───

    @Test
    @DisplayName("a store error on the deadline self-fire does not fail the await — it still times out (R39)")
    void selfFireStoreError_stillTimesOut() throws Exception {
        var faulty = useDeadlineFaultStore();
        faulty.failNextSelfFire = true;
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var outcome = new AtomicReference<Object>();

        var worker = awaitInBackground(createContext(instanceId, "order-1", 0, false), "payment.result",
                Duration.ofMillis(200), outcome);
        worker.join(5_000);

        assertEquals(1, faulty.injected.get(), "the fault must actually have been hit");
        assertInstanceOf(SignalTimeoutException.class, outcome.get(),
                "a store blip at the deadline must not escape into workflow code");
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    }

    @Test
    @DisplayName("a store error on the post-deadline signal re-read re-parks and retries — it still times out (R39)")
    void lateSignalReadStoreError_reparksAndStillTimesOut() throws Exception {
        var faulty = useDeadlineFaultStore();
        faulty.failLateReadAfterSelfFire = true;
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        var outcome = new AtomicReference<Object>();

        var worker = awaitInBackground(createContext(instanceId, "order-1", 0, false), "payment.result",
                Duration.ofMillis(200), outcome);
        worker.join(5_000);

        assertEquals(1, faulty.injected.get(), "the fault must actually have been hit");
        assertInstanceOf(SignalTimeoutException.class, outcome.get());
        assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    }

    @Test
    @DisplayName("a signal arriving during a post-deadline store error still wins the open slot (R39)")
    void lateSignalReadStoreError_signalArrivingMeanwhileWins() throws Exception {
        var faulty = useDeadlineFaultStore();
        faulty.failLateReadAfterSelfFire = true;
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
        faulty.onLateReadFailure = () -> faulty.saveSignal(new WorkflowSignal(UUID.randomUUID(), instanceId,
                "order-1", "payment.result", serializer.serialize("paid-during-blip"), false, Instant.now()));
        var outcome = new AtomicReference<Object>();

        var worker = awaitInBackground(createContext(instanceId, "order-1", 0, false), "payment.result",
                Duration.ofMillis(200), outcome);
        worker.join(5_000);

        assertEquals(1, faulty.injected.get());
        assertEquals("paid-during-blip", outcome.get());
        assertEquals(EventType.SIGNAL_RECEIVED, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    }

    // ── concurrent waiters share one acknowledged subscribe (item 23) ──

    @Test
    @DisplayName("a second concurrent waiter does not park before the first waiter's subscribe is acknowledged")
    void secondWaiter_waitsForTheFirstWaitersSubscribe() throws Exception {
        var release = new CountDownLatch(1);
        var subscribeEntered = new CountDownLatch(1);
        var notifier = new SubscribingNotifier() {
            @Override
            public void subscribe(String workflowId, SignalCallback callback) {
                subscribeEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS); // the server has not acknowledged yet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.subscribe(workflowId, callback);
            }
        };
        signalManager = new SignalManager(store, messaging, notifier, serializer, parkingLot);
        var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);

        var first = awaitInBackground(createContext(instanceId, "order-1", 0, false), "signal-a",
                Duration.ofSeconds(30), new AtomicReference<>());
        assertTrue(subscribeEntered.await(5, TimeUnit.SECONDS));
        var second = awaitInBackground(createContext(instanceId, "order-1", 10, false), "signal-b",
                Duration.ofSeconds(30), new AtomicReference<>());

        // The second waiter must be held in subscribeForWake: it writes no
        // deadline row and does not park while the subscribe is unacknowledged.
        Thread.sleep(500);
        assertTrue(store.findTimer(instanceId, "await-signal-11").isEmpty(),
                "the second waiter passed subscribeForWake before the subscription was acknowledged");
        assertFalse(parkingLot.isParked("order-1:signal:signal-b"));

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                parkingLot.isParked("order-1:signal:signal-a") && parkingLot.isParked("order-1:signal:signal-b"));
        assertEquals(List.of("order-1"), notifier.subscribeCalls, "still one physical subscription");
        first.interrupt();
        second.interrupt();
    }

    /** Swaps in a {@link DeadlineFaultStore} and a SignalManager with a 100 ms re-check. */
    private DeadlineFaultStore useDeadlineFaultStore() {
        var faulty = new DeadlineFaultStore();
        store = faulty;
        signalManager = new SignalManager(store, messaging, null, serializer, parkingLot, Duration.ofMillis(100));
        return faulty;
    }

    /**
     * Throws once from the timeout decision's own store calls: the deadline
     * self-fire ({@code markTimerFired}), or the signal re-read that follows
     * it. The per-pass probe reads before the decision are left alone — they
     * are already advisory (Issue 20).
     */
    private static final class DeadlineFaultStore extends InMemoryWorkflowStore {
        volatile boolean failNextSelfFire;
        volatile boolean failLateReadAfterSelfFire;
        volatile @org.jspecify.annotations.Nullable Runnable onLateReadFailure;
        private volatile boolean selfFireSeen;
        final java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public synchronized boolean markTimerFired(UUID timerId) {
            selfFireSeen = true;
            if (failNextSelfFire) {
                failNextSelfFire = false;
                injected.incrementAndGet();
                throw new RuntimeException("store blip at the deadline (self-fire, injected)");
            }
            return super.markTimerFired(timerId);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            if (failLateReadAfterSelfFire && selfFireSeen) {
                failLateReadAfterSelfFire = false;
                injected.incrementAndGet();
                var hook = onLateReadFailure;
                if (hook != null) {
                    hook.run();
                }
                throw new RuntimeException("store blip at the deadline (late signal read, injected)");
            }
            return super.getUnconsumedSignals(workflowId, signalName);
        }
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
    @DisplayName("a wake on a row another runner finalised (COMPLETED) stands down as stale WITHOUT consuming "
            + "the signal (M7 / R30)")
    void wakeOnCompletedRow_standsDownWithoutConsumingTheSignal() throws Exception {
        var sm = new SignalManager(store, messaging, null, serializer, parkingLot, Duration.ofMillis(100));
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        var ctx = createContext(instanceId, "order-1", 0, false);
        Thread.ofVirtual().start(() -> {
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                try {
                    sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(30));
                } catch (Throwable t) {
                    outcome.set(t);
                } finally {
                    done.countDown();
                }
            });
        });
        await().atMost(Duration.ofSeconds(2)).until(() -> parkingLot.isParked("order-1:signal:payment.result"));

        // Another runner finished the workflow; then a signal for it lands.
        store.updateInstance(store.getInstance("order-1").orElseThrow().toBuilder()
                .status(WorkflowStatus.COMPLETED).build());
        store.saveSignal(new WorkflowSignal(UUID.randomUUID(), instanceId, "order-1",
                "payment.result", serializer.serialize("late"), false, Instant.now()));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        var stale = assertInstanceOf(io.b2mash.maestro.core.exception.StaleRunException.class, outcome.get());
        assertEquals(WorkflowStatus.COMPLETED, stale.observedStatus());
        assertEquals(1, store.getUnconsumedSignals("order-1", "payment.result").size(),
                "a stale run must not consume a signal of a workflow another runner finished");
        assertTrue(store.getEvents(instanceId).stream().noneMatch(e -> e.eventType() == EventType.SIGNAL_RECEIVED),
                "no SIGNAL_RECEIVED may be memoized by the stale run");
        assertEquals(io.b2mash.maestro.core.context.RunCancellation.Cause.STALE, ctx.cancellation().cause(),
                "the wake's stand-down is sticky on the run (round 2, R33)");
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

    // ── Durable trace context (design §4.3) ────────────────────────────

    /**
     * The raw W3C {@code traceparent} used throughout the trace-context tests
     * — the example value from the W3C Trace Context recommendation.
     */
    private static final String TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    @DisplayName("deliverSignal persists TraceContextHolder.current() on the signal row")
    void deliverSignalPersistsTraceContext() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // The transport listener thread sets the holder before calling in —
        // deliverSignal runs synchronously on that same thread (design §4.3(a)).
        TraceContextHolder.runWith(TRACEPARENT, () ->
                signalManager.deliverSignal("order-1", "payment.result", "paid"));

        var rows = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, rows.size());
        assertEquals(TRACEPARENT, rows.getFirst().traceContext(),
                "the signal row must carry the traceparent the listener thread was under");
    }

    @Test
    @DisplayName("deliverSignal with no trace context persists null — absence is never an error")
    void deliverSignalWithoutTraceContextPersistsNull() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        signalManager.deliverSignal("order-1", "payment.result", "paid");

        var rows = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, rows.size());
        assertNull(rows.getFirst().traceContext());
    }

    @Test
    @DisplayName("signalPersisted carries the captured traceparent on SignalInfo")
    void signalPersistedCarriesTraceContext() {
        var observer = new RecordingEngineObserver();
        var sm = new SignalManager(store, messaging, null, serializer, parkingLot,
                Duration.ofSeconds(30), observer);
        createInstance("order-1", UUID.randomUUID());

        TraceContextHolder.runWith(TRACEPARENT, () ->
                sm.deliverSignal("order-1", "payment.result", "paid"));

        assertEquals(1, observer.signalPersisted().size());
        assertEquals(TRACEPARENT, observer.signalPersisted().getFirst().traceContext());
    }

    @Test
    @DisplayName("consumeSignal surfaces the row's traceContext on SignalInfo — the durable hop")
    void consumeSignalSurfacesDurableTraceContext() {
        var observer = new RecordingEngineObserver();
        var sm = new SignalManager(store, messaging, null, serializer, parkingLot,
                Duration.ofSeconds(30), observer);
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // The row was written by a *different* thread (possibly a different
        // node) — the holder is deliberately not set here.
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "order-1", "payment.result",
                serializer.serialize("paid"), false, Instant.now(), TRACEPARENT));

        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10)));

        var live = observer.signalConsumed().stream().filter(c -> !c.replayed()).toList();
        assertEquals(1, live.size());
        assertEquals(TRACEPARENT, live.getFirst().info().traceContext(),
                "the durable row's trace context must reach the observer at consume time");
        assertNull(TraceContextHolder.current(),
                "consuming must not leave the workflow thread's holder set");
    }

    @Test
    @DisplayName("a replayed SIGNAL_RECEIVED carries no trace context — no spans on replay")
    void replayedSignalCarriesNoTraceContext() {
        var observer = new RecordingEngineObserver();
        var sm = new SignalManager(store, messaging, null, serializer, parkingLot,
                Duration.ofSeconds(30), observer);
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), instanceId, 1, EventType.SIGNAL_RECEIVED,
                "$maestro:awaitSignal:payment.result", serializer.serialize("paid"), Instant.now()));

        var ctx = createContext(instanceId, "order-1", 0, true);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                sm.awaitSignal(ctx, "payment.result", String.class, Duration.ofSeconds(10)));

        assertEquals(1, observer.signalConsumed().size());
        assertTrue(observer.signalConsumed().getFirst().replayed());
        assertNull(observer.signalConsumed().getFirst().info().traceContext());
    }

    @Test
    @DisplayName("an over-long trace context set directly via the holder is dropped, not persisted "
            + "— the signal is still stored and consumable")
    void oversizedTraceContextIsDroppedNotPersisted() {
        var instanceId = UUID.randomUUID();
        createInstance("order-1", instanceId);

        // TraceContextHolder.set is public API: a future traced transport, or an
        // embedder, can put anything here. A value wider than the column must
        // never be able to fail the insert — that would strand the signal.
        var oversized = "00-" + "a".repeat(TraceContextHolder.MAX_LENGTH * 4);
        TraceContextHolder.runWith(oversized, () ->
                signalManager.deliverSignal("order-1", "payment.result", "paid"));

        var rows = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, rows.size(), "the signal must still be persisted");
        assertNull(rows.getFirst().traceContext(),
                "the over-long value degrades to no trace context, never to a failed write");

        // And it is still consumable — the signal was not damaged by the drop.
        var ctx = createContext(instanceId, "order-1", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
                assertEquals("paid", signalManager.awaitSignal(
                        ctx, "payment.result", String.class, Duration.ofSeconds(10))));
    }

    @Test
    @DisplayName("a trace context exactly at the limit is still persisted — the guard bounds, "
            + "it does not truncate the legitimate range")
    void traceContextAtTheLimitIsPersisted() {
        createInstance("order-1", UUID.randomUUID());
        var atLimit = "0".repeat(TraceContextHolder.MAX_LENGTH);

        TraceContextHolder.runWith(atLimit, () ->
                signalManager.deliverSignal("order-1", "payment.result", "paid"));

        assertEquals(atLimit,
                store.getUnconsumedSignals("order-1", "payment.result").getFirst().traceContext());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Runs {@code awaitSignal} under the workflow scoped value on a new
     * virtual thread, storing the returned payload or the thrown exception.
     */
    private Thread awaitInBackground(WorkflowContext ctx, String name, Duration timeout,
                                     AtomicReference<Object> outcome) {
        return Thread.ofVirtual().start(() ->
                ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
                    try {
                        outcome.set(signalManager.awaitSignal(ctx, name, String.class, timeout));
                    } catch (Throwable t) {
                        outcome.set(t);
                    }
                }));
    }

    /** The {@code TIMER_SCHEDULED} payload a parked await memoizes. */
    private JsonNode awaitDetail(String signalName, String timerId, Instant deadline) {
        return serializer.serialize(new SignalManager.AwaitSignalDetail(
                signalName, timerId, Duration.ofHours(1).toString(), deadline.toString()));
    }

    /**
     * Seeds the history of a parked await at {@code seq}: the
     * {@code TIMER_SCHEDULED} memo plus its {@code await-signal-<seq>} row.
     *
     * @return the timer row's database id
     */
    private UUID seedAwaitMemo(UUID instanceId, int seq, String signalName, Instant deadline,
                               TimerStatus rowStatus) {
        var timerId = "await-signal-" + seq;
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, seq, EventType.TIMER_SCHEDULED,
                "$maestro:awaitSignal:" + signalName, awaitDetail(signalName, timerId, deadline), Instant.now()));
        var rowId = UUID.randomUUID();
        store.saveTimer(new WorkflowTimer(rowId, instanceId, "order-1", timerId, deadline, rowStatus,
                Instant.now()));
        return rowId;
    }

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

        List<WorkflowTimer> getAllTimers() {
            return List.copyOf(timers);
        }

        List<WorkflowSignal> getAllSignals() {
            return List.copyOf(signals);
        }

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
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            // Test fixture: no CAS and one writer per instance, so composing the two
            // writes is atomic enough here. Event first, so a duplicate writes nothing.
            appendEvent(terminalEvent);
            updateInstance(terminal);
            return true;
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
        public int deleteFailureEvents(UUID instanceId) {
            return removeFailureEvents(events, instanceId);
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
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            return 0;
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
        public synchronized boolean markTimerFired(UUID timerId) {
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
        public synchronized boolean markTimerCancelled(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(
                            t.id(), t.workflowInstanceId(), t.workflowId(), t.timerId(),
                            t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return true;
                }
            }
            return false;
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

    /**
     * Throws from {@code publish}. It records the hand-off thread and installs
     * an uncaught-exception handler on it, so a test can join that thread and
     * tell whether the engine caught the failure (nothing escapes) or not.
     */
    private static class FailingNotifier implements SignalNotifier {

        final AtomicReference<Thread> publishThread = new AtomicReference<>();
        final CopyOnWriteArrayList<Throwable> escaped = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String workflowId, String signalName) {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> escaped.add(e));
            publishThread.set(Thread.currentThread());
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
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
