package io.b2mash.maestro.admin.kafka;

import io.b2mash.maestro.admin.config.AdminProperties;
import io.b2mash.maestro.admin.projection.EventProjector;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes workflow lifecycle events from Kafka and delegates them to the
 * {@link EventProjector} for persistence in the admin database.
 *
 * <p>Implements {@link SmartLifecycle} to manage the Kafka listener container
 * lifecycle within the Spring application context. The container subscribes to
 * the events topic configured in {@link AdminProperties} and uses per-record
 * acknowledgement to ensure reliable processing.
 *
 * <h2>Error Handling</h2>
 * <p>Deserialization and projection errors are logged per-message without
 * crashing the consumer. This ensures that a single malformed event does not
 * block processing of subsequent events.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The underlying
 * {@link ConcurrentMessageListenerContainer} manages its own threading.
 *
 * @see EventProjector
 * @see AdminProperties
 */
@Component
public class AdminEventConsumer implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AdminEventConsumer.class);

    private final ConsumerFactory<String, byte[]> consumerFactory;
    private final ObjectMapper objectMapper;
    private final AdminProperties adminProperties;
    private final EventProjector eventProjector;

    private volatile @Nullable ConcurrentMessageListenerContainer<String, byte[]> container;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new admin event consumer.
     *
     * @param consumerFactory Spring Boot auto-configured consumer factory
     * @param objectMapper    Jackson 3 ObjectMapper for deserialization
     * @param adminProperties admin configuration properties
     * @param eventProjector  projector that persists events to the admin database
     */
    public AdminEventConsumer(
            ConsumerFactory<String, byte[]> consumerFactory,
            ObjectMapper objectMapper,
            AdminProperties adminProperties,
            EventProjector eventProjector
    ) {
        this.consumerFactory = consumerFactory;
        this.objectMapper = objectMapper;
        this.adminProperties = adminProperties;
        this.eventProjector = eventProjector;
    }

    // ── SmartLifecycle ──────────────────────────────────────────────

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        var topic = adminProperties.getEventsTopic();
        var groupId = adminProperties.getConsumerGroup();

        logger.info("Starting admin event consumer on topic '{}' with group '{}'", topic, groupId);

        var containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(groupId);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProps.setMessageListener((MessageListener<String, byte[]>) record -> {
            try {
                var event = objectMapper.readValue(record.value(), WorkflowLifecycleEvent.class);
                eventProjector.project(event);
            } catch (Exception e) {
                logger.error("Failed to process lifecycle event from topic '{}' partition {} offset {}: {}",
                        record.topic(), record.partition(), record.offset(), e.getMessage(), e);
            }
        });

        container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.start();

        logger.info("Admin event consumer started successfully");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        logger.info("Stopping admin event consumer");

        var currentContainer = container;
        if (currentContainer != null) {
            try {
                currentContainer.stop();
            } catch (Exception e) {
                logger.warn("Error stopping admin event consumer container: {}", e.getMessage(), e);
            }
            container = null;
        }

        logger.info("Admin event consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Start after default Spring beans, stop before them
        return Integer.MAX_VALUE - 100;
    }
}
