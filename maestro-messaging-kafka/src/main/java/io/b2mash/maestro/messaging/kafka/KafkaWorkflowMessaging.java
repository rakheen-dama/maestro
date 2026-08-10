package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
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
 * <h2>Trace Propagation</h2>
 * <p>When a {@link KafkaTracePropagation} collaborator is supplied, every
 * published record carries the W3C trace context of the span active at publish
 * time, and every consumed record's handler runs under the remote context it
 * carried — see that class for the exact wire contract. Without the
 * collaborator the wire format is byte-identical to a build with no tracing.
 *
 * <h2>Partition Key</h2>
 * <p>All messages are keyed by {@code workflowId} to guarantee per-workflow
 * ordering within a topic partition.
 *
 * <h2>Handler Failure</h2>
 * <p>Handler exceptions are never swallowed: they reach the container, whose
 * error handler redelivers the record with exponential backoff and, once the
 * attempt budget is spent, publishes it to {@code <topic><deadLetterSuffix>}.
 * The offset is committed only after the handler succeeds or the record is
 * dead-lettered, so a message is never acknowledged unprocessed. Handlers may
 * therefore be re-invoked with the same message and must be idempotent.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing can be called concurrently from
 * multiple virtual threads. Listener containers are managed in a
 * {@link CopyOnWriteArrayList} and stopped on {@link #destroy()}.
 *
 * @see WorkflowMessaging
 * @see KafkaMessagingConfig
 * @see KafkaRedeliveryErrorHandlers
 */
public final class KafkaWorkflowMessaging implements WorkflowMessaging, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(KafkaWorkflowMessaging.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ConsumerFactory<String, byte[]> consumerFactory;
    private final ObjectMapper objectMapper;
    private final KafkaMessagingConfig config;
    private final @Nullable KafkaTracePropagation tracePropagation;
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
        this(kafkaTemplate, consumerFactory, objectMapper, config, null);
    }

    /**
     * Creates a new Kafka-based workflow messaging implementation with W3C trace
     * context propagation.
     *
     * @param kafkaTemplate    the Kafka template for publishing messages
     * @param consumerFactory  the consumer factory for creating listener containers
     * @param objectMapper     Jackson 3 ObjectMapper for serialization
     * @param config           resolved topic and consumer group configuration
     * @param tracePropagation injects W3C headers on publish and restores the
     *                         remote context around each handler call; when
     *                         {@code null} the wire format is byte-identical to
     *                         a build with no tracing at all
     */
    public KafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ConsumerFactory<String, byte[]> consumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig config,
            @Nullable KafkaTracePropagation tracePropagation
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
        this.objectMapper = objectMapper;
        this.config = config;
        this.tracePropagation = tracePropagation;
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
            kafkaTemplate.send(tracedRecord(topic, key, serialize(event)));
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
        if (config.redeliveryEnabled()) {
            KafkaDeadLetterTopicCheck.warnOnMissing(consumerFactory, topic, config.deadLetterSuffix());
        }
        // Nothing is caught here on purpose: a handler failure — or an
        // undeserializable record — must reach the container's error handler so
        // the offset is not committed. See the class Javadoc.
        var container = createContainer(topic, record -> {
            var message = deserialize(record.value(), TaskMessage.class);
            if (tracePropagation == null) {
                handler.accept(message);
            } else {
                tracePropagation.runWithExtractedContext(record.headers(), () -> handler.accept(message));
            }
        });
        containers.add(container);
        container.start();
        logger.info("Subscribed to task queue '{}' on topic '{}'", taskQueue, topic);
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        var topic = resolveSignalTopic(serviceName);
        if (config.redeliveryEnabled()) {
            KafkaDeadLetterTopicCheck.warnOnMissing(consumerFactory, topic, config.deadLetterSuffix());
        }
        // The only place the raw ConsumerRecord — and therefore its W3C
        // headers — is visible before the payload-typed handler runs.
        var container = createContainer(topic, record -> {
            var message = deserialize(record.value(), SignalMessage.class);
            if (tracePropagation == null) {
                handler.accept(message);
            } else {
                tracePropagation.runWithExtractedContext(record.headers(), () -> handler.accept(message));
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
        var record = tracedRecord(topic, key, serialize(message));
        try {
            // Block on the future to ensure at-least-once delivery.
            // Safe on virtual threads — yields the carrier thread while waiting.
            kafkaTemplate.send(record).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to publish message to Kafka topic '" + topic + "' (key=" + key + ")", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing message to Kafka topic '" + topic + "'", e);
        }
    }

    /**
     * Builds the outbound record, injecting the current span's W3C trace context
     * when tracing is configured. Without the collaborator the record is exactly
     * what {@code kafkaTemplate.send(topic, key, bytes)} would have produced.
     */
    private ProducerRecord<String, byte[]> tracedRecord(String topic, String key, byte[] bytes) {
        var record = new ProducerRecord<String, byte[]>(topic, key, bytes);
        if (tracePropagation != null) {
            tracePropagation.inject(record.headers());
        }
        return record;
    }

    private ConcurrentMessageListenerContainer<String, byte[]> createContainer(
            String topic,
            MessageListener<String, byte[]> listener
    ) {
        var containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(config.consumerGroup());
        containerProps.setMessageListener(listener);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.setCommonErrorHandler(config.redeliveryEnabled()
                ? KafkaRedeliveryErrorHandlers.deadLettering(
                        kafkaTemplate,
                        config.maxAttempts(),
                        config.initialInterval(),
                        config.multiplier(),
                        config.maxInterval(),
                        config.deadLetterSuffix())
                // maestro.messaging.redelivery.enabled=false: the operator's explicit
                // choice to restore at-most-once handler semantics — zero retries, no
                // DeadLetterPublishingRecoverer, a failing record is logged and skipped.
                : new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
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
