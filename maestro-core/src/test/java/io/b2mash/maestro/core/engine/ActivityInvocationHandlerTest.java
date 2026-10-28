package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.core.spi.SignalMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.ScopedValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ActivityInvocationHandler} — the core hybrid memoization proxy.
 */
class ActivityInvocationHandlerTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final String WORKFLOW_ID = "test-workflow-1";
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InMemoryWorkflowStore store;
    private RecordingMessaging messaging;
    private RecordingLock lock;
    private PayloadSerializer serializer;
    private RetryExecutor retryExecutor;
    private ActivityProxyFactory factory;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        messaging = new RecordingMessaging();
        lock = new RecordingLock();
        serializer = new PayloadSerializer(MAPPER);
        retryExecutor = new RetryExecutor();
        factory = new ActivityProxyFactory();
    }

    /**
     * Creates a default WorkflowContext for tests.
     */
    private WorkflowContext createContext() {
        return new WorkflowContext(
                INSTANCE_ID, WORKFLOW_ID, RUN_ID,
                "TestWorkflow", "test-queue", "test-service",
                0, true
        );
    }

    /**
     * Runs the given block within a ScopedValue-bound WorkflowContext.
     */
    private void withContext(Runnable block) {
        ScopedValue.where(WorkflowContext.scopedValue(), createContext()).run(block);
    }

    // ── Replay path tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Replay: returns stored result when ACTIVITY_COMPLETED event exists")
    void replayCompletedActivity() {
        // Pre-populate memoization log with a completed event at seq 1
        var payload = MAPPER.valueToTree(new Greeting("Hello, World!"));
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                payload, Instant.now()
        ));
        // Clear the tracking list so we only see events from the proxy
        store.appendedEvents.clear();

        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            var result = proxy.greet("World");

            assertEquals("Hello, World!", result.message());
            // Activity should NOT have been invoked (replay)
            assertTrue(store.appendedEvents.isEmpty(),
                    "No new events should be appended during replay");
        });
    }

    @Test
    @DisplayName("Replay: throws ActivityExecutionException when ACTIVITY_FAILED event exists")
    void replayFailedActivity() {
        // Pre-populate with a failed event
        var errorPayload = MAPPER.valueToTree(
                Map.of("exceptionType", "java.lang.RuntimeException", "message", "Simulated failure"));
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_FAILED, "GreetingActivities.greet",
                errorPayload, Instant.now()
        ));

        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

            var ex = assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));
            assertTrue(ex.getMessage().contains("Simulated failure"));
            assertInstanceOf(RuntimeException.class, ex.getCause());
            assertEquals(RuntimeException.class, ex.getCause().getClass());
        });
    }

    // ── Live execution path tests ─────────────────────────────────────

    @Test
    @DisplayName("Live: executes activity and persists ACTIVITY_COMPLETED event")
    void liveExecutionSuccess() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            var result = proxy.greet("World");

            assertEquals("Hello, World!", result.message());

            // Verify event was persisted
            assertEquals(1, store.appendedEvents.size());
            var event = store.appendedEvents.getFirst();
            assertEquals(EventType.ACTIVITY_COMPLETED, event.eventType());
            assertEquals(1, event.sequenceNumber());
            assertEquals("GreetingActivities.greet", event.stepName());
            assertNotNull(event.payload());
        });
    }

    @Test
    @DisplayName("Live: handles void activity methods")
    void liveExecutionVoid() {
        withContext(() -> {
            var impl = new GreetingActivitiesImpl();
            var proxy = createProxy(GreetingActivities.class, impl);

            assertDoesNotThrow(() -> proxy.doNothing());

            assertEquals(1, store.appendedEvents.size());
            var event = store.appendedEvents.getFirst();
            assertEquals(EventType.ACTIVITY_COMPLETED, event.eventType());
        });
    }

    @Test
    @DisplayName("Live: persists ACTIVITY_FAILED when retries exhausted")
    void liveExecutionFailure() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new FailingActivities());

            assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));

            // Should have an ACTIVITY_FAILED event
            var failedEvents = store.appendedEvents.stream()
                    .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED)
                    .toList();
            assertEquals(1, failedEvents.size());
        });
    }

    @Test
    @DisplayName("Live: sequence numbers increment across multiple calls")
    void sequenceNumbering() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

            proxy.greet("A");
            proxy.greet("B");
            proxy.greet("C");

            assertEquals(3, store.appendedEvents.size());
            assertEquals(1, store.appendedEvents.get(0).sequenceNumber());
            assertEquals(2, store.appendedEvents.get(1).sequenceNumber());
            assertEquals(3, store.appendedEvents.get(2).sequenceNumber());
        });
    }

    // ── Idempotency tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Stale run: losing the append race rethrows DuplicateEventException instead of adopting the winner's value")
    void duplicateEventOnAppend_standsDownInsteadOfAdopting() {
        store.preloadForDuplication(1, new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                MAPPER.valueToTree(new Greeting("winner")), Instant.now()));
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            var thrown = assertThrows(DuplicateEventException.class, () -> proxy.greet("World"));
            assertEquals(1, thrown.sequenceNumber());
        });
        assertTrue(messaging.publishedEvents.stream().noneMatch(e -> e.eventType() == LifecycleEventType.ACTIVITY_COMPLETED),
                "the loser must not announce a completion it did not persist");
    }

    @Test
    @DisplayName("Stale run: a loser whose own attempt FAILED after the winner completed does not persist or surface that failure")
    void duplicateEventOnFailureAppend_standsDown() {
        // Review Focus 4: the loser never reports a failure that contradicts the winner.
        store.preloadForDuplication(1, new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                MAPPER.valueToTree(new Greeting("winner")), Instant.now()));
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new FailingActivities(), RetryPolicy.noRetry(),
                    Duration.ofSeconds(30));
            assertThrows(DuplicateEventException.class, () -> proxy.greet("World"),
                    "must not surface ActivityExecutionException — the winner's COMPLETED is the truth");
        });
        assertTrue(messaging.publishedEvents.stream().noneMatch(e -> e.eventType() == LifecycleEventType.ACTIVITY_FAILED));
        assertTrue(store.appendedEvents.isEmpty(), "the loser persisted nothing");
    }

    @Test
    @DisplayName("Stale run: after one collision, a SWALLOWED DuplicateEventException does not let the next "
            + "live activity run — it throws StaleRunException before executing anything (R29)")
    void swallowedDuplicate_nextLiveActivityStandsDownBeforeExecuting() {
        store.preloadForDuplication(1, new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                MAPPER.valueToTree(new Greeting("winner")), Instant.now()));
        var calls = new AtomicInteger();
        var observer = new RecordingEngineObserver();
        GreetingActivities counting = new GreetingActivitiesImpl() {
            @Override
            public Greeting greet(String name) {
                calls.incrementAndGet();
                return super.greet(name);
            }
        };
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, counting, RetryPolicy.noRetry(),
                    Duration.ofSeconds(30), observer);
            try {
                proxy.greet("first");
            } catch (DuplicateEventException swallowed) {
                // An author's catch-and-continue — exactly what the stand-down contract forbids.
            }
            assertEquals(1, calls.get());
            var thrown = assertThrows(StaleRunException.class, () -> proxy.greet("second"),
                    "the engine must stop the next live activity of a run it already knows is stale");
            assertEquals(WORKFLOW_ID, thrown.workflowId());
            assertEquals("append collision at sequence 1", thrown.detail());
        });
        assertEquals(1, calls.get(), "the second activity must not execute — the winner runs that side effect");
        assertEquals(1, lock.acquiredKeys.size(), "no activity lock for the stood-down call");
        assertEquals(1, messaging.publishedEvents.stream()
                        .filter(e -> e.eventType() == LifecycleEventType.ACTIVITY_STARTED).count(),
                "no ACTIVITY_STARTED for the stood-down call");
        assertEquals(1, observer.callbackNames().stream().filter("activityStarted"::equals).count(),
                "no activityStarted (no span opened) for the stood-down call: " + observer.callbackNames());
        assertTrue(store.appendedEvents.isEmpty(), "the stale run persisted nothing");
    }

    // ── Lock behavior tests ───────────────────────────────────────────

    @Test
    @DisplayName("Lock: proceeds without lock when lock acquisition fails")
    void lockAcquisitionFailure() {
        lock.failAcquisition = true;
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

            // Should still succeed — lock is optimization, not correctness
            var result = proxy.greet("World");
            assertEquals("Hello, World!", result.message());
        });
    }

    @Test
    @DisplayName("Lock: proceeds when lock backend throws exception")
    void lockBackendError() {
        lock.throwOnAcquire = true;
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

            var result = proxy.greet("World");
            assertEquals("Hello, World!", result.message());
        });
    }

    @Test
    @DisplayName("Lock: acquires and releases lock on success")
    void lockLifecycle() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            proxy.greet("World");

            assertEquals(1, lock.acquiredKeys.size());
            assertTrue(lock.acquiredKeys.getFirst().contains("maestro:lock:activity:" + WORKFLOW_ID));
            assertEquals(1, lock.releasedCount);
        });
    }

    @Test
    @DisplayName("Lock: releases lock even when activity fails")
    void lockReleasedOnFailure() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new FailingActivities());

            assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));
            assertEquals(1, lock.releasedCount);
        });
    }

    @Test
    @DisplayName("Lock: honours a custom lock key prefix (maestro.lock.key-prefix)")
    void lockHonoursCustomKeyPrefix() {
        withContext(() -> {
            var proxy = createProxyWithLockPrefix(
                    GreetingActivities.class, new GreetingActivitiesImpl(), "custom:prefix:");
            proxy.greet("World");

            assertEquals(1, lock.acquiredKeys.size());
            assertEquals("custom:prefix:activity:" + WORKFLOW_ID + ":1", lock.acquiredKeys.getFirst(),
                    "the activity lock key must honour the configured prefix exactly like the instance lock");
        });
    }

    // ── Messaging behavior tests ──────────────────────────────────────

    @Test
    @DisplayName("Messaging: publishes ACTIVITY_STARTED and ACTIVITY_COMPLETED lifecycle events")
    void lifecycleEventsOnSuccess() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            proxy.greet("World");

            assertEquals(2, messaging.publishedEvents.size());
            assertEquals(LifecycleEventType.ACTIVITY_STARTED, messaging.publishedEvents.get(0).eventType());
            assertEquals(LifecycleEventType.ACTIVITY_COMPLETED, messaging.publishedEvents.get(1).eventType());
        });
    }

    @Test
    @DisplayName("Messaging: publishes ACTIVITY_FAILED lifecycle event on failure")
    void lifecycleEventsOnFailure() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new FailingActivities());

            assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));

            var failedEvents = messaging.publishedEvents.stream()
                    .filter(e -> e.eventType() == LifecycleEventType.ACTIVITY_FAILED)
                    .toList();
            assertEquals(1, failedEvents.size());
        });
    }

    @Test
    @DisplayName("Messaging: messaging failure does not interrupt workflow")
    void messagingFailureDoesNotBlock() {
        messaging.throwOnPublish = true;
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

            // Should still succeed — messaging is best-effort
            var result = proxy.greet("World");
            assertEquals("Hello, World!", result.message());
        });
    }

    @Test
    @DisplayName("Messaging: works without messaging (null)")
    void nullMessaging() {
        withContext(() -> {
            var proxy = createProxyWithoutMessaging(GreetingActivities.class, new GreetingActivitiesImpl());

            var result = proxy.greet("World");
            assertEquals("Hello, World!", result.message());
        });
    }

    // ── Object method pass-through ────────────────────────────────────

    @Test
    @DisplayName("Object methods: toString returns proxy description")
    void toStringReturnsProxyDescription() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            assertEquals("ActivityProxy[GreetingActivities]", proxy.toString());
        });
    }

    @Test
    @DisplayName("Object methods: hashCode returns identity hash")
    void hashCodeReturnsIdentityHash() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        });
    }

    @Test
    @DisplayName("Object methods: equals uses reference equality")
    void equalsUsesReferenceEquality() {
        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            assertEquals(proxy, proxy);
            assertNotEquals(proxy, createProxy(GreetingActivities.class, new GreetingActivitiesImpl()));
        });
    }

    // ── Replay state tests ────────────────────────────────────────────

    @Test
    @DisplayName("Replaying flag: set to false after first live execution")
    void replayingFlagClearedOnLiveExecution() {
        withContext(() -> {
            assertTrue(WorkflowContext.current().isReplaying());

            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            proxy.greet("World");

            assertFalse(WorkflowContext.current().isReplaying());
        });
    }

    @Test
    @DisplayName("Replaying flag: stays true during replay")
    void replayingFlagStaysTrueDuringReplay() {
        // Pre-populate memoization log
        store.appendEvent(new WorkflowEvent(
                UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                MAPPER.valueToTree(new Greeting("stored")), Instant.now()
        ));
        store.appendedEvents.clear();

        withContext(() -> {
            var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
            proxy.greet("World");

            assertTrue(WorkflowContext.current().isReplaying());
        });
    }

    @Test
    @DisplayName("Custom @Activity name: uses annotation name in step key")
    void customActivityName() {
        withContext(() -> {
            var proxy = createProxy(CustomNamedActivities.class, new CustomNamedActivitiesImpl());
            proxy.doWork();

            var event = store.appendedEvents.getFirst();
            assertEquals("custom-activities.doWork", event.stepName());
        });
    }

    // ── startToCloseTimeout enforcement (C11) ─────────────────────────

    @Test
    @DisplayName("Timeout: an attempt exceeding startToCloseTimeout is cancelled and recorded as ActivityTimeoutException")
    void liveAttemptExceedingTimeout_isRecordedAsTimeout() {
        withContext(() -> {
            var proxy = createProxy(SlowActivities.class, new SlowActivitiesImpl(Duration.ofSeconds(2), false),
                    RetryPolicy.noRetry(), Duration.ofMillis(100));
            var start = System.nanoTime();
            var thrown = assertThrows(ActivityExecutionException.class, proxy::slow);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0,
                    "must give up at the timeout, not wait for the attempt");
            assertTrue(thrown.causedBy(ActivityTimeoutException.class));
            assertEquals(1, store.appendedEvents.size());
            assertEquals(EventType.ACTIVITY_FAILED, store.appendedEvents.get(0).eventType());
            assertEquals(ActivityTimeoutException.class.getName(),
                    store.appendedEvents.get(0).payload().get("exceptionType").stringValue());
        });
    }

    @Test
    @DisplayName("Timeout: a timed-out attempt is retried under the policy, and a fast second attempt succeeds")
    void timedOutAttempt_isRetriedLikeAnyFailure() {
        var calls = new AtomicInteger();
        SlowActivities impl = () -> calls.incrementAndGet() == 1 ? sleepThenReturn(Duration.ofSeconds(2)) : "fast";
        withContext(() -> {
            var proxy = createProxy(SlowActivities.class, impl,
                    RetryPolicy.builder().maxAttempts(2).initialInterval(Duration.ofMillis(1)).build(),
                    Duration.ofMillis(100));
            assertEquals("fast", proxy.slow());
            assertEquals(2, calls.get());
            assertEquals(1, store.appendedEvents.size());
            assertEquals(EventType.ACTIVITY_COMPLETED, store.appendedEvents.get(0).eventType());
        });
    }

    @Test
    @DisplayName("Timeout: nonRetryableExceptions = ActivityTimeoutException makes a timeout terminal after one attempt")
    void timeoutListedNonRetryable_failsAfterOneAttempt() {
        var calls = new AtomicInteger();
        withContext(() -> {
            var proxy = createProxy(SlowActivities.class, () -> { calls.incrementAndGet(); return sleepThenReturn(Duration.ofSeconds(2)); },
                    RetryPolicy.builder().maxAttempts(5).initialInterval(Duration.ofMillis(1))
                            .nonRetryableExceptions(List.of(ActivityTimeoutException.class)).build(),
                    Duration.ofMillis(100));
            assertThrows(ActivityExecutionException.class, proxy::slow);
            assertEquals(1, calls.get());
        });
    }

    @Test
    @DisplayName("Timeout: a late value from an attempt that ignored interruption is never memoized")   // Review Focus 3
    void lateValueAfterTimeout_isNeverMemoized() throws Exception {
        var zombie = new SlowActivitiesImpl(Duration.ofMillis(400), true);
        withContext(() -> {
            var proxy = createProxy(SlowActivities.class, zombie,
                    RetryPolicy.noRetry(), Duration.ofMillis(50));
            assertThrows(ActivityExecutionException.class, proxy::slow);
        });
        Thread.sleep(700);   // let the uninterruptible attempt finish and try to "return"
        assertTrue(zombie.returned.await(5, TimeUnit.SECONDS),
                "the uninterruptible attempt must actually have run to its return, or this pin is vacuous");
        assertEquals(1, store.appendedEvents.size(), "exactly one event at this sequence");
        assertEquals(EventType.ACTIVITY_FAILED, store.appendedEvents.get(0).eventType(),
                "the late 'late' value must not be persisted over the recorded timeout");
    }

    @Test
    @DisplayName("Terminate requested before the call: no lock, no ACTIVITY_STARTED, no attempt (R22 part 3)")
    void terminateBeforeCall_publishesNoActivityStarted() {
        var calls = new AtomicInteger();
        var observer = new RecordingEngineObserver();
        withContext(() -> {
            WorkflowContext.current().cancellation().requestTermination("operator");
            var proxy = createProxy(SlowActivities.class, () -> { calls.incrementAndGet(); return "ran"; },
                    RetryPolicy.noRetry(), Duration.ofSeconds(30), observer);
            assertThrows(WorkflowTerminatedException.class, proxy::slow);
        });
        assertTrue(messaging.publishedEvents.isEmpty(),
                "a terminated run must not publish ACTIVITY_STARTED (or open an activity span)");
        assertTrue(observer.invocations().isEmpty(),
                "no observer callback either — in particular no activityStarted: " + observer.callbackNames());
        assertTrue(lock.acquiredKeys.isEmpty(), "no activity lock for a terminated run");
        assertTrue(store.appendedEvents.isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("Attempt thread: the observer's wrapActivityAttempt wrapper runs on the attempt thread, "
            + "around the activity (R26)")
    void observerWrapperRunsAroundTheAttemptOnTheAttemptThread() {
        var workflowThread = new AtomicReference<Thread>();
        var wrapperThread = new AtomicReference<Thread>();
        var activityThread = new AtomicReference<Thread>();
        var wrappedInfo = new AtomicReference<ActivityInfo>();
        var observer = new EngineObserver() {
            @Override
            public <T> Callable<T> wrapActivityAttempt(ActivityInfo a, Callable<T> attempt) {
                wrappedInfo.set(a);
                return () -> {
                    wrapperThread.set(Thread.currentThread());
                    return attempt.call();
                };
            }
        };
        withContext(() -> {
            workflowThread.set(Thread.currentThread());
            var proxy = createProxy(SlowActivities.class, () -> {
                activityThread.set(Thread.currentThread());
                return "ok";
            }, RetryPolicy.noRetry(), Duration.ofSeconds(30), observer);
            assertEquals("ok", proxy.slow());
        });
        assertEquals("SlowActivities.slow", wrappedInfo.get().activityName());
        assertEquals(1, wrappedInfo.get().sequenceNumber());
        assertNotSame(workflowThread.get(), wrapperThread.get(), "the wrapper must run on the attempt thread");
        assertSame(wrapperThread.get(), activityThread.get(), "the wrapper must run around the activity");
    }

    @Test
    @DisplayName("Constructor: a zero or negative startToCloseTimeout is rejected up front")
    void nonPositiveTimeout_isRejected() {
        var zero = assertThrows(IllegalArgumentException.class, () ->
                createProxy(SlowActivities.class, () -> "x", RetryPolicy.noRetry(), Duration.ZERO));
        assertTrue(zero.getMessage().contains("startToCloseTimeout must be positive"), zero.getMessage());
        assertThrows(IllegalArgumentException.class, () ->
                createProxy(SlowActivities.class, () -> "x", RetryPolicy.noRetry(), Duration.ofSeconds(-1)));
    }

    // ── Helper methods ────────────────────────────────────────────────

    private <T> T createProxy(Class<T> iface, T impl) {
        return factory.createProxy(
                iface, impl, store, lock, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor
        );
    }

    private <T> T createProxy(Class<T> iface, T impl, RetryPolicy policy, Duration startToCloseTimeout,
                              EngineObserver observer) {
        return factory.createProxy(
                iface, impl, store, lock, messaging,
                policy, startToCloseTimeout,
                serializer, retryExecutor, WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer
        );
    }

    private <T> T createProxy(Class<T> iface, T impl, RetryPolicy policy, Duration startToCloseTimeout) {
        return factory.createProxy(
                iface, impl, store, lock, messaging,
                policy, startToCloseTimeout,
                serializer, retryExecutor
        );
    }

    /** Sleeps for {@code d}; an interrupt (the timeout's cancel) surfaces as a failure. */
    private static String sleepThenReturn(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        return "slow";
    }

    private <T> T createProxyWithoutMessaging(Class<T> iface, T impl) {
        return factory.createProxy(
                iface, impl, store, lock, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor
        );
    }

    private <T> T createProxyWithLockPrefix(Class<T> iface, T impl, String lockKeyPrefix) {
        return factory.createProxy(
                iface, impl, store, lock, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor, lockKeyPrefix
        );
    }

    // ── @Compensate argument resolution tests ──────────────────────────

    @Test
    @DisplayName("resolveCompensationArgs: no params → null args")
    void resolveCompensationArgs_noParams() throws Exception {
        var activity = NoArgCompensateActivities.class.getMethod("doWork", String.class);
        var compensation = NoArgCompensateActivities.class.getMethod("undo");

        var result = ActivityInvocationHandler.resolveCompensationArgs(
                compensation, activity, new Object[]{"input"}, "result");

        assertNull(result);
    }

    @Test
    @DisplayName("resolveCompensationArgs: single param matching return type → return value")
    void resolveCompensationArgs_returnValue() throws Exception {
        var activity = ReturnValueCompensateActivities.class.getMethod("reserve", String.class);
        var compensation = ReturnValueCompensateActivities.class.getMethod("release", String.class);

        var result = ActivityInvocationHandler.resolveCompensationArgs(
                compensation, activity, new Object[]{"items"}, "reservation-123");

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals("reservation-123", result[0]);
    }

    @Test
    @DisplayName("resolveCompensationArgs: same params as activity → original args")
    void resolveCompensationArgs_originalArgs() throws Exception {
        var activity = OriginalArgsCompensateActivities.class.getMethod("charge", String.class, int.class);
        var compensation = OriginalArgsCompensateActivities.class.getMethod("refund", String.class, int.class);

        var originalArgs = new Object[]{"card-1", 100};
        var result = ActivityInvocationHandler.resolveCompensationArgs(
                compensation, activity, originalArgs, "receipt");

        assertNotNull(result);
        assertEquals(2, result.length);
        assertEquals("card-1", result[0]);
        assertEquals(100, result[1]);
        // Verify defensive copy
        assertNotSame(originalArgs, result);
    }

    interface NoArgCompensateActivities {
        @io.b2mash.maestro.core.annotation.Compensate("undo")
        String doWork(String input);
        void undo();
    }

    interface ReturnValueCompensateActivities {
        @io.b2mash.maestro.core.annotation.Compensate("release")
        String reserve(String item);
        void release(String reservation);
    }

    interface OriginalArgsCompensateActivities {
        @io.b2mash.maestro.core.annotation.Compensate("refund")
        String charge(String cardId, int amount);
        void refund(String cardId, int amount);
    }

    // ── Test activity interfaces and implementations ──────────────────

    record Greeting(String message) {}

    interface GreetingActivities {
        Greeting greet(String name);
        void doNothing();
    }

    static class GreetingActivitiesImpl implements GreetingActivities {
        @Override
        public Greeting greet(String name) {
            return new Greeting("Hello, " + name + "!");
        }

        @Override
        public void doNothing() {
            // no-op
        }
    }

    static class FailingActivities implements GreetingActivities {
        @Override
        public Greeting greet(String name) {
            throw new RuntimeException("Simulated activity failure");
        }

        @Override
        public void doNothing() {
            throw new RuntimeException("Simulated void failure");
        }
    }

    @Activity(name = "custom-activities")
    interface CustomNamedActivities {
        void doWork();
    }

    static class CustomNamedActivitiesImpl implements CustomNamedActivities {
        @Override
        public void doWork() {
            // no-op
        }
    }

    @Activity
    interface SlowActivities {
        String slow();
    }

    /**
     * Sleeps for {@code sleep} then returns {@code "late"}. With
     * {@code ignoreInterrupt} it swallows interrupts and keeps going until the
     * deadline — an attempt the timeout's {@code cancel(true)} cannot stop.
     */
    static final class SlowActivitiesImpl implements SlowActivities {
        private final Duration sleep;
        private final boolean ignoreInterrupt;
        /** Counted down when an interrupt-ignoring attempt reaches its return. */
        final CountDownLatch returned = new CountDownLatch(1);

        SlowActivitiesImpl(Duration sleep, boolean ignoreInterrupt) {
            this.sleep = sleep;
            this.ignoreInterrupt = ignoreInterrupt;
        }

        @Override
        public String slow() {
            if (ignoreInterrupt) {
                var deadline = System.nanoTime() + sleep.toNanos();
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        // deliberately ignored: this attempt cannot be stopped
                    }
                }
                returned.countDown();
                return "late";
            }
            return sleepThenReturn(sleep);
        }
    }

    // ── In-memory SPI stubs ───────────────────────────────────────────

    /**
     * In-memory WorkflowStore that supports memoization operations needed by the proxy.
     */
    static class InMemoryWorkflowStore implements WorkflowStore {

        final Map<Integer, WorkflowEvent> eventsBySequence = new HashMap<>();
        final List<WorkflowEvent> appendedEvents = new CopyOnWriteArrayList<>();
        private final Map<Integer, WorkflowEvent> duplicateEvents = new HashMap<>();

        /**
         * Arms a lost append race at {@code seq}: the memoization lookup still
         * sees an empty slot (so the activity really executes live), and the
         * winner's {@code event} lands at the moment this run appends — which
         * then throws {@link DuplicateEventException}, exactly as a concurrent
         * runner's earlier insert does against the unique index.
         */
        void preloadForDuplication(int seq, WorkflowEvent event) {
            duplicateEvents.put(seq, event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return Optional.ofNullable(eventsBySequence.get(sequenceNumber));
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            var winner = duplicateEvents.remove(event.sequenceNumber());
            if (winner != null) {
                eventsBySequence.put(event.sequenceNumber(), winner);
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            if (eventsBySequence.containsKey(event.sequenceNumber())) {
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            eventsBySequence.put(event.sequenceNumber(), event);
            appendedEvents.add(event);
        }

        @Override
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return new ArrayList<>(eventsBySequence.values());
        }

        @Override
        public int deleteFailureEvents(UUID instanceId) {
            var before = eventsBySequence.size();
            eventsBySequence.values().removeIf(e ->
                    e.eventType() == EventType.ACTIVITY_FAILED
                            || e.eventType() == EventType.WORKFLOW_FAILED);
            return before - eventsBySequence.size();
        }

        // ── Unused operations (not called by the proxy) ──

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Recording distributed lock that tracks acquisitions and releases.
     */
    static class RecordingLock implements DistributedLock {

        final List<String> acquiredKeys = new ArrayList<>();
        int releasedCount = 0;
        boolean failAcquisition = false;
        boolean throwOnAcquire = false;

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (throwOnAcquire) {
                throw new RuntimeException("Lock backend unavailable");
            }
            if (failAcquisition) {
                return Optional.empty();
            }
            acquiredKeys.add(key);
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
            releasedCount++;
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            // no-op
            return true;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    /**
     * Recording messaging that captures published lifecycle events.
     */
    static class RecordingMessaging implements WorkflowMessaging {

        final List<WorkflowLifecycleEvent> publishedEvents = new CopyOnWriteArrayList<>();
        boolean throwOnPublish = false;

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            if (throwOnPublish) {
                throw new RuntimeException("Messaging backend unavailable");
            }
            publishedEvents.add(event);
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
            throw new UnsupportedOperationException();
        }
    }
}
