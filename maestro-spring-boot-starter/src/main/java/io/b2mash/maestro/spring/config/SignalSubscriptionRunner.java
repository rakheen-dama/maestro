package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * Subscribes to the engine-level inbound signal channel
 * ({@code maestro.signals.{serviceName}}) and routes each message to
 * {@link WorkflowExecutor#deliverSignal}.
 *
 * <p>This is the ingestion path for signals published via
 * {@link WorkflowMessaging#publishSignal} — for example, signals sent from
 * the admin dashboard. Application-level cross-service signals typically use
 * {@code @MaestroSignalListener} on domain topics instead; both paths converge
 * on {@code deliverSignal}, which persists the signal before any in-memory
 * delivery.
 *
 * <p><b>Idempotency:</b> transports are at-least-once, so a redelivered
 * message persists a second signal row. Duplicate unconsumed rows are
 * tolerated by design — each await consumes exactly one row (guarded by the
 * store's consumed-flag CAS), and extras simply remain unconsumed.
 *
 * <p>Ordered {@code HIGHEST_PRECEDENCE + 20}: right after
 * {@link StartupRecoveryRunner} (+10), so recovery replay completes before
 * the transport consumer starts. Earlier delivery would still be safe —
 * {@code deliverSignal} needs only the store — but this keeps startup calm.
 *
 * <p><b>Thread safety:</b> this class is thread-safe. {@link #run} is invoked
 * once by Spring at startup; {@code handleSignal} may be invoked concurrently
 * on transport listener threads and only touches the thread-safe
 * {@link WorkflowExecutor}.
 */
public class SignalSubscriptionRunner implements ApplicationRunner, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(SignalSubscriptionRunner.class);

    private static final String ADMIN_COMMAND_PREFIX = "$maestro:";

    private final WorkflowExecutor executor;
    private final @Nullable WorkflowMessaging messaging;
    private final MaestroProperties properties;

    /**
     * Creates a new signal subscription runner.
     *
     * @param executor   the workflow executor signals are delivered to
     * @param messaging  optional messaging backend; when {@code null}, inbound
     *                   signal subscription is disabled
     * @param properties Maestro configuration (provides the service name)
     */
    public SignalSubscriptionRunner(
            WorkflowExecutor executor,
            @Nullable WorkflowMessaging messaging,
            MaestroProperties properties
    ) {
        this.executor = executor;
        this.messaging = messaging;
        this.properties = properties;
    }

    @Override
    public void run(@Nullable ApplicationArguments args) {
        if (messaging == null) {
            logger.info("No WorkflowMessaging configured — inbound signal subscription disabled");
            return;
        }
        var serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            // Unreachable in practice: auto-configuration fails fast without a service name
            logger.warn("maestro.service-name not set — inbound signal subscription disabled");
            return;
        }
        messaging.subscribeSignals(serviceName, this::handleSignal);
        logger.info("Maestro inbound signal subscription started for service '{}'", serviceName);
    }

    private void handleSignal(SignalMessage message) {
        if (message.signalName().startsWith(ADMIN_COMMAND_PREFIX)) {
            // TODO(admin-commands): engine-side handling of $maestro:retry /
            // $maestro:terminate is not implemented yet. Dropped rather than
            // persisted — an unconsumable command row would pollute the signal
            // table and could be adopted by a future instance of the same
            // workflowId.
            logger.warn("Dropping admin command signal '{}' for workflow '{}' — "
                            + "engine-side command handling is not yet implemented",
                    message.signalName(), message.workflowId());
            return;
        }
        try {
            executor.deliverSignal(message.workflowId(), message.signalName(), message.payload());
        } catch (RuntimeException e) {
            // Rethrow so the transport does NOT ack: deliverSignal persists
            // before any in-memory delivery, so a failure here means the
            // signal is not yet durable — swallowing it would lose the signal
            // permanently once the transport marks the message consumed.
            logger.error("Failed to deliver inbound signal '{}' to workflow '{}' — "
                            + "rethrowing for transport retry",
                    message.signalName(), message.workflowId(), e);
            throw e;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
