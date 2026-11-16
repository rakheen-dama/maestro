package io.b2mash.maestro.messaging.kafka.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostSenderConfiguration;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins WP7 Task 4 (D5 guard): a host that injects a {@code KafkaTemplate<…, byte[]>}
 * without a qualifier, and so receives a template that cannot serialize
 * {@code byte[]}, fails at startup naming the bean, the injection point and
 * both fixes — and every context that would work still starts.
 *
 * <p>Every context is built from the REAL {@code KafkaMessagingAutoConfiguration}
 * and Spring Boot's {@code KafkaAutoConfiguration}
 * ({@link CoexistenceFixtures#coexistenceRunner()}). Host beans are declared in
 * user {@code @Configuration} classes, as a host declares them: components are
 * brought in with {@code @Import} (what component scanning registers), the
 * {@code @Bean}-method cases are {@code @Bean} methods.
 */
@DisplayName("ByteArrayTemplateInjectionCheck")
class ByteArrayTemplateInjectionCheckTest {

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        checkLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        checkLogger().detachAppender(logAppender);
    }

    // ── Firing: an unqualified byte[] point that received Boot's StringSerializer template ──

    @Test
    void unqualifiedConstructorInjection_failsStartupNamingTheBeanAndTheFix() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'legacyBytesSender'")
                    .hasMessageContaining("constructor parameter 0 of " + LegacyBytesSender.class.getName())
                    .hasMessageContaining("'kafkaTemplate'")
                    .hasMessageContaining("org.apache.kafka.common.serialization.StringSerializer")
                    .hasMessageContaining("@Qualifier(MaestroKafkaBeans.TEMPLATE)")
                    .hasMessageContaining("spring.kafka.producer.value-serializer="
                            + "org.apache.kafka.common.serialization.ByteArraySerializer");
        });
    }

    @Test
    void theFailureMessageIsExact() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class).run(ctx ->
                assertThat(rootCause(ctx)).hasMessage("Bean 'legacyBytesSender' injects a KafkaTemplate<…, byte[]> "
                        + "without a qualifier (constructor parameter 0 of " + LegacyBytesSender.class.getName()
                        + ") and received 'kafkaTemplate', whose value serializer "
                        + "org.apache.kafka.common.serialization.StringSerializer cannot serialize byte[]: every send "
                        + "through it would fail. Maestro's Kafka beans are not injected by type. Inject Maestro's "
                        + "template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\"maestroKafkaTemplate\"), or set "
                        + "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer "
                        + "to send byte[] through Spring Boot's template. "
                        + "See docs/configuration.md § Kafka Client Configuration."));
    }

    @Test
    void fieldInjection_isCaughtToo() {
        coexistenceRunner().withUserConfiguration(FieldSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'fieldSender'")
                    .hasMessageContaining("field template of " + FieldSender.class.getName())
                    .hasMessageContaining("'kafkaTemplate'");
        });
    }

    @Test
    void setterInjection_isCaughtToo() {
        coexistenceRunner().withUserConfiguration(SetterSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'setterSender'")
                    .hasMessageContaining("parameter 0 of method " + SetterSender.class.getName() + ".setTemplate");
        });
    }

    @Test
    void beanMethodParameter_isCaughtToo() {
        coexistenceRunner().withUserConfiguration(BeanMethodSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'bytesSender'")
                    .hasMessageContaining("parameter 0 of @Bean method "
                            + BeanMethodSenderConfiguration.class.getName() + ".bytesSender");
        });
    }

    @Test
    void aKafkaOperationsTypedPoint_isCaughtToo() {
        coexistenceRunner().withUserConfiguration(OperationsSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'operationsSender'")
                    .hasMessageContaining("constructor parameter 0 of " + OperationsSender.class.getName());
        });
    }

    @Test
    void everyViolationIsListedInOneFailure() {
        coexistenceRunner()
                .withUserConfiguration(LegacyBytesSenderConfiguration.class, FieldSenderConfiguration.class)
                .run(ctx -> assertThat(rootCause(ctx))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Bean 'legacyBytesSender' injects")
                        .hasMessageContaining("Bean 'fieldSender' injects"));
    }

    // ── Not firing: every context that would work ──────────────────────

    @Test
    void qualifiedInjection_startsAndGetsMaestrosTemplate() {
        coexistenceRunner().withUserConfiguration(QualifiedSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(QualifiedSender.class).template()).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(ctx.getBean("qualifiedBeanMethodSender", BytesHolder.class).template())
                    .as("a qualified @Bean-method parameter, in a proxied @Configuration")
                    .isSameAs(ctx.getBean(TEMPLATE));
        });
    }

    @Test
    void aBeanUsingBootsTemplateAndAQualifiedEngineTemplate_starts() {
        // These beans DO depend on Boot's StringSerializer template (for their own
        // String traffic), so the check inspects their points: the qualified
        // byte[] point must not count. The @Bean method sits in a proxied
        // (CGLIB-enhanced) @Configuration.
        coexistenceRunner().withUserConfiguration(MixedSendersConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate"))
                    .contains("mixedSender", "mixedBeanMethodSender");
            var mixed = ctx.getBean("mixedSender", MixedSender.class);
            assertThat(mixed.own()).isSameAs(ctx.getBean("kafkaTemplate"));
            assertThat(mixed.engine()).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(ctx.getBean("mixedBeanMethodSender", MixedSender.class).engine()).isSameAs(ctx.getBean(TEMPLATE));
        });
    }

    @Test
    void bootTemplateConfiguredForByteArray_starts() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + "org.apache.kafka.common.serialization.ByteArraySerializer")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                });
    }

    @Test
    void aHostTemplateWithASerializerInstanceForByteArray_starts() {
        // The factory's config map names StringSerializer; the INSTANCE set on the
        // factory is what Spring Kafka uses, and it serializes byte[].
        coexistenceRunner().withUserConfiguration(HostByteArrayTemplateConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaTemplate");
            assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("hostBytesTemplate"));
        });
    }

    @Test
    void aTemplateForAnotherValueType_isNotChecked() {
        coexistenceRunner().withUserConfiguration(HostSenderConfiguration.class).run(ctx ->
                assertThat(ctx).hasNotFailed());
    }

    @Test
    void withNoHostInjection_theCheckRunsAndStarts() {
        coexistenceRunner().run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(ByteArrayTemplateInjectionCheck.class)
                .hasBean("maestroKafkaTemplateInjectionCheck"));
    }

    @Test
    void bootsOwnRetryTopicConfiguration_isNotFlagged() {
        // Boot's kafkaRetryTopicConfiguration injects KafkaTemplate<?, ?> and so
        // depends on Boot's StringSerializer template; it is not a byte[] point.
        coexistenceRunner().withPropertyValues("spring.kafka.retry.topic.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasBean("kafkaRetryTopicConfiguration");
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate"))
                    .contains("kafkaRetryTopicConfiguration");
        });
    }

    @Test
    void aSerializerTheCheckCannotClassify_startsAndWarns() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + "org.springframework.kafka.support.serializer.JacksonJsonSerializer")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(logAppender.list)
                            .filteredOn(e -> e.getLevel() == Level.WARN)
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .singleElement(InstanceOfAssertFactories.STRING)
                            .contains("'legacyBytesSender'", "'kafkaTemplate'", JacksonJsonSerializer.class.getName(),
                                    "@Qualifier(MaestroKafkaBeans.TEMPLATE)");
                });
    }

    @Test
    void anObjectProviderPoint_isOutOfReach() {
        // Documented limit: an ObjectProvider (or a getBean at runtime) is resolved
        // when the host calls it, not at injection, so the check cannot see it.
        coexistenceRunner().withUserConfiguration(ProviderSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ProviderSender.class).template().getIfUnique()).isSameAs(ctx.getBean("kafkaTemplate"));
        });
    }

    // ── Several host templates: a point counts only against the template it received ──

    @Test
    void aBeanInjectingTwoHostTemplates_startsAndEachPointHoldsItsTemplate() {
        // dualSender depends on stringTemplate (StringSerializer) through `strings`;
        // its unqualified byte[] point `bytes` received bytesTemplate, not stringTemplate.
        coexistenceRunner().withUserConfiguration(DualSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaTemplate");
            var sender = ctx.getBean(DualSender.class);
            assertThat(sender.strings()).isSameAs(ctx.getBean("stringTemplate"));
            assertThat(sender.bytes()).isSameAs(ctx.getBean("bytesTemplate"));
            assertThat(ctx.getBeanFactory().getDependentBeans("stringTemplate"))
                    .as("the check inspects dualSender's points against stringTemplate")
                    .contains("dualSender");
            assertThat(ctx.getBeanFactory().containsSingleton("lazyTemplate"))
                    .as("the check reads bean definitions only: a lazy template stays uncreated")
                    .isFalse();
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    void aBeanInjectingAnUnclassifiableTemplateAndAByteArrayOne_startsWithoutAWarning() {
        // The WARN path follows the same rule: `bytes` did not receive the JSON template.
        coexistenceRunner().withUserConfiguration(DualJsonSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean(DualSender.class);
            assertThat(sender.strings()).isSameAs(ctx.getBean("stringTemplate"));
            assertThat(sender.bytes()).isSameAs(ctx.getBean("bytesTemplate"));
            assertThat(ctx.getBeanFactory().getDependentBeans("stringTemplate")).contains("dualSender");
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    void aPrimaryTemplateBesideAQualifiedOne_startsAndWarnsOnceThatItCannotTell() {
        // `bytes` matches both byte[] templates; Spring picks the @Primary one. The
        // check cannot tell which one Spring picked, so it does not fail: one WARN.
        coexistenceRunner().withUserConfiguration(PrimarySenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean(PrimarySender.class);
            assertThat(sender.bytes()).isSameAs(ctx.getBean("bytesTemplate"));
            assertThat(sender.legacy()).isSameAs(ctx.getBean("legacyTemplate"));
            assertThat(ctx.getBeanFactory().getDependentBeans("legacyTemplate")).contains("primarySender");
            assertThat(warnings())
                    .singleElement(InstanceOfAssertFactories.STRING)
                    .contains("'primarySender'", "constructor parameter 0 of " + PrimarySender.class.getName(),
                            "several Kafka templates", "bytesTemplate", "legacyTemplate",
                            StringSerializer.class.getName());
        });
    }

    @Test
    void aBeanInjectingTwoTemplatesByParameterName_startsAndWarnsOnceThatItCannotTell() {
        // Both templates are declared KafkaTemplate<?, ?>, so both match both points;
        // Spring falls back to the parameter names.
        coexistenceRunner().withUserConfiguration(NamedSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean(NamedSender.class);
            assertThat(sender.stringTemplate()).isSameAs(ctx.getBean("stringTemplate"));
            assertThat(sender.bytesTemplate()).isSameAs(ctx.getBean("bytesTemplate"));
            assertThat(ctx.getBeanFactory().getDependentBeans("stringTemplate")).contains("namedSender");
            assertThat(warnings())
                    .singleElement(InstanceOfAssertFactories.STRING)
                    .contains("'namedSender'", "constructor parameter 1 of " + NamedSender.class.getName(),
                            "several Kafka templates", "bytesTemplate", "stringTemplate");
        });
    }

    @Test
    void theCheckResolvesTheValueSerializerOnce() {
        // DefaultKafkaProducerFactory.getValueSerializer() runs configure(...) on the
        // shared serializer instance on every call.
        var withoutAPoint = new AtomicInteger();
        coexistenceRunner().withUserConfiguration(CountingTemplateConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            withoutAPoint.set(ctx.getBean(CountingByteArraySerializer.class).configureCalls());
        });
        coexistenceRunner().withUserConfiguration(CountingTemplateConfiguration.class,
                LegacyBytesSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("countingTemplate"));
            var serializer = ctx.getBean(CountingByteArraySerializer.class);
            var afterStartup = serializer.configureCalls();
            var factory = ctx.getBean("countingTemplate", KafkaTemplate.class).getProducerFactory();
            factory.getValueSerializer();
            var oneResolution = serializer.configureCalls() - afterStartup;
            assertThat(oneResolution).as("configure calls per getValueSerializer()").isEqualTo(1);
            assertThat(afterStartup - withoutAPoint.get())
                    .as("configure calls the check adds when a point received the template")
                    .isEqualTo(oneResolution);
        });
    }

    // ── Static helpers ───────────────────────────────────────────────────

    @Test
    void valueSerializerTargetResolvesDeclaredTypes() {
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(templateWithValueSerializer(StringSerializer.class)))
                .isEqualTo(String.class);
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(templateWithValueSerializer(ByteArraySerializer.class)))
                .isEqualTo(byte[].class);
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(templateWithValueSerializer(JacksonJsonSerializer.class)))
                .isNull();
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(
                templateWithValueSerializer(StringSerializer.class.getName())))
                .as("a class name in the config map")
                .isEqualTo(String.class);
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(
                templateWithValueSerializer("com.example.NoSuchSerializer")))
                .isNull();
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1")))))
                .as("a factory without value.serializer")
                .isNull();
        var instanceWins = new DefaultKafkaProducerFactory<String, byte[]>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                new StringSerializer(), new ByteArraySerializer());
        assertThat(ByteArrayTemplateInjectionCheck.valueSerializerTarget(new KafkaTemplate<>(instanceWins)))
                .as("a serializer instance on the factory wins over the config map")
                .isEqualTo(byte[].class);
    }

    @Test
    void unqualifiedByteArrayTemplatePoints_listsEachForm() throws Exception {
        assertThat(ByteArrayTemplateInjectionCheck.unqualifiedByteArrayTemplatePoints(LegacyBytesSender.class, null))
                .containsExactly("constructor parameter 0 of " + LegacyBytesSender.class.getName());
        assertThat(ByteArrayTemplateInjectionCheck.unqualifiedByteArrayTemplatePoints(FieldSender.class, null))
                .containsExactly("field template of " + FieldSender.class.getName());
        assertThat(ByteArrayTemplateInjectionCheck.unqualifiedByteArrayTemplatePoints(QualifiedSender.class, null))
                .isEmpty();
        var beanMethod = BeanMethodSenderConfiguration.class.getDeclaredMethod("bytesSender", KafkaTemplate.class);
        assertThat(ByteArrayTemplateInjectionCheck.unqualifiedByteArrayTemplatePoints(BytesHolder.class, beanMethod))
                .as("a @Bean-produced object: the method's parameters, never the produced class's constructors")
                .containsExactly("parameter 0 of @Bean method "
                        + BeanMethodSenderConfiguration.class.getName() + ".bytesSender");
        assertThat(ByteArrayTemplateInjectionCheck.unqualifiedByteArrayTemplatePoints(TwoConstructors.class, null))
                .as("several constructors, none @Autowired: Spring uses none of them for injection here")
                .isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static KafkaTemplate<String, byte[]> templateWithValueSerializer(Object serializer) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializer)));
    }

    private static <V> KafkaTemplate<String, V> hostTemplate(Class<?> valueSerializer) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, V>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer)));
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static Throwable rootCause(AssertableApplicationContext ctx) {
        Throwable cause = ctx.getStartupFailure();
        assertThat(cause).as("the context failed to start").isNotNull();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Logger checkLogger() {
        return (Logger) LoggerFactory.getLogger(ByteArrayTemplateInjectionCheck.class);
    }

    // ── Host beans ───────────────────────────────────────────────────────

    /** The old docs' advice: an unqualified byte[] template, by constructor. */
    @Component("legacyBytesSender")
    static class LegacyBytesSender {
        private final KafkaTemplate<String, byte[]> template;

        LegacyBytesSender(KafkaTemplate<String, byte[]> maestroKafkaTemplate) {
            this.template = maestroKafkaTemplate;
        }

        KafkaTemplate<String, byte[]> template() {
            return template;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LegacyBytesSender.class)
    static class LegacyBytesSenderConfiguration {
    }

    @Component("fieldSender")
    static class FieldSender {
        @Autowired
        KafkaTemplate<String, byte[]> template;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(FieldSender.class)
    static class FieldSenderConfiguration {
    }

    @Component("setterSender")
    static class SetterSender {
        KafkaTemplate<String, byte[]> template;

        @Autowired
        void setTemplate(KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SetterSender.class)
    static class SetterSenderConfiguration {
    }

    /** What a {@code @Bean} method produces: its own constructor is never used for injection. */
    record BytesHolder(KafkaTemplate<String, byte[]> template) {}

    @Configuration(proxyBeanMethods = false)
    static class BeanMethodSenderConfiguration {
        @Bean
        BytesHolder bytesSender(KafkaTemplate<String, byte[]> template) {
            return new BytesHolder(template);
        }
    }

    @Component("operationsSender")
    record OperationsSender(KafkaOperations<String, byte[]> template) {}

    @Configuration(proxyBeanMethods = false)
    @Import(OperationsSender.class)
    static class OperationsSenderConfiguration {
    }

    @Component("qualifiedSender")
    record QualifiedSender(KafkaTemplate<String, byte[]> template) {
        QualifiedSender(@Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }
    }

    /** A full (proxied) {@code @Configuration} with a qualified {@code @Bean}-method parameter. */
    @Configuration
    @Import(QualifiedSender.class)
    static class QualifiedSenderConfiguration {
        @Bean
        BytesHolder qualifiedBeanMethodSender(@Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> template) {
            return new BytesHolder(template);
        }
    }

    /** A host bean with its own String traffic through Boot's template and the engine's template by qualifier. */
    @Component("mixedSender")
    record MixedSender(KafkaTemplate<String, String> own, KafkaTemplate<String, byte[]> engine) {
        MixedSender(KafkaTemplate<String, String> own, @Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> engine) {
            this.own = own;
            this.engine = engine;
        }
    }

    @Configuration
    @Import(MixedSender.class)
    static class MixedSendersConfiguration {
        @Bean
        MixedSender mixedBeanMethodSender(KafkaTemplate<String, String> own,
                @Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> engine) {
            return new MixedSender(own, engine);
        }
    }

    /** A host template for byte[] whose serializer is an instance on the factory, not a config entry. */
    @Configuration(proxyBeanMethods = false)
    @Import(LegacyBytesSender.class)
    static class HostByteArrayTemplateConfiguration {
        @Bean
        KafkaTemplate<String, byte[]> hostBytesTemplate() {
            ProducerFactory<String, byte[]> pf = new DefaultKafkaProducerFactory<>(
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                    new StringSerializer(), new ByteArraySerializer());
            return new KafkaTemplate<>(pf);
        }
    }

    @Component("providerSender")
    record ProviderSender(ObjectProvider<KafkaTemplate<String, byte[]>> template) {}

    @Configuration(proxyBeanMethods = false)
    @Import(ProviderSender.class)
    static class ProviderSenderConfiguration {
    }

    static class TwoConstructors {
        TwoConstructors() {
        }

        TwoConstructors(KafkaTemplate<String, byte[]> template) {
        }
    }

    /** A {@code ByteArraySerializer} that counts its {@code configure} calls. */
    static class CountingByteArraySerializer extends ByteArraySerializer {
        private final AtomicInteger configureCalls = new AtomicInteger();

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            configureCalls.incrementAndGet();
            super.configure(configs, isKey);
        }

        int configureCalls() {
            return configureCalls.get();
        }
    }

    /** A host byte[] template whose factory holds a counting serializer instance. */
    @Configuration(proxyBeanMethods = false)
    static class CountingTemplateConfiguration {
        @Bean
        CountingByteArraySerializer countingSerializer() {
            return new CountingByteArraySerializer();
        }

        @Bean
        KafkaTemplate<String, byte[]> countingTemplate(CountingByteArraySerializer serializer) {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"),
                    new StringSerializer(), serializer));
        }
    }

    /** A host bean with its own String template and its own byte[] template, both unqualified. */
    @Component("dualSender")
    record DualSender(KafkaTemplate<String, String> strings, KafkaTemplate<String, byte[]> bytes) {}

    @Configuration(proxyBeanMethods = false)
    @Import(DualSender.class)
    static class DualSenderConfiguration {
        @Bean
        KafkaTemplate<String, String> stringTemplate() {
            return hostTemplate(StringSerializer.class);
        }

        @Bean
        KafkaTemplate<String, byte[]> bytesTemplate() {
            return hostTemplate(ByteArraySerializer.class);
        }

        /** Never injected anywhere: must stay uncreated. */
        @Bean
        @Lazy
        KafkaTemplate<String, Long> lazyTemplate() {
            return hostTemplate(LongSerializer.class);
        }
    }

    /** {@link DualSender} with a JSON (unclassifiable) String template. */
    @Configuration(proxyBeanMethods = false)
    @Import(DualSender.class)
    static class DualJsonSenderConfiguration {
        @Bean
        KafkaTemplate<String, String> stringTemplate() {
            return hostTemplate(JacksonJsonSerializer.class);
        }

        @Bean
        KafkaTemplate<String, byte[]> bytesTemplate() {
            return hostTemplate(ByteArraySerializer.class);
        }
    }

    /** An unqualified byte[] point that two templates match, resolved by {@code @Primary}. */
    @Component("primarySender")
    record PrimarySender(KafkaTemplate<String, byte[]> bytes, KafkaTemplate<String, byte[]> legacy) {
        PrimarySender(KafkaTemplate<String, byte[]> bytes,
                @Qualifier("legacyTemplate") KafkaTemplate<String, byte[]> legacy) {
            this.bytes = bytes;
            this.legacy = legacy;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PrimarySender.class)
    static class PrimarySenderConfiguration {
        @Bean
        @Primary
        KafkaTemplate<String, byte[]> bytesTemplate() {
            return hostTemplate(ByteArraySerializer.class);
        }

        /** Declared for byte[] but configured with StringSerializer; injected only by qualifier. */
        @Bean
        KafkaTemplate<String, byte[]> legacyTemplate() {
            return hostTemplate(StringSerializer.class);
        }
    }

    /** Two points that both templates match, resolved by parameter name. */
    @Component("namedSender")
    record NamedSender(KafkaTemplate<String, String> stringTemplate, KafkaTemplate<String, byte[]> bytesTemplate) {}

    @Configuration(proxyBeanMethods = false)
    @Import(NamedSender.class)
    static class NamedSenderConfiguration {
        @Bean
        KafkaTemplate<?, ?> stringTemplate() {
            return hostTemplate(StringSerializer.class);
        }

        @Bean
        KafkaTemplate<?, ?> bytesTemplate() {
            return hostTemplate(ByteArraySerializer.class);
        }
    }
}
