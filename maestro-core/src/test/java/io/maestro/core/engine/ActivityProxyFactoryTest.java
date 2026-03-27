package io.maestro.core.engine;

import io.maestro.core.annotation.Activity;
import io.maestro.core.annotation.Compensate;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowInstance;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowTimer;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LockHandle;
import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ActivityProxyFactory}.
 *
 * <p>Uses stub SPI implementations — no mocking frameworks. Tests verify
 * proxy creation, annotation resolution, and Object method pass-through.
 */
class ActivityProxyFactoryTest {

    private final ActivityProxyFactory factory = new ActivityProxyFactory();
    private final WorkflowStore store = new StubWorkflowStore();
    private final DistributedLock lock = new StubDistributedLock();
    private final WorkflowMessaging messaging = new StubWorkflowMessaging();
    private final RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
    private final Duration timeout = Duration.ofSeconds(30);
    private final PayloadSerializer serializer = new PayloadSerializer(new ObjectMapper());
    private final RetryExecutor retryExecutor = new RetryExecutor();

    // ── Activity interfaces for testing ──────────────────────────────────

    /**
     * Activity interface with a custom name via {@link Activity} annotation.
     */
    @Activity(name = "custom")
    interface NamedActivities {
        String doWork(String input);
    }

    /**
     * Activity interface without {@link Activity} annotation — should use
     * the interface's simple name.
     */
    interface UnnamedActivities {
        String doWork(String input);
    }

    /**
     * Concrete class (not an interface) — used to verify rejection.
     */
    static class NotAnInterface {
        public String doWork(String input) { return input; }
    }

    /**
     * Simple implementation of {@link NamedActivities} for proxy creation.
     */
    static class NamedActivitiesImpl implements NamedActivities {
        @Override
        public String doWork(String input) { return "done: " + input; }
    }

    /**
     * Simple implementation of {@link UnnamedActivities} for proxy creation.
     */
    static class UnnamedActivitiesImpl implements UnnamedActivities {
        @Override
        public String doWork(String input) { return "done: " + input; }
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createProxy rejects non-interface class with IllegalArgumentException")
    @SuppressWarnings("unchecked")
    void nonInterfaceRejected() {
        var impl = new NotAnInterface();

        var exception = assertThrows(IllegalArgumentException.class, () ->
                factory.createProxy(
                        (Class) NotAnInterface.class,
                        impl,
                        store, lock, messaging,
                        retryPolicy, timeout, serializer, retryExecutor
                )
        );

        assertTrue(exception.getMessage().contains("must be an interface"),
                "Exception message should mention 'must be an interface'");
    }

    @Test
    @DisplayName("createProxy returns a proxy that implements the activity interface")
    void proxyImplementsInterface() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        assertNotNull(proxy);
        assertInstanceOf(NamedActivities.class, proxy);
    }

    @Test
    @DisplayName("proxy toString contains custom activity name from @Activity annotation")
    void activityNameFromAnnotation() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        String toString = proxy.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("custom"),
                "toString should contain the custom activity name 'custom', but was: " + toString);
    }

    @Test
    @DisplayName("proxy toString contains interface simple name when @Activity is absent")
    void activityNameFromInterfaceSimpleName() {
        UnnamedActivities proxy = factory.createProxy(
                UnnamedActivities.class,
                new UnnamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        String toString = proxy.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("UnnamedActivities"),
                "toString should contain 'UnnamedActivities', but was: " + toString);
    }

    @Test
    @DisplayName("proxy toString returns formatted string")
    void proxyToString() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        String result = proxy.toString();

        assertNotNull(result);
        assertEquals("ActivityProxy[custom]", result);
    }

    @Test
    @DisplayName("proxy hashCode returns System.identityHashCode")
    void proxyHashCode() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
    }

    @Test
    @DisplayName("proxy equals uses reference equality")
    void proxyEquals() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );

        // Same reference should be equal
        assertTrue(proxy.equals(proxy));

        // Different proxy instance should not be equal
        NamedActivities other = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );
        assertNotEquals(proxy, other);
    }

    @Test
    @DisplayName("createProxy works with null lock and messaging")
    void proxyWithNullOptionalDependencies() {
        NamedActivities proxy = factory.createProxy(
                NamedActivities.class,
                new NamedActivitiesImpl(),
                store, null, null,
                retryPolicy, timeout, serializer, retryExecutor
        );

        assertNotNull(proxy);
        assertInstanceOf(NamedActivities.class, proxy);
        assertEquals("ActivityProxy[custom]", proxy.toString());
    }

    // ── @Compensate validation tests ────────────────────────────────────

    /**
     * Activity with valid @Compensate — return-value pattern.
     */
    @Activity
    interface ValidCompensateActivities {
        @Compensate("releaseReservation")
        String reserve(String item);

        void releaseReservation(String reservation);
    }

    static class ValidCompensateActivitiesImpl implements ValidCompensateActivities {
        @Override public String reserve(String item) { return "res-" + item; }
        @Override public void releaseReservation(String reservation) {}
    }

    /**
     * Activity with @Compensate pointing to nonexistent method.
     */
    @Activity
    interface BadCompensateMethodActivities {
        @Compensate("nonExistentMethod")
        String doWork(String input);
    }

    static class BadCompensateMethodActivitiesImpl implements BadCompensateMethodActivities {
        @Override public String doWork(String input) { return input; }
    }

    /**
     * Activity with @Compensate with incompatible parameter types.
     */
    @Activity
    interface BadCompensateParamsActivities {
        @Compensate("compensate")
        String doWork(String input);

        void compensate(int wrongType);
    }

    static class BadCompensateParamsActivitiesImpl implements BadCompensateParamsActivities {
        @Override public String doWork(String input) { return input; }
        @Override public void compensate(int wrongType) {}
    }

    @Test
    @DisplayName("createProxy accepts valid @Compensate annotations")
    void validCompensateAccepted() {
        var proxy = factory.createProxy(
                ValidCompensateActivities.class,
                new ValidCompensateActivitiesImpl(),
                store, lock, messaging,
                retryPolicy, timeout, serializer, retryExecutor
        );
        assertNotNull(proxy);
    }

    @Test
    @DisplayName("createProxy rejects @Compensate referencing nonexistent method")
    void compensateNonexistentMethodRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                factory.createProxy(
                        BadCompensateMethodActivities.class,
                        new BadCompensateMethodActivitiesImpl(),
                        store, lock, messaging,
                        retryPolicy, timeout, serializer, retryExecutor
                ));
    }

    @Test
    @DisplayName("createProxy rejects @Compensate with incompatible parameters")
    void compensateIncompatibleParamsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                factory.createProxy(
                        BadCompensateParamsActivities.class,
                        new BadCompensateParamsActivitiesImpl(),
                        store, lock, messaging,
                        retryPolicy, timeout, serializer, retryExecutor
                ));
    }

    // ── Stub SPI implementations ─────────────────────────────────────────

    /**
     * Minimal stub — all methods throw {@link UnsupportedOperationException}
     * because the proxy creation tests do not invoke activity methods (which
     * would require a {@link io.maestro.core.context.WorkflowContext}).
     */
    private static class StubWorkflowStore implements WorkflowStore {

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void markSignalConsumed(UUID signalId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            throw new UnsupportedOperationException("stub");
        }
    }

    /**
     * Minimal stub — all methods throw {@link UnsupportedOperationException}.
     */
    private static class StubDistributedLock implements DistributedLock {

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void release(LockHandle handle) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void renew(LockHandle handle, Duration ttl) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            throw new UnsupportedOperationException("stub");
        }
    }

    /**
     * Minimal stub — all methods throw {@link UnsupportedOperationException}.
     */
    private static class StubWorkflowMessaging implements WorkflowMessaging {

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
