package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SignalSubscriptionRunner}.
 *
 * <p>Uses a real {@link WorkflowExecutor} backed by the in-memory store so the
 * handler's behaviour (signal persistence via {@code deliverSignal}) is tested
 * against real code, not a mock.
 */
class SignalSubscriptionRunnerTest {

    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;
    private MaestroProperties properties;
    private RecordingMessaging messaging;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "order-service");
        properties = new MaestroProperties();
        properties.setServiceName("order-service");
        messaging = new RecordingMessaging();
    }

    @Test
    @DisplayName("subscribes to inbound signals with the configured service name")
    void subscribesWithConfiguredServiceName() {
        var runner = new SignalSubscriptionRunner(executor, messaging, properties);

        runner.run(null);

        assertEquals("order-service", messaging.subscribedServiceName);
        assertNotNull(messaging.handler, "a signal handler must be registered");
    }

    @Test
    @DisplayName("registered handler routes inbound signals to executor.deliverSignal")
    void handlerPersistsDeliveredSignal() {
        var runner = new SignalSubscriptionRunner(executor, messaging, properties);
        runner.run(null);

        var payload = new ObjectMapper().createObjectNode().put("status", "APPROVED");
        messaging.handler.accept(new SignalMessage("order-1", "payment.result", payload));

        var signals = store.getUnconsumedSignals("order-1", "payment.result");
        assertEquals(1, signals.size(), "inbound signal must be persisted via deliverSignal");
        assertEquals("payment.result", signals.getFirst().signalName());
        assertNotNull(signals.getFirst().payload());
        assertEquals("APPROVED", signals.getFirst().payload().get("status").asString());
    }

    @Test
    @DisplayName("no WorkflowMessaging configured — runner is a no-op")
    void nullMessagingIsNoOp() {
        var runner = new SignalSubscriptionRunner(executor, null, properties);

        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    @DisplayName("handler propagates delivery failures so the transport does not ack a lost signal")
    void handlerPropagatesDeliveryFailure() {
        var failingStore = new FailingSaveSignalStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var failingExecutor = new WorkflowExecutor(
                failingStore, null, null, null, serializer, "order-service");
        var runner = new SignalSubscriptionRunner(failingExecutor, messaging, properties);
        runner.run(null);

        assertThrows(RuntimeException.class, () ->
                        messaging.handler.accept(new SignalMessage("order-1", "payment.result", null)),
                "a failed deliverSignal must propagate — swallowing it would let the "
                        + "transport ack a message whose signal was never persisted");
    }

    @Test
    @DisplayName("admin command signals ($maestro:*) are not persisted as workflow signals")
    void adminCommandSignalsAreNotPersisted() {
        var runner = new SignalSubscriptionRunner(executor, messaging, properties);
        runner.run(null);

        assertDoesNotThrow(() ->
                messaging.handler.accept(new SignalMessage("order-1", "$maestro:terminate", null)));

        assertTrue(store.getUnconsumedSignals("order-1", "$maestro:terminate").isEmpty(),
                "unimplemented admin commands must not pollute the signal table");
    }

    @Test
    @DisplayName("runs right after StartupRecoveryRunner")
    void orderedAfterStartupRecovery() {
        var runner = new SignalSubscriptionRunner(executor, messaging, properties);

        assertTrue(runner.getOrder() > new StartupRecoveryRunner(executor, new WorkflowRegistrar(executor), properties).getOrder(),
                "signal subscription must start after startup recovery");
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 20, runner.getOrder());
    }

    // ── Store whose saveSignal always fails ────────────────────────────

    private static class FailingSaveSignalStore implements io.b2mash.maestro.core.spi.WorkflowStore {

        @Override
        public io.b2mash.maestro.core.model.WorkflowInstance createInstance(
                io.b2mash.maestro.core.model.WorkflowInstance instance) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.util.Optional<io.b2mash.maestro.core.model.WorkflowInstance> getInstance(String workflowId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.List<io.b2mash.maestro.core.model.WorkflowInstance> getRecoverableInstances() {
            return java.util.List.of();
        }

        @Override
        public void updateInstance(io.b2mash.maestro.core.model.WorkflowInstance instance) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void appendEvent(io.b2mash.maestro.core.model.WorkflowEvent event) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.util.Optional<io.b2mash.maestro.core.model.WorkflowEvent> getEventBySequence(
                java.util.UUID instanceId, int sequenceNumber) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.List<io.b2mash.maestro.core.model.WorkflowEvent> getEvents(java.util.UUID instanceId) {
            return java.util.List.of();
        }

        @Override
        public int deleteFailureEvents(java.util.UUID instanceId) {
            return 0;
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            throw new RuntimeException("Simulated store failure");
        }

        @Override
        public java.util.List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return java.util.List.of();
        }

        @Override
        public boolean markSignalConsumed(java.util.UUID signalId) {
            return false;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, java.util.UUID instanceId) {}

        @Override
        public void saveTimer(io.b2mash.maestro.core.model.WorkflowTimer timer) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.util.List<io.b2mash.maestro.core.model.WorkflowTimer> getDueTimers(
                java.time.Instant now, int batchSize) {
            return java.util.List.of();
        }

        @Override
        public java.util.Optional<io.b2mash.maestro.core.model.WorkflowTimer> findTimer(
                java.util.UUID workflowInstanceId, String timerId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public boolean markTimerFired(java.util.UUID timerId) {
            return false;
        }

        @Override
        public boolean markTimerCancelled(java.util.UUID timerId) { return false; }
    }

    // ── Recording WorkflowMessaging ────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {

        String subscribedServiceName;
        Consumer<SignalMessage> handler;

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {}

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {}

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
            this.subscribedServiceName = serviceName;
            this.handler = handler;
        }
    }
}
