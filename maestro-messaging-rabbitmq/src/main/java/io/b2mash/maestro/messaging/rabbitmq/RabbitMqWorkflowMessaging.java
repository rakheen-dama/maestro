package io.b2mash.maestro.messaging.rabbitmq;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.DisposableBean;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * RabbitMQ implementation of the {@link WorkflowMessaging} SPI.
 *
 * <p>Uses {@link RabbitTemplate} for publishing and
 * {@link SimpleMessageListenerContainer} for consuming. Messages are
 * serialized with Jackson 3 at the application layer, keeping the
 * RabbitMQ message body as raw bytes for efficiency.
 *
 * <h2>Exchange Topology</h2>
 * <ul>
 *   <li>Tasks: {@code maestro.tasks} — direct exchange, routing key = task queue name</li>
 *   <li>Signals: {@code maestro.signals} — direct exchange, routing key = service name</li>
 *   <li>Admin events: {@code maestro.admin.events} — fanout exchange</li>
 *   <li>Dead letters: {@link RabbitMqRedeliveryConfig#deadLetterExchange()} — direct
 *       exchange, one {@code <queueName>.dlq} quorum queue per source queue, bound
 *       with routing key = the source queue name</li>
 * </ul>
 *
 * <p>All queues are declared as quorum queues ({@code x-queue-type: quorum})
 * for high availability and data safety. Every declaration is idempotent, so
 * repeated {@code subscribe}/{@code subscribeSignals} calls — or an operator
 * pre-declaring the same names out of band — are safe.
 *
 * <h2>Handler Failure</h2>
 * <p>Handler exceptions are never swallowed: they propagate out of the raw
 * message listener to a stateless retry interceptor on the listener
 * container's advice chain. The interceptor re-invokes the handler in-process
 * with exponential backoff and, once {@link RabbitMqRedeliveryConfig#maxAttempts()}
 * is spent, a {@link RepublishMessageRecoverer} republishes the message to the
 * dead-letter exchange (routing key = the source queue name, {@code
 * x-exception-*} headers added) — only then is the original message
 * acknowledged. {@link AcknowledgeMode#AUTO} is safe here specifically
 * because the container only acks after the advice chain returns normally;
 * a message is never acknowledged unprocessed. Handlers may therefore be
 * re-invoked with the same message and must be idempotent.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing can be called concurrently from
 * multiple virtual threads. Listener containers are managed in a
 * {@link ConcurrentHashMap} and stopped on {@link #destroy()}.
 *
 * @see WorkflowMessaging
 * @see RabbitMqRedeliveryConfig
 */
@NullMarked
public final class RabbitMqWorkflowMessaging implements WorkflowMessaging, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMqWorkflowMessaging.class);

    static final String TASKS_EXCHANGE = "maestro.tasks";
    static final String SIGNALS_EXCHANGE = "maestro.signals";
    static final String ADMIN_EVENTS_EXCHANGE = "maestro.admin.events";

    private static final Map<String, Object> QUORUM_QUEUE_ARGS = Map.of("x-queue-type", "quorum");

    private final RabbitTemplate rabbitTemplate;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final RabbitMqRedeliveryConfig redelivery;
    private final RabbitAdmin admin;

    /**
     * Tracks active listener containers keyed by queue name.
     * Guards against duplicate subscribe calls for the same queue.
     */
    private final ConcurrentHashMap<String, SimpleMessageListenerContainer> containers =
            new ConcurrentHashMap<>();

    /**
     * Creates a new RabbitMQ-based workflow messaging implementation with the
     * default redelivery policy.
     *
     * @param rabbitTemplate    the RabbitMQ template for publishing messages
     * @param connectionFactory the connection factory for creating listener containers
     * @param objectMapper      Jackson 3 ObjectMapper for serialization
     */
    public RabbitMqWorkflowMessaging(
            RabbitTemplate rabbitTemplate,
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        this(rabbitTemplate, connectionFactory, objectMapper, RabbitMqRedeliveryConfig.defaults());
    }

    /**
     * Creates a new RabbitMQ-based workflow messaging implementation.
     *
     * @param rabbitTemplate    the RabbitMQ template for publishing messages
     * @param connectionFactory the connection factory for creating listener containers
     * @param objectMapper      Jackson 3 ObjectMapper for serialization
     * @param redelivery        handler-failure redelivery and dead-letter policy
     */
    public RabbitMqWorkflowMessaging(
            RabbitTemplate rabbitTemplate,
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            RabbitMqRedeliveryConfig redelivery
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.redelivery = redelivery;
        this.admin = new RabbitAdmin(connectionFactory);
        declareAdminTopology();
    }

    // ── Publishing ───────────────────────────────────────────────────────

    @Override
    public void publishTask(String taskQueue, TaskMessage message) {
        var bytes = serialize(message);
        rabbitTemplate.convertAndSend(TASKS_EXCHANGE, taskQueue, bytes);
    }

    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        var bytes = serialize(message);
        rabbitTemplate.convertAndSend(SIGNALS_EXCHANGE, serviceName, bytes);
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        try {
            var bytes = serialize(event);
            rabbitTemplate.convertAndSend(ADMIN_EVENTS_EXCHANGE, "", bytes);
        } catch (Exception e) {
            // SPI contract: lifecycle event failures must not interrupt workflow execution
            logger.warn("Failed to publish lifecycle event {} for workflow '{}' to exchange '{}'",
                    event.eventType(), event.workflowId(), ADMIN_EVENTS_EXCHANGE, e);
        }
    }

    // ── Subscribing ──────────────────────────────────────────────────────

    @Override
    public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        var queueName = "maestro.tasks." + taskQueue;
        declareTaskTopology(queueName, taskQueue);

        // Nothing is caught here on purpose: a handler failure — or an
        // undeserializable message — must reach the container's retry
        // interceptor so the message is not acknowledged. See the class Javadoc.
        var container = createContainer(queueName, bytes ->
                handler.accept(deserialize(bytes, TaskMessage.class)));

        var existing = containers.putIfAbsent(queueName, container);
        if (existing != null) {
            container.stop();  // Clean up the unused container
            logger.warn("Already subscribed to task queue '{}', ignoring duplicate", taskQueue);
            return;
        }
        container.start();
        logger.info("Subscribed to task queue '{}' on RabbitMQ queue '{}'", taskQueue, queueName);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        var queueName = "maestro.signals." + serviceName;
        declareSignalTopology(queueName, serviceName);

        // Nothing is caught here on purpose: a handler failure means the
        // signal is not yet durable, so it must reach the container's retry
        // interceptor rather than being acknowledged. See the class Javadoc.
        var container = createContainer(queueName, bytes ->
                handler.accept(deserialize(bytes, SignalMessage.class)));

        var existing = containers.putIfAbsent(queueName, container);
        if (existing != null) {
            container.stop();  // Clean up the unused container
            logger.warn("Already subscribed to signals for service '{}', ignoring duplicate", serviceName);
            return;
        }
        container.start();
        logger.info("Subscribed to signals for service '{}' on RabbitMQ queue '{}'", serviceName, queueName);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void destroy() {
        logger.info("Stopping {} RabbitMQ listener container(s)", containers.size());
        for (var entry : containers.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                logger.warn("Error stopping RabbitMQ listener container for queue '{}': {}",
                        entry.getKey(), e.getMessage(), e);
            }
        }
        containers.clear();
    }

    // ── Topology Declaration ─────────────────────────────────────────────

    /**
     * Declares the direct exchange, quorum queue, and binding for a task queue,
     * plus its dead-letter exchange and queue.
     * Topology is declared lazily on first subscribe.
     */
    private void declareTaskTopology(String queueName, String routingKey) {
        var exchange = new DirectExchange(TASKS_EXCHANGE, true, false);
        var queue = new Queue(queueName, true, false, false, QUORUM_QUEUE_ARGS);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        declareDeadLetterTopology(queueName);
    }

    /**
     * Declares the fanout exchange for admin lifecycle events.
     * Called eagerly in the constructor so the exchange exists before any publish.
     */
    private void declareAdminTopology() {
        var exchange = new FanoutExchange(ADMIN_EVENTS_EXCHANGE, true, false);
        admin.declareExchange(exchange);
    }

    /**
     * Declares the direct exchange, quorum queue, and binding for a signal queue,
     * plus its dead-letter exchange and queue.
     * Topology is declared lazily on first subscribe.
     */
    private void declareSignalTopology(String queueName, String routingKey) {
        var exchange = new DirectExchange(SIGNALS_EXCHANGE, true, false);
        var queue = new Queue(queueName, true, false, false, QUORUM_QUEUE_ARGS);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        declareDeadLetterTopology(queueName);
    }

    /**
     * Declares this queue's dead-letter exchange and {@code <queueName>.dlq}
     * quorum queue, idempotently, exactly like the rest of this class's
     * topology. Unlike Kafka — where Maestro never auto-creates topics — the
     * RabbitMQ transport already self-declares its own exchanges and queues,
     * so the dead-letter destination follows the same, already-accepted
     * pattern (design ruling, {@code issue1-design.md} §10). Operators may
     * equivalently pre-declare the same names out of band.
     *
     * @param queueName the source queue whose exhausted messages land here
     */
    private void declareDeadLetterTopology(String queueName) {
        var deadLetterExchange = new DirectExchange(redelivery.deadLetterExchange(), true, false);
        var deadLetterQueue = new Queue(queueName + ".dlq", true, false, false, QUORUM_QUEUE_ARGS);
        admin.declareExchange(deadLetterExchange);
        admin.declareQueue(deadLetterQueue);
        admin.declareBinding(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queueName));
    }

    // ── Internal Helpers ─────────────────────────────────────────────────

    private SimpleMessageListenerContainer createContainer(
            String queueName,
            Consumer<byte[]> listener
    ) {
        var container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setMessageListener(new MessageListenerAdapter(
                new RawBytesHandler(listener), "handleMessage"));
        // AUTO is safe here because the advice chain below only lets the
        // container ack after a successful handler invocation or a successful
        // dead-letter republish — never after an unhandled exception.
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setAdviceChain(buildRetryInterceptor(queueName));
        return container;
    }

    /**
     * Builds the stateless retry interceptor shared by every listener
     * container: bounded, backed-off in-process redelivery, then a republish
     * to this queue's dead-letter destination.
     *
     * @param queueName the source queue, used as the dead-letter routing key
     * @return an advice for {@link SimpleMessageListenerContainer#setAdviceChain}
     */
    private org.aopalliance.aop.Advice buildRetryInterceptor(String queueName) {
        // Spring Retry's SimpleRetryPolicy (the default here) classifies on
        // Throwable but only counts subclasses of Exception towards the retry
        // budget; a Throwable that is an Error would bypass retries entirely
        // and dead-letter on the first attempt. Unreachable today — no engine
        // path throws ExecutorShutdownException (an Error; see its Javadoc)
        // on a listener thread — but keep it that way if this container's
        // threading model changes.
        var recoverer = new RepublishMessageRecoverer(
                rabbitTemplate, redelivery.deadLetterExchange(), queueName);
        return RetryInterceptorBuilder.stateless()
                .maxRetries(Math.max(0, redelivery.maxAttempts() - 1))
                .backOffOptions(
                        redelivery.initialInterval().toMillis(),
                        redelivery.multiplier(),
                        redelivery.maxInterval().toMillis())
                .recoverer(recoverer)
                .build();
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to serialize message of type " + value.getClass().getName(), e);
        }
    }

    private <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize message to " + type.getName(), e);
        }
    }

    /**
     * Simple delegate that receives raw byte[] messages from the
     * {@link MessageListenerAdapter}. The adapter calls {@code handleMessage(byte[])}
     * which delegates to the provided consumer.
     */
    static final class RawBytesHandler {

        private final Consumer<byte[]> delegate;

        RawBytesHandler(Consumer<byte[]> delegate) {
            this.delegate = delegate;
        }

        @SuppressWarnings("unused") // Called reflectively by MessageListenerAdapter
        public void handleMessage(byte[] body) {
            delegate.accept(body);
        }
    }
}
