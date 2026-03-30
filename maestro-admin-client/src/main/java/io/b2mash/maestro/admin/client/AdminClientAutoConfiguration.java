package io.b2mash.maestro.admin.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for the Maestro Admin Client lifecycle event publisher.
 *
 * <p>Activates when:
 * <ul>
 *   <li>{@link KafkaTemplate} is on the classpath</li>
 *   <li>{@code maestro.admin.events.enabled} is {@code true} (default)</li>
 * </ul>
 *
 * <p>Registers an {@link AdminEventPublisher} bean that wraps the application's
 * {@code KafkaTemplate<String, byte[]>} to publish {@link io.b2mash.maestro.core.spi.WorkflowLifecycleEvent}
 * records to a configurable Kafka topic.
 *
 * @see AdminClientProperties
 * @see AdminEventPublisher
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "maestro.admin.events", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AdminClientProperties.class)
public class AdminClientAutoConfiguration {

    /**
     * Creates the {@link AdminEventPublisher} bean if one does not already exist.
     *
     * @param kafkaTemplate the Kafka template for publishing serialized events
     * @param objectMapper  Jackson ObjectMapper for event serialization
     * @param properties    admin client configuration properties
     * @return a configured {@link AdminEventPublisher}
     */
    @Bean
    @ConditionalOnMissingBean
    public AdminEventPublisher adminEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ObjectMapper objectMapper,
            AdminClientProperties properties
    ) {
        return new AdminEventPublisher(kafkaTemplate, objectMapper, properties.getTopic());
    }
}
