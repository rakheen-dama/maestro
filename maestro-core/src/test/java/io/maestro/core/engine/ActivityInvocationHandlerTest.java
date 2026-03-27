package io.maestro.core.engine;

import io.maestro.core.annotation.Activity;
import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.ActivityExecutionException;
import io.maestro.core.exception.DuplicateEventException;
import io.maestro.core.exception.OptimisticLockException;
import io.maestro.core.exception.WorkflowAlreadyExistsException;
import io.maestro.core.exception.WorkflowNotFoundException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowInstance;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowTimer;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LockHandle;
import io.maestro.core.spi.LifecycleEventType;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

        var ctx = new WorkflowContext(
                INSTANCE_ID, WORKFLOW_ID, RUN_ID,
                "TestWorkflow", "test-queue", "test-service",
                0, true
        );
        WorkflowContext.bind(ctx);
    }

    @AfterEach
    void tearDown() {
        WorkflowContext.clear();
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

        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        var result = proxy.greet("World");

        assertEquals("Hello, World!", result.message());
        // Activity should NOT have been invoked (replay)
        assertTrue(store.appendedEvents.isEmpty(),
                "No new events should be appended during replay");
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

        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

        var ex = assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));
        assertTrue(ex.getMessage().contains("Simulated failure"));
    }

    // ── Live execution path tests ─────────────────────────────────────

    @Test
    @DisplayName("Live: executes activity and persists ACTIVITY_COMPLETED event")
    void liveExecutionSuccess() {
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
    }

    @Test
    @DisplayName("Live: handles void activity methods")
    void liveExecutionVoid() {
        var impl = new GreetingActivitiesImpl();
        var proxy = createProxy(GreetingActivities.class, impl);

        assertDoesNotThrow(() -> proxy.doNothing());

        assertEquals(1, store.appendedEvents.size());
        var event = store.appendedEvents.getFirst();
        assertEquals(EventType.ACTIVITY_COMPLETED, event.eventType());
    }

    @Test
    @DisplayName("Live: persists ACTIVITY_FAILED when retries exhausted")
    void liveExecutionFailure() {
        var proxy = createProxy(GreetingActivities.class, new FailingActivities());

        assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));

        // Should have an ACTIVITY_FAILED event
        var failedEvents = store.appendedEvents.stream()
                .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
    }

    @Test
    @DisplayName("Live: sequence numbers increment across multiple calls")
    void sequenceNumbering() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

        proxy.greet("A");
        proxy.greet("B");
        proxy.greet("C");

        assertEquals(3, store.appendedEvents.size());
        assertEquals(1, store.appendedEvents.get(0).sequenceNumber());
        assertEquals(2, store.appendedEvents.get(1).sequenceNumber());
        assertEquals(3, store.appendedEvents.get(2).sequenceNumber());
    }

    // ── Idempotency tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Idempotency: DuplicateEventException on append returns stored result")
    void duplicateEventIdempotency() {
        // Pre-populate the store so appendEvent will throw DuplicateEventException
        var storedPayload = MAPPER.valueToTree(new Greeting("Stored result"));
        store.preloadForDuplication(1, new WorkflowEvent(
                UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet",
                storedPayload, Instant.now()
        ));

        // The proxy will try to append, get DuplicateEventException, re-read stored result
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        var result = proxy.greet("World");

        // Should return the stored result, not the live execution result
        assertEquals("Stored result", result.message());
    }

    // ── Lock behavior tests ───────────────────────────────────────────

    @Test
    @DisplayName("Lock: proceeds without lock when lock acquisition fails")
    void lockAcquisitionFailure() {
        lock.failAcquisition = true;
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

        // Should still succeed — lock is optimization, not correctness
        var result = proxy.greet("World");
        assertEquals("Hello, World!", result.message());
    }

    @Test
    @DisplayName("Lock: proceeds when lock backend throws exception")
    void lockBackendError() {
        lock.throwOnAcquire = true;
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

        var result = proxy.greet("World");
        assertEquals("Hello, World!", result.message());
    }

    @Test
    @DisplayName("Lock: acquires and releases lock on success")
    void lockLifecycle() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        proxy.greet("World");

        assertEquals(1, lock.acquiredKeys.size());
        assertTrue(lock.acquiredKeys.getFirst().contains("maestro:lock:activity:" + WORKFLOW_ID));
        assertEquals(1, lock.releasedCount);
    }

    @Test
    @DisplayName("Lock: releases lock even when activity fails")
    void lockReleasedOnFailure() {
        var proxy = createProxy(GreetingActivities.class, new FailingActivities());

        assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));
        assertEquals(1, lock.releasedCount);
    }

    // ── Messaging behavior tests ──────────────────────────────────────

    @Test
    @DisplayName("Messaging: publishes ACTIVITY_STARTED and ACTIVITY_COMPLETED lifecycle events")
    void lifecycleEventsOnSuccess() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        proxy.greet("World");

        assertEquals(2, messaging.publishedEvents.size());
        assertEquals(LifecycleEventType.ACTIVITY_STARTED, messaging.publishedEvents.get(0).eventType());
        assertEquals(LifecycleEventType.ACTIVITY_COMPLETED, messaging.publishedEvents.get(1).eventType());
    }

    @Test
    @DisplayName("Messaging: publishes ACTIVITY_FAILED lifecycle event on failure")
    void lifecycleEventsOnFailure() {
        var proxy = createProxy(GreetingActivities.class, new FailingActivities());

        assertThrows(ActivityExecutionException.class, () -> proxy.greet("World"));

        var failedEvents = messaging.publishedEvents.stream()
                .filter(e -> e.eventType() == LifecycleEventType.ACTIVITY_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
    }

    @Test
    @DisplayName("Messaging: messaging failure does not interrupt workflow")
    void messagingFailureDoesNotBlock() {
        messaging.throwOnPublish = true;
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());

        // Should still succeed — messaging is best-effort
        var result = proxy.greet("World");
        assertEquals("Hello, World!", result.message());
    }

    @Test
    @DisplayName("Messaging: works without messaging (null)")
    void nullMessaging() {
        var proxy = createProxyWithoutMessaging(GreetingActivities.class, new GreetingActivitiesImpl());

        var result = proxy.greet("World");
        assertEquals("Hello, World!", result.message());
    }

    // ── Object method pass-through ────────────────────────────────────

    @Test
    @DisplayName("Object methods: toString returns proxy description")
    void toStringReturnsProxyDescription() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        assertEquals("ActivityProxy[GreetingActivities]", proxy.toString());
    }

    @Test
    @DisplayName("Object methods: hashCode returns identity hash")
    void hashCodeReturnsIdentityHash() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
    }

    @Test
    @DisplayName("Object methods: equals uses reference equality")
    void equalsUsesReferenceEquality() {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        assertEquals(proxy, proxy);
        assertNotEquals(proxy, createProxy(GreetingActivities.class, new GreetingActivitiesImpl()));
    }

    // ── Replay state tests ────────────────────────────────────────────

    @Test
    @DisplayName("Replaying flag: set to false after first live execution")
    void replayingFlagClearedOnLiveExecution() {
        assertTrue(WorkflowContext.current().isReplaying());

        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        proxy.greet("World");

        assertFalse(WorkflowContext.current().isReplaying());
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

        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        proxy.greet("World");

        assertTrue(WorkflowContext.current().isReplaying());
    }

    @Test
    @DisplayName("Custom @Activity name: uses annotation name in step key")
    void customActivityName() {
        var proxy = createProxy(CustomNamedActivities.class, new CustomNamedActivitiesImpl());
        proxy.doWork();

        var event = store.appendedEvents.getFirst();
        assertEquals("custom-activities.doWork", event.stepName());
    }

    // ── Helper methods ────────────────────────────────────────────────

    private <T> T createProxy(Class<T> iface, T impl) {
        return factory.createProxy(
                iface, impl, store, lock, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor
        );
    }

    private <T> T createProxyWithoutMessaging(Class<T> iface, T impl) {
        return factory.createProxy(
                iface, impl, store, lock, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor
        );
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

    // ── In-memory SPI stubs ───────────────────────────────────────────

    /**
     * In-memory WorkflowStore that supports memoization operations needed by the proxy.
     */
    static class InMemoryWorkflowStore implements WorkflowStore {

        final Map<Integer, WorkflowEvent> eventsBySequence = new HashMap<>();
        final List<WorkflowEvent> appendedEvents = new ArrayList<>();
        private final Map<Integer, WorkflowEvent> duplicateEvents = new HashMap<>();

        /**
         * Pre-loads an event that will cause appendEvent to throw DuplicateEventException
         * for the given sequence, simulating a crash-after-persist scenario.
         */
        void preloadForDuplication(int seq, WorkflowEvent event) {
            duplicateEvents.put(seq, event);
            eventsBySequence.put(seq, event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return Optional.ofNullable(eventsBySequence.get(sequenceNumber));
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            if (duplicateEvents.containsKey(event.sequenceNumber())) {
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            if (eventsBySequence.containsKey(event.sequenceNumber())) {
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            eventsBySequence.put(event.sequenceNumber(), event);
            appendedEvents.add(event);
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return new ArrayList<>(eventsBySequence.values());
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
        public void markSignalConsumed(UUID signalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            throw new UnsupportedOperationException();
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
        public void markTimerFired(UUID timerId) {
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
        public void renew(LockHandle handle, Duration ttl) {
            // no-op
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

        final List<WorkflowLifecycleEvent> publishedEvents = new ArrayList<>();
        boolean throwOnPublish = false;

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            if (throwOnPublish) {
                throw new RuntimeException("Messaging backend unavailable");
            }
            publishedEvents.add(event);
        }

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
            throw new UnsupportedOperationException();
        }
    }
}
