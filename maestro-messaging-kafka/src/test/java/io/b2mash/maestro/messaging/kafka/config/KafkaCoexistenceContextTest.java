package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDto;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDtoSender;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostSenderConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.CONSUMER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.PRODUCER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.assertBootEvaluatedFirst;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.bootFirstRunner;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.producerConfig;
import static org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.getField;

/**
 * Pins WP7 / 3.4: Maestro's three engine Kafka beans are not default
 * candidates, so Spring Boot's own Kafka beans register beside them, a host's
 * unqualified injection points get Boot's, and the engine still holds exactly
 * Maestro's instances.
 *
 * <p>Every context is built from the REAL {@code KafkaMessagingAutoConfiguration}
 * and Boot's {@code KafkaAutoConfiguration} ({@link CoexistenceFixtures#coexistenceRunner()}),
 * because auto-configuration ordering and {@code @ConditionalOnMissingBean}
 * back-off are what is under test; a {@code .withBean(...)} stand-in would
 * always register first and hide both. Host beans are declared in user
 * {@code @Configuration} classes, as a host declares them.
 */
@DisplayName("Spring Boot's Kafka beans coexist with Maestro's")
class KafkaCoexistenceContextTest {

    @Test
    void withNoHostKafkaConfig_bootsBeansExistAndTheEngineKeepsMaestros() {
        coexistenceRunner().run(KafkaCoexistenceContextTest::assertBootAndMaestroCoexist);
    }

    @Test
    void aHostTemplateForItsOwnType_startsWithBootsSerializers() {
        coexistenceRunner().withUserConfiguration(HostSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + "org.springframework.kafka.support.serializer.JacksonJsonSerializer")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(HostDtoSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                    assertThat(producerConfig(ctx, "kafkaProducerFactory"))
                            .containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
                    assertThat(producerConfig(ctx, PRODUCER_FACTORY))
                            .as("the engine's factory still forces its wire format")
                            .containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
                });
    }

    @Test
    void aHostsOwnKafkaBeans_replaceBootsAndLeaveTheEngineAlone() {
        coexistenceRunner().withUserConfiguration(HostOwnBeansConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .doesNotHaveBean("kafkaTemplate")
                    .doesNotHaveBean("kafkaProducerFactory")
                    .doesNotHaveBean("kafkaConsumerFactory");
            assertThat(ctx.getBean("hostTemplate", KafkaTemplate.class).getProducerFactory())
                    .isSameAs(ctx.getBean("hostProducerFactory"));
            assertEngineHoldsMaestrosBeans(ctx);
        });
    }

    @Test
    void aHostTemplateOverBootsProducerFactory_getsBootsFactory() {
        coexistenceRunner().withUserConfiguration(HostTemplateOnlyConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean("hostTemplate", KafkaTemplate.class).getProducerFactory())
                    .isSameAs(ctx.getBean("kafkaProducerFactory"));
            assertThat(ctx)
                    .as("Boot's template backs off for the host's, as without Maestro")
                    .doesNotHaveBean("kafkaTemplate");
        });
    }

    @Test
    void aHostWithOnlyItsOwnProducerFactory_failsStartupInBootsKafkaTemplate() {
        // Boot's kafkaProducerFactory backs off for the host's factory, while Boot's
        // kafkaTemplate (Maestro's template is not a default candidate) still
        // registers and cannot use a ProducerFactory<String, HostDto>.
        coexistenceRunner().withUserConfiguration(HostProducerFactoryOnlyConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx.getStartupFailure()))
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("No qualifying bean of type "
                            + "'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'");
            assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("Error creating bean with name 'kafkaTemplate' defined in "
                            + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration");
        });
    }

    @Test
    void aHostWithOnlyItsOwnProducerFactory_startsWithEitherWayOut() {
        // (a) its own KafkaTemplate over its factory: Boot's kafkaTemplate backs off.
        coexistenceRunner()
                .withUserConfiguration(HostProducerFactoryOnlyConfiguration.class, HostTemplateOnlyConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed()
                            .doesNotHaveBean("kafkaTemplate")
                            .doesNotHaveBean("kafkaProducerFactory");
                    assertThat(ctx.getBean("hostTemplate", KafkaTemplate.class).getProducerFactory())
                            .isSameAs(ctx.getBean("hostProducerFactory"));
                    assertEngineHoldsMaestrosBeans(ctx);
                });
        // (b) its factory declared defaultCandidate = false: Boot's kafkaProducerFactory
        // and kafkaTemplate register beside it.
        coexistenceRunner().withUserConfiguration(NonDefaultCandidateHostProducerFactoryConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed()
                            .hasBean("hostProducerFactory")
                            .hasBean("kafkaProducerFactory")
                            .hasBean("kafkaTemplate");
                    assertThat(ctx.getBean("kafkaTemplate", KafkaTemplate.class).getProducerFactory())
                            .isSameAs(ctx.getBean("kafkaProducerFactory"));
                    assertEngineHoldsMaestrosBeans(ctx);
                });
    }

    @Test
    void aNonDefaultCandidateHostProducerFactory_isInjectedByName() {
        coexistenceRunner().withUserConfiguration(NonDefaultCandidateHostProducerFactoryConfiguration.class,
                        HostFactoryUserConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(HostFactoryUser.class).factory())
                            .isSameAs(ctx.getBean("hostProducerFactory"));
                    assertThat(ctx.getBean("kafkaTemplate", KafkaTemplate.class).getProducerFactory())
                            .isSameAs(ctx.getBean("kafkaProducerFactory"));
                });
    }

    @Test
    void aHostProducerFactoryWithWildcardGenerics_isUsedByBootsTemplate_andStarts() {
        // Only a factory whose declared generics ProducerFactory<Object, Object> cannot match
        // fails Boot's kafkaTemplate; a ProducerFactory<?, ?> is picked up by it.
        coexistenceRunner().withUserConfiguration(WildcardHostProducerFactoryConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaProducerFactory");
            assertThat(ctx.getBean("kafkaTemplate", KafkaTemplate.class).getProducerFactory())
                    .isSameAs(ctx.getBean("hostProducerFactory"));
            assertEngineHoldsMaestrosBeans(ctx);
        });
    }

    @Test
    void aHostReplacingMaestrosTemplateByName_isWhatTheEngineUses() {
        // (a) the documented way: same name, not a default candidate. Boot's template stays.
        coexistenceRunner().withUserConfiguration(NonDefaultCandidateTemplateReplacement.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasBean("kafkaTemplate");
            var engineTemplate = getField(ctx.getBean(KafkaWorkflowMessaging.class), "kafkaTemplate");
            assertThat(engineTemplate).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(((KafkaTemplate<?, ?>) engineTemplate).getDefaultTopic()).isEqualTo("host-replacement");
        });
        // (b) a plain @Bean of that name: still what the engine uses, and Boot's template backs off (as today).
        coexistenceRunner().withUserConfiguration(PlainTemplateReplacement.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaTemplate");
            var engineTemplate = getField(ctx.getBean(KafkaWorkflowMessaging.class), "kafkaTemplate");
            assertThat(engineTemplate).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(((KafkaTemplate<?, ?>) engineTemplate).getDefaultTopic()).isEqualTo("host-replacement");
        });
    }

    @Test
    void aHostReplacingMaestrosConsumerFactoryByName_isWhatTheEngineUses() {
        coexistenceRunner().withUserConfiguration(PlainConsumerFactoryReplacement.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaConsumerFactory");
            assertThat(getField(ctx.getBean(KafkaWorkflowMessaging.class), "consumerFactory"))
                    .isSameAs(ctx.getBean(CONSUMER_FACTORY))
                    .isSameAs(PlainConsumerFactoryReplacement.INSTANCE);
        });
    }

    @Test
    void aTransactionIdPrefix_givesBootsTransactionManagerOverBootsFactory() {
        // Maestro's configuration first (AutoConfigurations, alphabetical) ...
        coexistenceRunner().withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-")
                .run(KafkaCoexistenceContextTest::assertBootsTransactionManagerAndANonTransactionalEngine);
        // ... and Boot's first (UserConfigurations keep their order).
        bootFirstRunner().withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertBootEvaluatedFirst(ctx);
            assertBootsTransactionManagerAndANonTransactionalEngine(ctx);
        });
    }

    @Test
    void bootsBeansCoexistWhicheverAutoConfigurationRunsFirst() {
        // UserConfigurations keep their declared order, so Boot's conditions are
        // evaluated first here; AutoConfigurations (every other test) sorts Maestro
        // first, alphabetically.
        bootFirstRunner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertBootEvaluatedFirst(ctx);
            assertBootAndMaestroCoexist(ctx);
        });
    }

    /**
     * One replacement of one engine bean, declared under its name, and what is
     * observed: whether the context starts, and which of Boot's three Kafka
     * beans then exist.
     */
    record Replacement(String bean, String form, Class<?> configuration, boolean starts, Set<String> bootBeans) {
        @Override
        public String toString() {
            return bean + " as " + form;
        }
    }

    static Stream<Replacement> replacements() {
        var all = Set.of("kafkaTemplate", "kafkaProducerFactory", "kafkaConsumerFactory");
        return Stream.of(
                // A plain producer-factory replacement makes Boot's kafkaProducerFactory back
                // off, while Boot's kafkaTemplate (Maestro's template is not a default
                // candidate) still registers and finds no ProducerFactory<Object, Object>.
                new Replacement(PRODUCER_FACTORY, "plain @Bean", PlainProducerFactoryReplacement.class,
                        false, Set.of()),
                new Replacement(PRODUCER_FACTORY, "@Bean(defaultCandidate = false)",
                        NonDefaultCandidateProducerFactoryReplacement.class, true, all),
                new Replacement(TEMPLATE, "plain @Bean", PlainTemplateReplacement.class,
                        true, Set.of("kafkaProducerFactory", "kafkaConsumerFactory")),
                new Replacement(TEMPLATE, "@Bean(defaultCandidate = false)",
                        NonDefaultCandidateTemplateReplacement.class, true, all),
                new Replacement(CONSUMER_FACTORY, "plain @Bean", PlainConsumerFactoryReplacement.class,
                        true, Set.of("kafkaTemplate", "kafkaProducerFactory")),
                new Replacement(CONSUMER_FACTORY, "@Bean(defaultCandidate = false)",
                        NonDefaultCandidateConsumerFactoryReplacement.class, true, all));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replacements")
    void replacingAnEngineBeanByName_matrix(Replacement r) {
        coexistenceRunner().withUserConfiguration(r.configuration()).run(ctx -> {
            if (!r.starts()) {
                assertThat(ctx).hasFailed();
                assertThat(rootCause(ctx.getStartupFailure()))
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("No qualifying bean of type "
                                + "'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'");
                // Surfaces through KafkaAnnotationDrivenConfiguration's constructor, which
                // takes Boot's kafkaTemplate.
                assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("Error creating bean with name 'kafkaTemplate' defined in "
                                + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration");
                return;
            }
            assertThat(ctx).hasNotFailed();
            var replacement = ctx.getBean(r.bean());
            assertThat(replacement).as("the host's bean, not Maestro's").isSameAs(hostInstance(r.configuration()));
            var engine = ctx.getBean(KafkaWorkflowMessaging.class);
            var engineTemplate = (KafkaTemplate<?, ?>) getField(engine, "kafkaTemplate");
            assertThat(engineTemplate).isSameAs(ctx.getBean(TEMPLATE));
            if (!r.bean().equals(TEMPLATE)) {
                // A replacement template brings its own factory; otherwise Maestro's
                // template is over the (possibly replaced) engine producer factory.
                assertThat(engineTemplate.getProducerFactory()).isSameAs(ctx.getBean(PRODUCER_FACTORY));
            }
            assertThat(getField(engine, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
            for (var boot : List.of("kafkaTemplate", "kafkaProducerFactory", "kafkaConsumerFactory")) {
                assertThat(ctx.containsBean(boot))
                        .as("Boot's %s present", boot)
                        .isEqualTo(r.bootBeans().contains(boot));
            }
        });
    }

    private static Object hostInstance(Class<?> configuration) {
        return getField(configuration, "INSTANCE");
    }

    private static Throwable rootCause(Throwable t) {
        var cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static void assertBootsTransactionManagerAndANonTransactionalEngine(AssertableApplicationContext ctx) {
        assertThat(ctx).hasNotFailed();
        var tm = ctx.getBean("kafkaTransactionManager", KafkaTransactionManager.class);
        assertThat(tm.getProducerFactory()).isSameAs(ctx.getBean("kafkaProducerFactory"));
        assertThat(tm.getProducerFactory().transactionCapable()).isTrue();
        assertThat(ctx.getBean(PRODUCER_FACTORY, ProducerFactory.class).transactionCapable())
                .as("the engine's producer is never transactional")
                .isFalse();
        assertThat(ctx.getBean(TEMPLATE, KafkaTemplate.class).isTransactional()).isFalse();
    }

    private static void assertBootAndMaestroCoexist(AssertableApplicationContext ctx) {
        assertThat(ctx).hasNotFailed()
                .hasBean("kafkaTemplate").hasBean("kafkaProducerFactory").hasBean("kafkaConsumerFactory")
                .hasBean("kafkaListenerContainerFactory")
                .hasBean(TEMPLATE).hasBean(PRODUCER_FACTORY).hasBean(CONSUMER_FACTORY);
        assertEngineHoldsMaestrosBeans(ctx);
        assertThat(producerConfig(ctx, PRODUCER_FACTORY))
                .containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                .containsEntry(ACKS_CONFIG, "all");
        assertThat(producerConfig(ctx, "kafkaProducerFactory"))
                .containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(ctx.getBean(KafkaTemplate.class))
                .as("by type, a host now gets Boot's")
                .isSameAs(ctx.getBean("kafkaTemplate"));
    }

    private static void assertEngineHoldsMaestrosBeans(AssertableApplicationContext ctx) {
        var engine = ctx.getBean(KafkaWorkflowMessaging.class);
        assertThat(getField(engine, "kafkaTemplate")).isSameAs(ctx.getBean(TEMPLATE));
        assertThat(getField(engine, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
        assertThat(ctx.getBean(TEMPLATE, KafkaTemplate.class).getProducerFactory())
                .isSameAs(ctx.getBean(PRODUCER_FACTORY));
    }

    // ── Host configurations ──────────────────────────────────────────────

    private static Map<String, Object> hostProducerProps() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
    }

    private static DefaultKafkaProducerFactory<String, byte[]> byteArrayProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    private static DefaultKafkaConsumerFactory<String, byte[]> byteArrayConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
    }

    private static KafkaTemplate<String, byte[]> markedByteArrayTemplate() {
        var template = new KafkaTemplate<>(byteArrayProducerFactory());
        template.setDefaultTopic("host-replacement");
        return template;
    }

    /** A host that declares all three of its own Kafka beans for its own type. */
    @Configuration(proxyBeanMethods = false)
    static class HostOwnBeansConfiguration {

        @Bean
        ProducerFactory<String, HostDto> hostProducerFactory() {
            return new DefaultKafkaProducerFactory<>(hostProducerProps());
        }

        @Bean
        KafkaTemplate<String, HostDto> hostTemplate(ProducerFactory<String, HostDto> pf) {
            return new KafkaTemplate<>(pf);
        }

        @Bean
        ConsumerFactory<String, HostDto> hostConsumerFactory() {
            return new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class));
        }
    }

    /** A host that declares only a producer factory for its own type, and no template. */
    @Configuration(proxyBeanMethods = false)
    static class HostProducerFactoryOnlyConfiguration {

        @Bean
        ProducerFactory<String, HostDto> hostProducerFactory() {
            return new DefaultKafkaProducerFactory<>(hostProducerProps());
        }
    }

    /** The same factory, not a default candidate: the host injects it by name. */
    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateHostProducerFactoryConfiguration {

        @Bean(defaultCandidate = false)
        ProducerFactory<String, HostDto> hostProducerFactory() {
            return new DefaultKafkaProducerFactory<>(hostProducerProps());
        }
    }

    /** Injects the non-default-candidate host factory by name. */
    record HostFactoryUser(ProducerFactory<String, HostDto> factory) {}

    @Configuration(proxyBeanMethods = false)
    static class HostFactoryUserConfiguration {

        @Bean
        HostFactoryUser hostFactoryUser(
                @Qualifier("hostProducerFactory") ProducerFactory<String, HostDto> hostProducerFactory) {
            return new HostFactoryUser(hostProducerFactory);
        }
    }

    /** A host factory declared with wildcard generics, and no template. */
    @Configuration(proxyBeanMethods = false)
    static class WildcardHostProducerFactoryConfiguration {

        @Bean
        ProducerFactory<?, ?> hostProducerFactory() {
            return new DefaultKafkaProducerFactory<String, HostDto>(hostProducerProps());
        }
    }

    /** A host that declares only a template, over whatever {@code ProducerFactory} the context offers. */
    @Configuration(proxyBeanMethods = false)
    static class HostTemplateOnlyConfiguration {

        @Bean
        KafkaTemplate<String, HostDto> hostTemplate(ProducerFactory<String, HostDto> pf) {
            return new KafkaTemplate<>(pf);
        }
    }

    /** Replaces Maestro's template by name, the documented way: not a default candidate. */
    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateTemplateReplacement {

        static final KafkaTemplate<String, byte[]> INSTANCE = markedByteArrayTemplate();

        @Bean(name = TEMPLATE, defaultCandidate = false)
        KafkaTemplate<String, byte[]> replacement() {
            return INSTANCE;
        }
    }

    /** Replaces Maestro's template by name with a plain {@code @Bean} (a default candidate). */
    @Configuration(proxyBeanMethods = false)
    static class PlainTemplateReplacement {

        static final KafkaTemplate<String, byte[]> INSTANCE = markedByteArrayTemplate();

        @Bean(name = TEMPLATE)
        KafkaTemplate<String, byte[]> replacement() {
            return INSTANCE;
        }
    }

    /** Replaces Maestro's producer factory by name with a plain {@code @Bean} (a default candidate). */
    @Configuration(proxyBeanMethods = false)
    static class PlainProducerFactoryReplacement {

        static final ProducerFactory<String, byte[]> INSTANCE = byteArrayProducerFactory();

        @Bean(name = PRODUCER_FACTORY)
        ProducerFactory<String, byte[]> replacement() {
            return INSTANCE;
        }
    }

    /** Replaces Maestro's producer factory by name, the documented way: not a default candidate. */
    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateProducerFactoryReplacement {

        static final ProducerFactory<String, byte[]> INSTANCE = byteArrayProducerFactory();

        @Bean(name = PRODUCER_FACTORY, defaultCandidate = false)
        ProducerFactory<String, byte[]> replacement() {
            return INSTANCE;
        }
    }

    /** Replaces Maestro's consumer factory by name, the documented way: not a default candidate. */
    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateConsumerFactoryReplacement {

        static final ConsumerFactory<String, byte[]> INSTANCE = byteArrayConsumerFactory();

        @Bean(name = CONSUMER_FACTORY, defaultCandidate = false)
        ConsumerFactory<String, byte[]> replacement() {
            return INSTANCE;
        }
    }

    /** Replaces Maestro's consumer factory by name with a plain {@code @Bean} (a default candidate). */
    @Configuration(proxyBeanMethods = false)
    static class PlainConsumerFactoryReplacement {

        static final ConsumerFactory<String, byte[]> INSTANCE = byteArrayConsumerFactory();

        @Bean(name = CONSUMER_FACTORY)
        ConsumerFactory<String, byte[]> replacement() {
            return INSTANCE;
        }
    }
}
