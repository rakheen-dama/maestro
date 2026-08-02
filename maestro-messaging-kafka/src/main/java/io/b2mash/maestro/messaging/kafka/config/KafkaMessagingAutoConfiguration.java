package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.listener.MaestroSignalListenerBeanPostProcessor;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.MaestroProperties;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.ObjectMapper;

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
 *   <li>{@link KafkaTracePropagation} — W3C trace-context injection/extraction,
 *       only when Micrometer Tracing is present and enabled</li>
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
@AutoConfiguration(after = MaestroAutoConfiguration.class,
        // Spring Boot's AutoConfigurationSorter falls back to alphabetical order
        // between classes with no declared relative ordering, and
        // `io.b2mash.maestro.messaging.kafka.config` sorts before
        // `org.springframework.boot.micrometer.tracing.*`. Without these entries
        // TracePropagationConfiguration's @ConditionalOnBean({Tracer, Propagator})
        // is evaluated before Boot registers either bean, the collaborator is
        // never created, and every published record silently loses its trace
        // context — the exact shape of the meters bug found in Task 4's fix round
        // 1. Class names absent from the classpath are ignored by the sorter, so
        // naming both bridges is safe.
        afterName = {
                "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration"
        })
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "maestro.messaging", name = "type", havingValue = "kafka", matchIfMissing = true)
public class KafkaMessagingAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);

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
        var redelivery = messaging.redelivery();
        return new KafkaMessagingConfig(
                topics.tasks(),
                topics.signals(),
                resolveAdminEventsTopic(properties),
                consumerGroup,
                redelivery.maxAttempts(),
                redelivery.initialInterval(),
                redelivery.multiplier(),
                redelivery.maxInterval(),
                redelivery.deadLetterSuffix()
        );
    }

    /**
     * Resolves the admin-events topic, honouring the deprecated
     * {@code maestro.admin.events.topic} alias.
     *
     * <p>{@code maestro.messaging.topics.admin-events} is the canonical
     * property. {@code maestro.admin.events.topic} is kept as an alias for
     * deployments that only ever touched the admin block. Both properties
     * carry the same default ({@code "maestro.admin.events"}), so a value
     * that differs from the default is treated as having been explicitly
     * configured:
     * <ul>
     *   <li>Neither customized — the shared default.</li>
     *   <li>Only one customized — that value is used.</li>
     *   <li>Both customized to the same value — that value, no conflict.</li>
     *   <li>Both customized to different values — the messaging property
     *       wins and a WARN is logged, so the conflict is visible rather
     *       than silently dropping the alias.</li>
     * </ul>
     *
     * @param properties the bound Maestro configuration
     * @return the topic to publish/consume admin lifecycle events on
     */
    private static String resolveAdminEventsTopic(MaestroProperties properties) {
        var defaultTopic = MaestroProperties.TopicsProperties.defaults().adminEvents();
        var messagingTopic = properties.getMessaging().topics().adminEvents();
        var aliasTopic = properties.getAdmin().events().topic();

        var messagingCustomized = !messagingTopic.equals(defaultTopic);
        var aliasCustomized = !aliasTopic.equals(defaultTopic);

        if (aliasCustomized && messagingCustomized && !aliasTopic.equals(messagingTopic)) {
            logger.warn("Both maestro.messaging.topics.admin-events ('{}') and the deprecated "
                            + "maestro.admin.events.topic ('{}') are configured — "
                            + "maestro.messaging.topics.admin-events wins. Remove the deprecated property.",
                    messagingTopic, aliasTopic);
            return messagingTopic;
        }
        if (aliasCustomized && !messagingCustomized) {
            return aliasTopic;
        }
        return messagingTopic;
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowMessaging.class)
    public KafkaWorkflowMessaging kafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> maestroKafkaTemplate,
            ConsumerFactory<String, byte[]> maestroKafkaConsumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig maestroKafkaMessagingConfig,
            ObjectProvider<KafkaTracePropagation> tracePropagation
    ) {
        return new KafkaWorkflowMessaging(
                maestroKafkaTemplate,
                maestroKafkaConsumerFactory,
                objectMapper,
                maestroKafkaMessagingConfig,
                tracePropagation.getIfAvailable()
        );
    }

    /**
     * W3C trace-context propagation over Kafka (observability design doc §4,
     * §7.2). Registered only when Micrometer Tracing is on the classpath, a
     * {@code Tracer} <em>and</em> a {@code Propagator} bean exist, and
     * {@code maestro.observability.tracing.enabled} is not {@code false}.
     * Without the bean, {@link KafkaWorkflowMessaging} produces exactly the
     * pre-tracing wire format.
     *
     * <p>Nested in its own {@code @Configuration} so that {@code
     * @ConditionalOnClass} guards the bean method's Micrometer parameter types
     * — the enclosing class must stay loadable with no tracing on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    @ConditionalOnProperty(prefix = "maestro.observability.tracing",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    static class TracePropagationConfiguration {

        /**
         * @param tracer     the Micrometer tracer whose current span is injected
         * @param propagator the Micrometer propagator that owns the wire format
         * @return the Kafka trace-propagation collaborator
         */
        @Bean
        @ConditionalOnBean({Tracer.class, Propagator.class})
        @ConditionalOnMissingBean
        public KafkaTracePropagation maestroKafkaTracePropagation(Tracer tracer, Propagator propagator) {
            return new KafkaTracePropagation(tracer, propagator);
        }
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
