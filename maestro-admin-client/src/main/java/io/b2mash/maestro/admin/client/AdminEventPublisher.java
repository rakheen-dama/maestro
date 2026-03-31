package io.b2mash.maestro.admin.client;

import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight publisher for {@link WorkflowLifecycleEvent} records to a Kafka topic.
 *
 * <p>This class serializes lifecycle events using Jackson and sends them to a
 * configurable Kafka topic. Events are keyed by {@code workflowId} to guarantee
 * per-workflow ordering within a topic partition.
 *
 * <h2>Failure Handling</h2>
 * <p>Publishing is <b>fire-and-forget</b>: all exceptions are caught and logged at
 * {@code WARN} level. Lifecycle event failures must never interrupt workflow execution.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The underlying {@link KafkaTemplate} and
 * {@link ObjectMapper} are both thread-safe, and this class holds no mutable state.
 *
 * @see WorkflowLifecycleEvent
 * @see AdminClientAutoConfiguration
 */
@NullMarked
public class AdminEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(AdminEventPublisher.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    /**
     * Creates a new lifecycle event publisher.
     *
     * @param kafkaTemplate the Kafka template for publishing serialized events
     * @param objectMapper  Jackson ObjectMapper for serialization
     * @param topic         the Kafka topic to publish events to
     */
    public AdminEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ObjectMapper objectMapper,
            String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * Publishes a workflow lifecycle event to the admin events Kafka topic.
     *
     * <p>The event is serialized to JSON bytes and sent with {@code event.workflowId()}
     * as the partition key. Publishing is fire-and-forget: any failure is logged at
     * {@code WARN} level and silently swallowed to avoid interrupting workflow execution.
     *
     * @param event the lifecycle event to publish
     */
    public void publish(WorkflowLifecycleEvent event) {
        var key = event.workflowId();
        try {
            var bytes = objectMapper.writeValueAsBytes(event);
            kafkaTemplate.send(topic, key, bytes);
        } catch (Exception e) {
            logger.warn("Failed to publish lifecycle event {} for workflow '{}' to topic '{}'",
                    event.eventType(), key, topic, e);
        }
    }
}
