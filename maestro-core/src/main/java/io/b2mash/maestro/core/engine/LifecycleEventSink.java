package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the engine hands a {@link WorkflowLifecycleEvent} once it has built
 * one — the single seam between the code that <em>produces</em> lifecycle
 * events (the executor, {@link SignalManager}, {@link DefaultWorkflowOperations},
 * {@link io.b2mash.maestro.core.saga.SagaManager}, {@link ActivityInvocationHandler})
 * and the {@link WorkflowMessaging} that eventually carries them.
 *
 * <h2>Why this exists</h2>
 * <p>Every one of those producers runs on a workflow's own virtual thread, and
 * a {@code WorkflowMessaging} implementation is free to block inside
 * {@link WorkflowMessaging#publishLifecycleEvent}: Kafka's producer, for
 * example, blocks synchronously inside {@code send()} while it waits — up to
 * {@code max.block.ms} — for metadata on a topic that does not exist. The SPI
 * contract says a lifecycle failure must not interrupt workflow execution;
 * this interface makes that true for <em>latency</em> as well, by letting the
 * executor substitute its off-thread {@link LifecycleEventPublisher} for the
 * inline call every producer used to make. A ten-activity workflow against a
 * missing admin-events topic used to stall for twenty times
 * {@code max.block.ms}; through {@link WorkflowExecutor#lifecycleEventSink()}
 * it completes in milliseconds and the publishes drain (or are dropped under
 * backpressure) in the background.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #publish} <b>never throws</b> and <b>never blocks</b> on the
 *       transport. The executor-backed sink hands the event to a bounded
 *       queue; {@link #direct} publishes inline but swallows and logs any
 *       exception. Neither reports success — lifecycle events are
 *       best-effort observability, not workflow state.</li>
 *   <li>Enablement ({@code maestro.admin.events.enabled}) is the caller's
 *       business: {@link WorkflowExecutor} hands out {@link #NOOP} when
 *       publishing is disabled, and {@link #direct} expects a reference that
 *       has already been through {@link GatedWorkflowMessaging#wrap}.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be safe to call concurrently from any number of
 * workflow threads; both built-in sinks are.
 *
 * @see WorkflowExecutor#lifecycleEventSink()
 * @see LifecycleEventPublisher
 */
@FunctionalInterface
public interface LifecycleEventSink {

    /** Discards every event — the sink for "no messaging" or "publishing disabled". */
    LifecycleEventSink NOOP = event -> { };

    /**
     * Accepts a lifecycle event for best-effort publishing. Returns promptly
     * and never throws.
     *
     * @param event the event to publish
     */
    void publish(WorkflowLifecycleEvent event);

    /**
     * A sink that publishes <em>inline</em>, on the calling thread, swallowing
     * (and logging at WARN) anything the transport throws. {@link #NOOP} when
     * {@code messaging} is {@code null}.
     *
     * <p>This is the pre-sink behaviour, kept for callers that build engine
     * components without a {@link WorkflowExecutor} — unit tests, mostly. A
     * blocking transport blocks the caller here; production wiring should
     * prefer {@link WorkflowExecutor#lifecycleEventSink()}.
     *
     * @param messaging the transport, already gated for enablement, or {@code null}
     * @return the inline sink, or {@link #NOOP}
     */
    static LifecycleEventSink direct(@Nullable WorkflowMessaging messaging) {
        return messaging == null ? NOOP : new Direct(messaging);
    }

    /** The inline implementation behind {@link #direct}; a class so it can own a logger. */
    final class Direct implements LifecycleEventSink {

        private static final Logger logger = LoggerFactory.getLogger(LifecycleEventSink.class);

        private final WorkflowMessaging messaging;

        private Direct(WorkflowMessaging messaging) {
            this.messaging = messaging;
        }

        @Override
        public void publish(WorkflowLifecycleEvent event) {
            try {
                messaging.publishLifecycleEvent(event);
            } catch (Exception e) {
                // SPI contract: lifecycle event failures must not interrupt workflow execution
                logger.warn("Failed to publish {} lifecycle event for workflow '{}'",
                        event.eventType(), event.workflowId(), e);
            }
        }
    }
}
