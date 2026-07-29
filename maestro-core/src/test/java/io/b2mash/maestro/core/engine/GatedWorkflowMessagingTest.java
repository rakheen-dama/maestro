package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link GatedWorkflowMessaging} — the shared seam every
 * lifecycle-event publisher in {@code maestro-core} wraps its {@link
 * WorkflowMessaging} reference with, so {@code maestro.admin.events.enabled}
 * is honoured uniformly rather than each publisher re-implementing its own
 * check (see {@link WorkflowExecutorLifecycleEventPublishingTest} and
 * {@code io.b2mash.maestro.spring.proxy.ActivityStubBeanPostProcessor} for
 * the integrated, end-to-end proof of that).
 */
@DisplayName("GatedWorkflowMessaging gates only publishLifecycleEvent")
class GatedWorkflowMessagingTest {

    @Test
    @DisplayName("wrap(null, ...) returns null — nothing to gate")
    void wrap_withNullDelegate_returnsNull() {
        assertNull(GatedWorkflowMessaging.wrap(null, true));
        assertNull(GatedWorkflowMessaging.wrap(null, false));
    }

    @Test
    @DisplayName("disabled: publishLifecycleEvent never reaches the delegate")
    void disabled_publishLifecycleEventNeverReachesDelegate() {
        var delegate = new RecordingMessaging();
        var gated = new GatedWorkflowMessaging(delegate, false);

        gated.publishLifecycleEvent(sampleEvent());

        assertEquals(0, delegate.lifecycleEvents.get());
    }

    @Test
    @DisplayName("enabled: publishLifecycleEvent reaches the delegate")
    void enabled_publishLifecycleEventReachesDelegate() {
        var delegate = new RecordingMessaging();
        var gated = new GatedWorkflowMessaging(delegate, true);

        gated.publishLifecycleEvent(sampleEvent());

        assertEquals(1, delegate.lifecycleEvents.get());
    }

    @Test
    @DisplayName("disabled: every other method still passes straight through")
    void disabled_everyOtherMethodStillPassesThrough() {
        var delegate = new RecordingMessaging();
        var gated = new GatedWorkflowMessaging(delegate, false);

        gated.publishTask("queue", new TaskMessage(
                UUID.randomUUID(), "wf-1", "SomeWorkflow", UUID.randomUUID(), "test-service", null));
        gated.publishSignal("svc", new SignalMessage("wf-1", "sig", null));
        gated.subscribe("queue", handler -> {});
        gated.subscribeSignals("svc", handler -> {});

        assertEquals(1, delegate.tasks.get());
        assertEquals(1, delegate.signals.get());
        assertEquals(1, delegate.subscribes.get());
        assertEquals(1, delegate.signalSubscribes.get());
    }

    @Test
    @DisplayName("wrap(nonNull, ...) returns a GatedWorkflowMessaging, not the raw delegate")
    void wrap_withDelegate_returnsWrapped() {
        var delegate = new RecordingMessaging();
        var wrapped = GatedWorkflowMessaging.wrap(delegate, true);

        assertEquals(GatedWorkflowMessaging.class, wrapped.getClass());
        assertSame(wrapped.getClass(), GatedWorkflowMessaging.wrap(delegate, false).getClass());
    }

    private static WorkflowLifecycleEvent sampleEvent() {
        return new WorkflowLifecycleEvent(
                UUID.randomUUID(), "wf-1", "SomeWorkflow", "test-service", "default",
                LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now());
    }

    /** Counts calls per method instead of recording payloads — only the routing matters here. */
    private static class RecordingMessaging implements WorkflowMessaging {
        final AtomicInteger tasks = new AtomicInteger();
        final AtomicInteger signals = new AtomicInteger();
        final AtomicInteger lifecycleEvents = new AtomicInteger();
        final AtomicInteger subscribes = new AtomicInteger();
        final AtomicInteger signalSubscribes = new AtomicInteger();

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {
            tasks.incrementAndGet();
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
            signals.incrementAndGet();
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            lifecycleEvents.incrementAndGet();
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
            subscribes.incrementAndGet();
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
            signalSubscribes.incrementAndGet();
        }
    }
}
