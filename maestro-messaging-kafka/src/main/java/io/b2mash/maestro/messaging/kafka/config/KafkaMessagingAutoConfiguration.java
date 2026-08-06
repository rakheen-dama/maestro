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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
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
 * <p>The engine's producer and consumer factories are built from Boot's bound
 * {@link KafkaProperties} — every {@code spring.kafka.producer.*} and
 * {@code spring.kafka.consumer.*} property (compression, batching, SSL,
 * arbitrary {@code properties.*} entries, and any {@link KafkaConnectionDetails}
 * bean, e.g. from a service-connection Testcontainers setup) reaches these
 * clients. A small set of wire-format invariants the engine's own protocol
 * depends on — key/value (de)serializer classes and producer {@code acks=all}
 * — are then forced on top, last, so no user property can silently corrupt
 * engine topics. See {@code docs/configuration.md} § Kafka client
 * configuration for the full precedence table.
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
        },
        // Maestro's typed producer/consumer factory beans must register before
        // Boot's KafkaAutoConfiguration evaluates its own @ConditionalOnMissingBean
        // (ProducerFactory/ConsumerFactory) conditions, so Boot's type-conditioned
        // beans back off in favour of Maestro's engine-owned ones — a suppression
        // that used to work only by alphabetical accident between the two package
        // names, now pinned explicitly. This is deliberate, not a bug: Maestro
        // needs a single byte[]-valued producer/consumer pair for its own topics,
        // not Boot's Object-typed general-purpose ones. Do NOT add `afterName` on
        // KafkaAutoConfiguration instead — that would let Boot's typed beans
        // register too and the context would fail with
        // NoUniqueBeanDefinitionException. Boot's bound KafkaProperties is
        // consumed at *instantiation* time (as a constructor-injected parameter),
        // which this declaration ordering does not affect.
        beforeName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
// Audit F8: maestro.enabled=false is documented as the master kill-switch
// (see MaestroAutoConfiguration), but this class previously had no direct
// gate on it — it kept wiring a real KafkaTemplate/producer/consumer
// factories and crashed resolving MaestroProperties (a bean only
// MaestroAutoConfiguration registers) once the engine itself had backed
// off. See KafkaMessagingAutoConfigurationMaestroDisabledTest.
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.messaging", name = "type", havingValue = "kafka", matchIfMissing = true)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaMessagingAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaProducerFactory")
    public ProducerFactory<String, byte[]> maestroKafkaProducerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails
    ) {
        var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        var details = connectionDetails.getIfAvailable();
        if (details != null) {
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, details.getProducer().getBootstrapServers());
        }
        // Engine wire-format invariants — forced LAST, overriding any user value.
        // Documented precedence: docs/configuration.md § Kafka client configuration.
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaTemplate")
    public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
            ProducerFactory<String, byte[]> maestroKafkaProducerFactory,
            ObjectProvider<KafkaTracePropagation> tracePropagation,
            Environment env
    ) {
        var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
        var configured = env.getProperty("spring.kafka.template.observation-enabled", Boolean.class);
        template.setObservationEnabled(observationEnabled(configured, tracePropagation.getIfAvailable() != null));
        return template;
    }

    /**
     * The observation-enablement rule shared by {@code maestroKafkaTemplate}
     * and the {@code @MaestroSignalListener} consumer containers (Issue 23 part
     * 2): an explicit {@code spring.kafka.template.observation-enabled} /
     * {@code spring.kafka.listener.observation-enabled} value always wins;
     * absent that, observation defaults on exactly when tracing is actually
     * wired (a {@link KafkaTracePropagation} collaborator exists).
     *
     * <p>Package-visible so {@code KafkaTemplateObservationTest} in this
     * package can pin it directly. The listener side
     * ({@code MaestroSignalListenerBeanPostProcessor}, a different package)
     * cannot call this method — it duplicates the one-line rule inline with a
     * comment pointing back here, rather than making this a public API.
     *
     * @param configured    the raw property value, or {@code null} when unset
     * @param tracerPresent whether a {@link KafkaTracePropagation} bean exists
     * @return whether observation should be enabled
     */
    static boolean observationEnabled(@Nullable Boolean configured, boolean tracerPresent) {
        return configured != null ? configured : tracerPresent;
    }

    @Bean
    @ConditionalOnMissingBean(name = "maestroKafkaConsumerFactory")
    public ConsumerFactory<String, byte[]> maestroKafkaConsumerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails,
            KafkaMessagingConfig messagingConfig
    ) {
        var props = new HashMap<String, Object>(kafkaProperties.buildConsumerProperties());
        var details = connectionDetails.getIfAvailable();
        if (details != null) {
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, details.getConsumer().getBootstrapServers());
        }
        // Engine wire-format invariants — forced LAST, overriding any user value.
        // Documented precedence: docs/configuration.md § Kafka client configuration.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        // Not a wire-format invariant — a user's explicit
        // spring.kafka.consumer.auto-offset-reset wins.
        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Default group ID — overridden per-container, but prevents confusing
        // errors if this factory is accidentally used outside container scope.
        // Engine-owned, so it is forced rather than putIfAbsent.
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
                redelivery.deadLetterSuffix(),
                redelivery.enabled()
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
}
