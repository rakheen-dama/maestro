package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.listener.MaestroSignalListenerBeanPostProcessor;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configuration for Kafka-based workflow messaging.
 *
 * <p>Activates when:
 * <ul>
 *   <li>Spring Kafka's {@link KafkaTemplate} is on the classpath</li>
 *   <li>{@code maestro.messaging.type} is {@code "kafka"} (default)</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link ProducerFactory} and {@link KafkaTemplate} for byte[] publishing</li>
 *   <li>{@link ConsumerFactory} for byte[] consuming</li>
 *   <li>{@link KafkaMessagingConfig} resolved from {@link MaestroProperties}</li>
 *   <li>{@link KafkaWorkflowMessaging} — the {@link WorkflowMessaging} SPI implementation</li>
 *   <li>{@link MaestroSignalListenerBeanPostProcessor} — annotation scanning and processing</li>
 * </ul>
 *
 * <p>Kafka bootstrap servers are resolved from {@code spring.kafka.bootstrap-servers}
 * (standard Spring Kafka property), falling back to {@code localhost:9092}.
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} to allow
 * user overrides.
 *
 * @see KafkaWorkflowMessaging
 * @see MaestroSignalListenerBeanPostProcessor
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "maestro.messaging", name = "type", havingValue = "kafka", matchIfMissing = true)
public class KafkaMessagingAutoConfiguration {

    private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaProducerFactory")
    public ProducerFactory<String, byte[]> maestroKafkaProducerFactory(Environment env) {
        var props = new HashMap<String, Object>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, resolveBootstrapServers(env));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaTemplate")
    public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
            ProducerFactory<String, byte[]> maestroKafkaProducerFactory
    ) {
        return new KafkaTemplate<>(maestroKafkaProducerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaConsumerFactory")
    public ConsumerFactory<String, byte[]> maestroKafkaConsumerFactory(
            Environment env, KafkaMessagingConfig messagingConfig
    ) {
        var props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolveBootstrapServers(env));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Default group ID — overridden per-container, but prevents confusing
        // errors if this factory is accidentally used outside container scope
        props.put(ConsumerConfig.GROUP_ID_CONFIG, messagingConfig.consumerGroup());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaMessagingConfig maestroKafkaMessagingConfig(MaestroProperties properties) {
        var messaging = properties.getMessaging();
        var topics = messaging.topics();
        var consumerGroup = messaging.consumerGroup();
        if (consumerGroup == null) {
            var serviceName = properties.getServiceName();
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalStateException(
                        "maestro.messaging.consumer-group or maestro.service-name must be configured for Kafka messaging");
            }
            consumerGroup = "maestro-" + serviceName;
        }
        return new KafkaMessagingConfig(
                topics.tasks(),
                topics.signals(),
                topics.adminEvents(),
                consumerGroup
        );
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowMessaging.class)
    public KafkaWorkflowMessaging kafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> maestroKafkaTemplate,
            ConsumerFactory<String, byte[]> maestroKafkaConsumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig maestroKafkaMessagingConfig
    ) {
        return new KafkaWorkflowMessaging(
                maestroKafkaTemplate,
                maestroKafkaConsumerFactory,
                objectMapper,
                maestroKafkaMessagingConfig
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public MaestroSignalListenerBeanPostProcessor maestroSignalListenerBeanPostProcessor() {
        return new MaestroSignalListenerBeanPostProcessor();
    }

    private static String resolveBootstrapServers(Environment env) {
        return env.getProperty("spring.kafka.bootstrap-servers", DEFAULT_BOOTSTRAP_SERVERS);
    }
}
