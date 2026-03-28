package io.maestro.messaging.kafka;

import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Apache Kafka implementation of the {@link WorkflowMessaging} SPI.
 *
 * <p>Uses {@code KafkaTemplate<String, byte[]>} for publishing and
 * {@link ConcurrentMessageListenerContainer} for consuming. Messages are
 * serialized with Jackson 3 at the application layer, keeping the Kafka
 * value type as raw bytes for efficiency.
 *
 * <h2>Topic Naming</h2>
 * <ul>
 *   <li>Tasks: {@code maestro.tasks.{taskQueue}} (or fixed override)</li>
 *   <li>Signals: {@code maestro.signals.{serviceName}} (or fixed override)</li>
 *   <li>Admin events: {@code maestro.admin.events} (configurable)</li>
 * </ul>
 *
 * <h2>Partition Key</h2>
 * <p>All messages are keyed by {@code workflowId} to guarantee per-workflow
 * ordering within a topic partition.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing can be called concurrently from
 * multiple virtual threads. Listener containers are managed in a
 * {@link CopyOnWriteArrayList} and stopped on {@link #destroy()}.
 *
 * @see WorkflowMessaging
 * @see KafkaMessagingConfig
 */
public final class KafkaWorkflowMessaging implements WorkflowMessaging, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(KafkaWorkflowMessaging.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ConsumerFactory<String, byte[]> consumerFactory;
    private final ObjectMapper objectMapper;
    private final KafkaMessagingConfig config;
    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers =
            new CopyOnWriteArrayList<>();

    /**
     * Creates a new Kafka-based workflow messaging implementation.
     *
     * @param kafkaTemplate   the Kafka template for publishing messages
     * @param consumerFactory the consumer factory for creating listener containers
     * @param objectMapper    Jackson 3 ObjectMapper for serialization
     * @param config          resolved topic and consumer group configuration
     */
    public KafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ConsumerFactory<String, byte[]> consumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig config
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ── Publishing ───────────────────────────────────────────────────────

    @Override
    public void publishTask(String taskQueue, TaskMessage message) {
        var topic = resolveTaskTopic(taskQueue);
        var key = message.workflowId();
        send(topic, key, message);
    }

    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        var topic = resolveSignalTopic(serviceName);
        var key = message.workflowId();
        send(topic, key, message);
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        var topic = config.adminEventsTopic();
        var key = event.workflowId();
        try {
            var bytes = serialize(event);
            kafkaTemplate.send(topic, key, bytes);
        } catch (Exception e) {
            // SPI contract: lifecycle event failures must not interrupt workflow execution
            logger.warn("Failed to publish lifecycle event {} for workflow '{}' to topic '{}'",
                    event.eventType(), key, topic, e);
        }
    }

    // ── Subscribing ──────────────────────────────────────────────────────

    @Override
    public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        var topic = resolveTaskTopic(taskQueue);
        var container = createContainer(topic, record -> {
            try {
                var message = deserialize(record.value(), TaskMessage.class);
                handler.accept(message);
            } catch (Exception e) {
                logger.error("Error processing task message from topic '{}': {}",
                        topic, e.getMessage(), e);
            }
        });
        containers.add(container);
        container.start();
        logger.info("Subscribed to task queue '{}' on topic '{}'", taskQueue, topic);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        var topic = resolveSignalTopic(serviceName);
        var container = createContainer(topic, record -> {
            try {
                var message = deserialize(record.value(), SignalMessage.class);
                handler.accept(message);
            } catch (Exception e) {
                logger.error("Error processing signal message from topic '{}': {}",
                        topic, e.getMessage(), e);
            }
        });
        containers.add(container);
        container.start();
        logger.info("Subscribed to signals for service '{}' on topic '{}'", serviceName, topic);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void destroy() {
        logger.info("Stopping {} Kafka listener container(s)", containers.size());
        for (var container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                logger.warn("Error stopping Kafka listener container: {}", e.getMessage(), e);
            }
        }
        containers.clear();
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void send(String topic, String key, Object message) {
        var bytes = serialize(message);
        try {
            // Block on the future to ensure at-least-once delivery.
            // Safe on virtual threads — yields the carrier thread while waiting.
            kafkaTemplate.send(topic, key, bytes).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to publish message to Kafka topic '" + topic + "' (key=" + key + ")", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing message to Kafka topic '" + topic + "'", e);
        }
    }

    private ConcurrentMessageListenerContainer<String, byte[]> createContainer(
            String topic,
            MessageListener<String, byte[]> listener
    ) {
        var containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(config.consumerGroup());
        containerProps.setMessageListener(listener);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        return new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
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

    private String resolveTaskTopic(String taskQueue) {
        return config.tasksTopic() != null
                ? config.tasksTopic()
                : "maestro.tasks." + taskQueue;
    }

    private String resolveSignalTopic(String serviceName) {
        return config.signalsTopic() != null
                ? config.signalsTopic()
                : "maestro.signals." + serviceName;
    }
}
