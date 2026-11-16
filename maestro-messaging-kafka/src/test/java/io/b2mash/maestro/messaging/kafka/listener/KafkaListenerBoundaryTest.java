package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.CONSUMER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.consumerConfig;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.getField;

/**
 * Pins the boundary between a host's plain {@code @KafkaListener} and a
 * {@code @MaestroSignalListener} in one application (WP7, todo A6).
 *
 * <p>A plain {@code @KafkaListener} is Spring Boot's: Boot's
 * {@code kafkaListenerContainerFactory} over Boot's {@code kafkaConsumerFactory}
 * bean, {@code spring.kafka.consumer.*} deserializers, {@code spring.kafka.listener.*},
 * its own group, the host's {@code CommonErrorHandler} bean and Boot's
 * {@code kafkaTemplate} as reply template. A {@code @MaestroSignalListener} is
 * Maestro's: a container the post-processor builds itself (not through any
 * container factory or the endpoint registry) over {@code maestroKafkaConsumerFactory},
 * with the forced {@code ByteArrayDeserializer}, the engine-owned group
 * {@code maestro-<service>}, {@code AckMode.RECORD} and Maestro's dead-lettering
 * error handler publishing through {@code maestroKafkaTemplate}. Neither leaks
 * into the other.
 *
 * <p>Built from the real auto-configurations ({@code CoexistenceFixtures.coexistenceRunner()}).
 * The mocked {@link WorkflowExecutor} is a collaborator, not under test. No
 * broker: the host container is not started ({@code spring.kafka.listener.auto-startup=false}),
 * and the Maestro container starts and polls an unreachable address in the
 * background, as in {@code MaestroSignalListenerContainerConfigTest}; the wiring
 * asserted here is fixed before it polls.
 */
@DisplayName("The plain @KafkaListener / @MaestroSignalListener boundary")
class KafkaListenerBoundaryTest {

    /**
     * The connection-details broker: a resolvable address that differs from
     * {@code spring.kafka.bootstrap-servers} ({@code localhost:1}). Nothing
     * listens there. It must resolve, because the Maestro container constructs
     * its consumer during context refresh, and a consumer refuses an
     * unresolvable bootstrap host ({@code ConfigException: No resolvable
     * bootstrap urls}).
     */
    static final String DETAILS_BOOTSTRAP = "127.0.0.1:19092";

    /** An address nothing listens on, for the tests added after the first two. */
    static final String UNREACHABLE = "127.0.0.1:1";

    private final ApplicationContextRunner runner = coexistenceRunner()
            .withUserConfiguration(BoundaryConfiguration.class)
            .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
            .withPropertyValues(
                    "maestro.service-name=boundary",
                    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.LongDeserializer",
                    "spring.kafka.listener.ack-mode=batch",
                    "spring.kafka.listener.auto-startup=false");

    @Test
    @DisplayName("a plain @KafkaListener rides Boot's factory and beans; a @MaestroSignalListener rides Maestro's; neither leaks into the other")
    void aPlainKafkaListenerRidesBootsFactoryAndAMaestroListenerRidesMaestros() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(registry.getListenerContainerIds())
                    .as("only the plain listener is built through Boot's container factory and registry")
                    .containsExactly("host-listener");

            // ── The plain @KafkaListener: Boot's container factory and beans ──
            var host = (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer("host-listener");
            var bootFactory = ctx.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(bootFactory.getConsumerFactory()).isSameAs(ctx.getBean("kafkaConsumerFactory"));
            assertThat(getField(host, "consumerFactory")).isSameAs(ctx.getBean("kafkaConsumerFactory"));
            assertThat(consumerConfig(host))
                    .containsEntry(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
            assertThat(host.getContainerProperties().getGroupId()).isEqualTo("host-group");
            assertThat(host.getContainerProperties().getAckMode()).isEqualTo(AckMode.BATCH);
            assertThat(host.isAutoStartup()).isFalse();
            assertThat(host.getCommonErrorHandler()).isSameAs(ctx.getBean("hostErrorHandler"));
            assertThat(recoverer(host.getCommonErrorHandler()))
                    .as("no dead-letter topic unless the host configures one")
                    .isNotInstanceOf(DeadLetterPublishingRecoverer.class);
            assertThat(getField(bootFactory, "replyTemplate")).isSameAs(ctx.getBean("kafkaTemplate"));

            // ── The @MaestroSignalListener: Maestro's consumer factory, group, ack mode, DLT ──
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
            assertThat(consumerConfig(maestro))
                    .containsEntry(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class)
                    .containsEntry(GROUP_ID_CONFIG, "maestro-boundary");
            assertThat(maestro.getContainerProperties().getGroupId()).isEqualTo("maestro-boundary");
            assertThat(maestro.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
            assertThat(maestro.isRunning())
                    .as("spring.kafka.listener.auto-startup=false does not reach a Maestro container")
                    .isTrue();
            assertThat(maestro.getCommonErrorHandler())
                    .isNotSameAs(ctx.getBean("hostErrorHandler"))
                    .isInstanceOf(DefaultErrorHandler.class);
            var maestroRecoverer = recoverer(maestro.getCommonErrorHandler());
            assertThat(maestroRecoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
            @SuppressWarnings("unchecked")
            var templateResolver = (Function<ProducerRecord<?, ?>, ?>) getField(maestroRecoverer, "templateResolver");
            assertThat(templateResolver.apply(new ProducerRecord<>("boundary.signals.DLT", "k", new byte[] {1})))
                    .as("dead letters go through Maestro's byte[] template")
                    .isSameAs(ctx.getBean(TEMPLATE));
        });
    }

    @Test
    @DisplayName("KafkaConnectionDetails reach a plain @KafkaListener's consumer and Maestro's")
    void aPlainKafkaListenerHonoursKafkaConnectionDetails() {
        runner.withUserConfiguration(ConnectionDetailsConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var host = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(consumerConfig(host).get(BOOTSTRAP_SERVERS_CONFIG)).hasToString("[" + DETAILS_BOOTSTRAP + "]");
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(consumerConfig(maestro).get(BOOTSTRAP_SERVERS_CONFIG)).hasToString("[" + DETAILS_BOOTSTRAP + "]");
        });
    }

    @Test
    @DisplayName("with no CommonErrorHandler bean, a started plain @KafkaListener gets Spring Kafka's default handler, with no dead-letter recoverer")
    void withoutAHostErrorHandler_aPlainKafkaListenerHasNoDeadLetterRecoverer() {
        coexistenceRunner()
                .withUserConfiguration(ListenersOnlyConfiguration.class)
                .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
                .withPropertyValues(
                        "maestro.service-name=boundary",
                        "spring.kafka.bootstrap-servers=" + UNREACHABLE,
                        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.LongDeserializer",
                        "spring.kafka.listener.auto-startup=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(CommonErrorHandler.class)).isEmpty();
                    var host = (ConcurrentMessageListenerContainer<?, ?>)
                            ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
                    assertThat(host.getCommonErrorHandler()).as("Boot set none").isNull();
                    host.start();
                    try {
                        // The started consumer's own handler: Spring Kafka's default when none is set.
                        var consumer = getField(host.getContainers().getFirst(), "listenerConsumer");
                        var effective = (CommonErrorHandler) getField(consumer, "commonErrorHandler");
                        assertThat(effective).isInstanceOf(DefaultErrorHandler.class);
                        assertThat(recoverer(effective))
                                .as("no dead-letter topic unless the host configures one")
                                .isNotInstanceOf(DeadLetterPublishingRecoverer.class);
                    }
                    finally {
                        host.stop();
                    }
                });
    }

    @Test
    @DisplayName("of several CommonErrorHandler beans, a plain @KafkaListener gets the @Primary one, and none without a @Primary")
    void ofSeveralHostErrorHandlers_aPlainKafkaListenerGetsThePrimaryOneOrNone() {
        runner.withUserConfiguration(PrimarySecondErrorHandler.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var host = (ConcurrentMessageListenerContainer<?, ?>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(host.getCommonErrorHandler()).isSameAs(ctx.getBean("secondErrorHandler"));
        });
        runner.withUserConfiguration(PlainSecondErrorHandler.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(CommonErrorHandler.class)).hasSize(2);
            var host = (ConcurrentMessageListenerContainer<?, ?>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(host.getCommonErrorHandler()).as("two candidates, no @Primary: Boot sets none").isNull();
        });
    }

    @Test
    @DisplayName("after typed plain-@Bean replacements of Maestro's consumer factory and template, a plain @KafkaListener gets an inline factory and no reply template")
    void afterTypedPlainReplacements_aPlainKafkaListenerGetsAnInlineFactoryAndNoReplyTemplate() {
        runner.withUserConfiguration(TypedPlainReplacements.class, ConnectionDetailsConfiguration.class)
                .withPropertyValues("spring.kafka.bootstrap-servers=" + UNREACHABLE)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed()
                            .doesNotHaveBean("kafkaConsumerFactory")
                            .doesNotHaveBean("kafkaTemplate");
                    var host = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
                    var hostFactory = getField(host, "consumerFactory");
                    assertThat(hostFactory)
                            .as("an inline factory, not any ConsumerFactory bean")
                            .isInstanceOf(DefaultKafkaConsumerFactory.class)
                            .isNotIn(ctx.getBeansOfType(ConsumerFactory.class).values());
                    assertThat(consumerConfig(host))
                            .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
                    assertThat(consumerConfig(host).get(BOOTSTRAP_SERVERS_CONFIG))
                            .as("the inline factory ignores KafkaConnectionDetails")
                            .hasToString("[" + UNREACHABLE + "]");
                    var bootFactory = ctx.getBean("kafkaListenerContainerFactory",
                            ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(getField(bootFactory, "replyTemplate")).isNull();
                    var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
                    assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
                });
    }

    @Test
    @DisplayName("after wildcard-typed plain-@Bean replacements, a plain @KafkaListener gets the host's byte[] factory and template")
    void afterWildcardPlainReplacements_aPlainKafkaListenerGetsTheReplacements() {
        runner.withUserConfiguration(WildcardPlainReplacements.class).run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .doesNotHaveBean("kafkaConsumerFactory")
                    .doesNotHaveBean("kafkaTemplate");
            var host = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(getField(host, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
            assertThat(consumerConfig(host))
                    .as("the replacement's deserializer, not spring.kafka.consumer.value-deserializer")
                    .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            var bootFactory = ctx.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(getField(bootFactory, "replyTemplate")).isSameAs(ctx.getBean(TEMPLATE));
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
        });
    }

    @Test
    @DisplayName("after defaultCandidate = false replacements, a plain @KafkaListener keeps Boot's consumer factory and reply template")
    void afterNonDefaultCandidateReplacements_aPlainKafkaListenerKeepsBootsBeans() {
        runner.withUserConfiguration(NonDefaultCandidateReplacements.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var host = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(getField(host, "consumerFactory")).isSameAs(ctx.getBean("kafkaConsumerFactory"));
            var bootFactory = ctx.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(getField(bootFactory, "replyTemplate")).isSameAs(ctx.getBean("kafkaTemplate"));
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
        });
    }

    private static Object recoverer(CommonErrorHandler handler) {
        return getField(getField(handler, "failureTracker"), "recoverer");
    }

    /** A host's plain {@code @KafkaListener} for its own {@code Long} payload. */
    public static class HostOrderListener {
        @KafkaListener(id = "host-listener", topics = "host.orders", groupId = "host-group")
        public void onOrder(Long value) {
        }
    }

    /** A {@code @MaestroSignalListener} in the same application. */
    public static class BoundarySignalListener {
        @MaestroSignalListener(topic = "boundary.signals", signalName = "boundary")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BoundaryConfiguration {

        @Bean
        HostOrderListener hostOrderListener() {
            return new HostOrderListener();
        }

        @Bean
        BoundarySignalListener boundarySignalListener() {
            return new BoundarySignalListener();
        }

        @Bean
        CommonErrorHandler hostErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(0L, 1L));
        }
    }

    private static DefaultKafkaConsumerFactory<String, byte[]> byteArrayConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                BOOTSTRAP_SERVERS_CONFIG, UNREACHABLE,
                KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
    }

    private static KafkaTemplate<String, byte[]> byteArrayTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, UNREACHABLE,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
    }

    /** Plain {@code @Bean} replacements of Maestro's consumer factory and template, typed for {@code byte[]}. */
    @Configuration(proxyBeanMethods = false)
    static class TypedPlainReplacements {

        @Bean(name = CONSUMER_FACTORY)
        ConsumerFactory<String, byte[]> consumerFactoryReplacement() {
            return byteArrayConsumerFactory();
        }

        @Bean(name = TEMPLATE)
        KafkaTemplate<String, byte[]> templateReplacement() {
            return byteArrayTemplate();
        }
    }

    /** The same replacements, the documented way: not default candidates. */
    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateReplacements {

        @Bean(name = CONSUMER_FACTORY, defaultCandidate = false)
        ConsumerFactory<String, byte[]> consumerFactoryReplacement() {
            return byteArrayConsumerFactory();
        }

        @Bean(name = TEMPLATE, defaultCandidate = false)
        KafkaTemplate<String, byte[]> templateReplacement() {
            return byteArrayTemplate();
        }
    }

    /** The same replacements, declared with wildcard generics. */
    @Configuration(proxyBeanMethods = false)
    static class WildcardPlainReplacements {

        @Bean(name = CONSUMER_FACTORY)
        ConsumerFactory<?, ?> consumerFactoryReplacement() {
            return byteArrayConsumerFactory();
        }

        @Bean(name = TEMPLATE)
        KafkaTemplate<?, ?> templateReplacement() {
            return byteArrayTemplate();
        }
    }

    /** The two listeners of {@link BoundaryConfiguration}, without a host {@code CommonErrorHandler} bean. */
    @Configuration(proxyBeanMethods = false)
    static class ListenersOnlyConfiguration {

        @Bean
        HostOrderListener hostOrderListener() {
            return new HostOrderListener();
        }

        @Bean
        BoundarySignalListener boundarySignalListener() {
            return new BoundarySignalListener();
        }
    }

    /** A second host {@code CommonErrorHandler}, {@code @Primary}. */
    @Configuration(proxyBeanMethods = false)
    static class PrimarySecondErrorHandler {

        @Bean
        @Primary
        CommonErrorHandler secondErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(0L, 2L));
        }
    }

    /** A second host {@code CommonErrorHandler}, not {@code @Primary}. */
    @Configuration(proxyBeanMethods = false)
    static class PlainSecondErrorHandler {

        @Bean
        CommonErrorHandler secondErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(0L, 2L));
        }
    }

    /** What a Testcontainers {@code @ServiceConnection} would contribute. */
    @Configuration(proxyBeanMethods = false)
    static class ConnectionDetailsConfiguration {

        @Bean
        KafkaConnectionDetails connectionDetails() {
            return () -> List.of(DETAILS_BOOTSTRAP);
        }
    }
}
