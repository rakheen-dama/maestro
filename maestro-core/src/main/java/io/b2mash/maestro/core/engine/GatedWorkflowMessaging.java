package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Decorates a {@link WorkflowMessaging} so that {@link #publishLifecycleEvent}
 * is a no-op when lifecycle publishing is disabled ({@code
 * maestro.admin.events.enabled=false}), while every other method — task
 * dispatch, signal delivery, subscriptions — passes straight through to the
 * delegate unaffected.
 *
 * <h2>Why this exists</h2>
 * <p>{@link WorkflowLifecycleEvent}s are published from several independent
 * places that each hold their own reference to a {@link WorkflowMessaging}:
 * {@link WorkflowExecutor} itself (its {@code WORKFLOW_*} events), {@link
 * SignalManager} ({@code SIGNAL_RECEIVED}), {@link DefaultWorkflowOperations}
 * ({@code TIMER_*}), {@link io.b2mash.maestro.core.saga.SagaManager}
 * ({@code COMPENSATION_*}), and activity proxies built via {@link
 * ActivityProxyFactory} ({@code ACTIVITY_*}). Rather than each of those
 * classes carrying its own copy of the enabled flag and its own {@code if
 * (!enabled) return;} guard — the exact duplication that let the activity,
 * signal and timer paths silently diverge from {@code WorkflowExecutor}'s own
 * gate — every one of them is instead handed a {@code WorkflowMessaging}
 * reference that has already been wrapped with this decorator, once, at the
 * point where the delegate and the flag are both known. The gate then lives
 * in exactly one place.
 *
 * <p>{@code WorkflowExecutor} wraps the {@code messaging} constructor
 * argument with this class and hands the wrapped reference to every
 * component it builds ({@link SignalManager}, {@link
 * io.b2mash.maestro.core.saga.SagaManager}, {@link
 * DefaultWorkflowOperations}). Callers that build activity proxies outside
 * {@code WorkflowExecutor} — the Spring Boot starter's
 * {@code ActivityStubBeanPostProcessor} in production, or a test that wires
 * one up directly — must wrap their {@code WorkflowMessaging} reference with
 * {@link #wrap} themselves before calling {@link
 * ActivityProxyFactory#createProxy}, since activity proxies are not built by
 * {@code WorkflowExecutor}.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond its two immutable fields; safe to share and call
 * concurrently, exactly like the {@link WorkflowMessaging} it wraps.
 *
 * @see WorkflowExecutor
 */
public final class GatedWorkflowMessaging implements WorkflowMessaging {

    private final WorkflowMessaging delegate;
    private final boolean lifecycleEventsEnabled;

    /**
     * @param delegate               the real messaging implementation
     * @param lifecycleEventsEnabled whether {@link #publishLifecycleEvent} may
     *                               reach the delegate at all
     */
    public GatedWorkflowMessaging(WorkflowMessaging delegate, boolean lifecycleEventsEnabled) {
        this.delegate = delegate;
        this.lifecycleEventsEnabled = lifecycleEventsEnabled;
    }

    /**
     * Wraps {@code messaging} with the lifecycle-event gate, or returns
     * {@code null} unchanged if there is nothing to wrap.
     *
     * @param messaging              the messaging instance to gate, or {@code null}
     * @param lifecycleEventsEnabled whether lifecycle events may be published
     * @return the gated wrapper, or {@code null} if {@code messaging} was {@code null}
     */
    public static @Nullable WorkflowMessaging wrap(
            @Nullable WorkflowMessaging messaging, boolean lifecycleEventsEnabled
    ) {
        return messaging == null ? null : new GatedWorkflowMessaging(messaging, lifecycleEventsEnabled);
    }

    @Override
    public void publishTask(String taskQueue, TaskMessage message) {
        delegate.publishTask(taskQueue, message);
    }

    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        delegate.publishSignal(serviceName, message);
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        if (!lifecycleEventsEnabled) {
            return;
        }
        delegate.publishLifecycleEvent(event);
    }

    @Override
    public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        delegate.subscribe(taskQueue, handler);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        delegate.subscribeSignals(serviceName, handler);
    }
}
