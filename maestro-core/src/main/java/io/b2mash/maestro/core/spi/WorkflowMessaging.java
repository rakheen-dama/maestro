package io.b2mash.maestro.core.spi;

import java.util.function.Consumer;

/**
 * Messaging SPI for signal delivery and lifecycle event publishing.
 *
 * <p>There is deliberately no task-dispatch channel: a workflow starts
 * in-process, on the node that called {@code startWorkflow}, so nothing is
 * ever dispatched to a worker over the transport.
 *
 * <p>Implementations provide the communication backbone between workflow
 * engine components. The reference implementation uses Apache Kafka via
 * Spring Kafka 4.x.
 *
 * <h2>Delivery Guarantees</h2>
 * <ul>
 *   <li>{@link #publishSignal} provides <b>at-least-once</b> delivery;
 *       lifecycle events are best-effort.</li>
 *   <li>Consumers must be idempotent — duplicate messages are possible
 *       during Kafka rebalances or retries, and a handler that throws is
 *       re-invoked with the same message until it succeeds or the message is
 *       dead-lettered.</li>
 *   <li>Message ordering is guaranteed <b>per workflow ID</b> (by using
 *       workflow ID as the Kafka partition key).</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple virtual threads may
 * publish messages concurrently. Subscription handlers are invoked on
 * the messaging implementation's consumer threads.
 *
 * @see SignalMessage
 * @see WorkflowLifecycleEvent
 */
public interface WorkflowMessaging {

    /**
     * Publishes a signal to a service for delivery to a workflow.
     *
     * <p>The signal is routed to the specified service's signal topic.
     * The service's signal consumer will persist it and deliver it to
     * the target workflow.
     *
     * @param serviceName the target service name
     * @param message     the signal message
     */
    void publishSignal(String serviceName, SignalMessage message);

    /**
     * Publishes a lifecycle event for the admin dashboard.
     *
     * <p>Lifecycle events are consumed by the Maestro Admin application
     * for observability. Publishing failures should be logged but must
     * not interrupt workflow execution. A transport the dashboard cannot
     * consume from may implement this as a no-op (the Postgres transport
     * does, logging one WARN).
     *
     * @param event the lifecycle event
     */
    void publishLifecycleEvent(WorkflowLifecycleEvent event);

    /**
     * Subscribes to inbound signals for the specified service.
     *
     * <p>The handler is invoked for each received signal. It is the
     * handler's responsibility to persist the signal via
     * {@link WorkflowStore#saveSignal} and notify the workflow.
     *
     * <p><b>A handler exception means the signal was not processed.</b> That
     * persist <em>is</em> the durability step: a handler exception means the
     * signal is not yet in the store, and acknowledging it there is the one
     * moment a signal can be truly lost. The implementation must therefore not
     * acknowledge it: it must redeliver the message a bounded number of times,
     * and route a message that exhausts redelivery to a durable, inspectable
     * dead-letter destination. Handler exceptions must never crash the consumer
     * and must never silently discard the signal.
     *
     * @param serviceName the service name to subscribe signals for
     * @param handler     callback invoked for each signal message
     */
    void subscribeSignals(String serviceName, Consumer<SignalMessage> handler);
}
