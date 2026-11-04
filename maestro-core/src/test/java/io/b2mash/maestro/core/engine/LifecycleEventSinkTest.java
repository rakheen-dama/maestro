package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the two built-in {@link LifecycleEventSink}s: {@link LifecycleEventSink#NOOP}
 * for "no messaging", and {@link LifecycleEventSink#direct} as the inline,
 * never-throwing pre-sink behaviour. The executor-backed, off-thread sink is
 * pinned in {@code WorkflowExecutorLifecycleEventPublishingTest}.
 */
@DisplayName("LifecycleEventSink")
class LifecycleEventSinkTest {

    @Test
    @DisplayName("direct(null) is the NOOP sink")
    void directOfNullIsNoop() {
        assertSame(LifecycleEventSink.NOOP, LifecycleEventSink.direct(null));
    }

    @Test
    @DisplayName("direct(messaging) publishes inline, on the calling thread")
    void directPublishesInline() {
        var messaging = new RecordingMessaging();
        var sink = LifecycleEventSink.direct(messaging);

        var event = event();
        sink.publish(event);

        assertEquals(1, messaging.events.size());
        assertSame(event, messaging.events.getFirst());
        assertEquals(Thread.currentThread().getName(), messaging.threads.getFirst(),
                "the direct sink is the inline pre-sink behaviour — same thread as the caller");
    }

    @Test
    @DisplayName("direct(messaging) swallows a throwing transport — lifecycle publishing never fails the caller")
    void directSwallowsTransportFailure() {
        var sink = LifecycleEventSink.direct(new WorkflowMessaging() {
            @Override
            public void publishSignal(String serviceName, SignalMessage message) {}

            @Override
            public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
                throw new IllegalStateException("broker down");
            }

            @Override
            public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
        });

        assertDoesNotThrow(() -> sink.publish(event()));
    }

    private static WorkflowLifecycleEvent event() {
        return new WorkflowLifecycleEvent(UUID.randomUUID(), "wf-1", "TestWorkflow", "test-service",
                "default", LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now());
    }

    private static final class RecordingMessaging implements WorkflowMessaging {
        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
            threads.add(Thread.currentThread().getName());
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
