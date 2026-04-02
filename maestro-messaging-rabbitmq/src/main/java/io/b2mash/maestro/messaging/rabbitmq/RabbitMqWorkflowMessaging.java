package io.b2mash.maestro.messaging.rabbitmq;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
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
 * </ul>
 *
 * <p>All queues are declared as quorum queues ({@code x-queue-type: quorum})
 * for high availability and data safety.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing can be called concurrently from
 * multiple virtual threads. Listener containers are managed in a
 * {@link ConcurrentHashMap} and stopped on {@link #destroy()}.
 *
 * @see WorkflowMessaging
 */
public final class RabbitMqWorkflowMessaging implements WorkflowMessaging, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMqWorkflowMessaging.class);

    static final String TASKS_EXCHANGE = "maestro.tasks";
    static final String SIGNALS_EXCHANGE = "maestro.signals";
    static final String ADMIN_EVENTS_EXCHANGE = "maestro.admin.events";

    private static final Map<String, Object> QUORUM_QUEUE_ARGS = Map.of("x-queue-type", "quorum");

    private final RabbitTemplate rabbitTemplate;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    /**
     * Tracks active listener containers keyed by queue name.
     * Guards against duplicate subscribe calls for the same queue.
     */
    private final ConcurrentHashMap<String, SimpleMessageListenerContainer> containers =
            new ConcurrentHashMap<>();

    /**
     * Creates a new RabbitMQ-based workflow messaging implementation.
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
        this.rabbitTemplate = rabbitTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
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

        if (containers.containsKey(queueName)) {
            logger.warn("Already subscribed to task queue '{}', ignoring duplicate subscribe call", taskQueue);
            return;
        }

        declareTaskTopology(queueName, taskQueue);

        var container = createContainer(queueName, bytes -> {
            try {
                var message = deserialize(bytes, TaskMessage.class);
                handler.accept(message);
            } catch (Exception e) {
                logger.error("Error processing task message from queue '{}': {}",
                        queueName, e.getMessage(), e);
            }
        });
        containers.put(queueName, container);
        container.start();
        logger.info("Subscribed to task queue '{}' on RabbitMQ queue '{}'", taskQueue, queueName);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        var queueName = "maestro.signals." + serviceName;

        if (containers.containsKey(queueName)) {
            logger.warn("Already subscribed to signals for service '{}', ignoring duplicate subscribe call",
                    serviceName);
            return;
        }

        declareSignalTopology(queueName, serviceName);

        var container = createContainer(queueName, bytes -> {
            try {
                var message = deserialize(bytes, SignalMessage.class);
                handler.accept(message);
            } catch (Exception e) {
                logger.error("Error processing signal message from queue '{}': {}",
                        queueName, e.getMessage(), e);
            }
        });
        containers.put(queueName, container);
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
     * Declares the direct exchange, quorum queue, and binding for a task queue.
     * Topology is declared lazily on first subscribe.
     */
    private void declareTaskTopology(String queueName, String routingKey) {
        var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(connectionFactory);
        var exchange = new DirectExchange(TASKS_EXCHANGE, true, false);
        var queue = new Queue(queueName, true, false, false, QUORUM_QUEUE_ARGS);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
    }

    /**
     * Declares the direct exchange, quorum queue, and binding for a signal queue.
     * Topology is declared lazily on first subscribe.
     */
    private void declareSignalTopology(String queueName, String routingKey) {
        var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(connectionFactory);
        var exchange = new DirectExchange(SIGNALS_EXCHANGE, true, false);
        var queue = new Queue(queueName, true, false, false, QUORUM_QUEUE_ARGS);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
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
        container.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
        return container;
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
