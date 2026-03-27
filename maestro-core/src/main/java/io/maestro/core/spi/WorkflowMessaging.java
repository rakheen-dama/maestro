package io.maestro.core.spi;

import java.util.function.Consumer;

/**
 * Messaging SPI for task dispatch, signal delivery, and lifecycle event publishing.
 *
 * <p>Implementations provide the communication backbone between workflow
 * engine components. The reference implementation uses Apache Kafka via
 * Spring Kafka 4.x.
 *
 * <h2>Delivery Guarantees</h2>
 * <ul>
 *   <li>All publish methods provide <b>at-least-once</b> delivery.</li>
 *   <li>Consumers must be idempotent — duplicate messages are possible
 *       during Kafka rebalances or retries.</li>
 *   <li>Message ordering is guaranteed <b>per workflow ID</b> (by using
 *       workflow ID as the Kafka partition key).</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple virtual threads may
 * publish messages concurrently. Subscription handlers are invoked on
 * the messaging implementation's consumer threads.
 *
 * @see TaskMessage
 * @see SignalMessage
 * @see WorkflowLifecycleEvent
 */
public interface WorkflowMessaging {

    /**
     * Publishes a task message to start or resume a workflow.
     *
     * <p>The message is dispatched to the specified task queue. Workers
     * subscribed to that queue will pick it up.
     *
     * @param taskQueue the task queue name
     * @param message   the task message
     */
    void publishTask(String taskQueue, TaskMessage message);

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
     * not interrupt workflow execution.
     *
     * @param event the lifecycle event
     */
    void publishLifecycleEvent(WorkflowLifecycleEvent event);

    /**
     * Subscribes to task messages on the specified queue.
     *
     * <p>The handler is invoked for each received message on the
     * implementation's consumer thread. Handler exceptions should be
     * caught and logged by the implementation — they must not crash
     * the consumer.
     *
     * @param taskQueue the task queue to subscribe to
     * @param handler   callback invoked for each task message
     */
    void subscribe(String taskQueue, Consumer<TaskMessage> handler);

    /**
     * Subscribes to inbound signals for the specified service.
     *
     * <p>The handler is invoked for each received signal. It is the
     * handler's responsibility to persist the signal via
     * {@link WorkflowStore#saveSignal} and notify the workflow.
     *
     * @param serviceName the service name to subscribe signals for
     * @param handler     callback invoked for each signal message
     */
    void subscribeSignals(String serviceName, Consumer<SignalMessage> handler);
}
