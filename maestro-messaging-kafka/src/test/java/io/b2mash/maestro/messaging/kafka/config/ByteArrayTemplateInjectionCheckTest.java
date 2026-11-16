package io.b2mash.maestro.messaging.kafka.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostSenderConfiguration;
import io.b2mash.maestro.messaging.kafka.listener.MaestroSignalListenerBeanPostProcessor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
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

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    /** The whole failure for {@link LegacyBytesSender} over Boot's default template. */
    static final String LEGACY_VIOLATION = "Bean 'legacyBytesSender' injects a KafkaTemplate<…, byte[]> "
            + "without a qualifier (constructor parameter 0 of " + LegacyBytesSender.class.getName()
            + ") and received 'kafkaTemplate', whose value serializer "
            + "org.apache.kafka.common.serialization.StringSerializer cannot serialize byte[]: every send "
            + "through it would fail. Maestro's Kafka beans are not injected by type. Inject Maestro's "
            + "template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\"maestroKafkaTemplate\"), or set "
            + "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer "
            + "to send byte[] through Spring Boot's template. Only if the injection point is in code you cannot "
            + "change, set maestro.messaging.template-injection-check=warn (or off) to start anyway. "
            + "See docs/configuration.md § Kafka Client Configuration.";

    /** The last-resort sentence of a failure; a WARN under {@code warn} replaces it. */
    static final String LAST_RESORT = " Only if the injection point is in code you cannot change, set "
            + "maestro.messaging.template-injection-check=warn (or off) to start anyway.";

    /** {@link #LEGACY_VIOLATION} as {@code warn} logs it. */
    static final String LEGACY_WARNING = LEGACY_VIOLATION.replace(LAST_RESORT, "")
            + " (Logged, not fatal, because maestro.messaging.template-injection-check=warn.)";

    /** The whole failure for {@link LegacyBytesSender} over Boot's template configured for JSON. */
    static final String JSON_VIOLATION = "Bean 'legacyBytesSender' injects a KafkaTemplate<…, byte[]> "
            + "without a qualifier (constructor parameter 0 of " + LegacyBytesSender.class.getName()
            + ") and received 'kafkaTemplate', whose value serializer "
            + "org.springframework.kafka.support.serializer.JacksonJsonSerializer writes byte[] as a base64-encoded "
            + "JSON string, not the bytes themselves: every record sent through it would carry a different payload. "
            + "Maestro's Kafka beans are not injected by type. Inject Maestro's "
            + "template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\"maestroKafkaTemplate\"), or set "
            + "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer "
            + "to send byte[] through Spring Boot's template. If 'kafkaTemplate' is meant to send JSON, qualify "
            + "the point with its bean name, @Qualifier(\"kafkaTemplate\"), which takes the point out of this "
            + "check." + LAST_RESORT + " See docs/configuration.md § Kafka Client Configuration.";

    /**
     * The whole failure for a point that received Boot's default {@code StringSerializer} template.
     *
     * @param bean  the bean
     * @param point the injection point's description
     */
    static String stringSerializerViolation(String bean, String point) {
        return LEGACY_VIOLATION
                .replace("Bean 'legacyBytesSender'", "Bean '" + bean + "'")
                .replace("(constructor parameter 0 of " + LegacyBytesSender.class.getName() + ")", "(" + point + ")");
    }

    @Test
    void theFailureMessageIsExact() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class).run(ctx ->
                assertThat(rootCause(ctx)).hasMessage(LEGACY_VIOLATION));
    }

    // ── Fix wave (B2): maestro.messaging.template-injection-check = fail | warn | off ──

    @Test
    void failMode_set_explicitly_failsStartup() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("maestro.messaging.template-injection-check=fail")
                .run(ctx -> assertThat(rootCause(ctx)).hasMessage(LEGACY_VIOLATION));
    }

    @Test
    void warnMode_startsAndLogsEveryViolationAsAWarning() {
        coexistenceRunner()
                .withUserConfiguration(LegacyBytesSenderConfiguration.class, FieldSenderConfiguration.class)
                .withPropertyValues("maestro.messaging.template-injection-check=warn")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                    assertThat(warnings()).hasSize(2);
                    assertThat(warnings()).contains(LEGACY_WARNING);
                    assertThat(warnings()).allSatisfy(w -> assertThat(w)
                            .doesNotContain("to start anyway")
                            .endsWith(" (Logged, not fatal, because maestro.messaging.template-injection-check=warn.)"));
                    assertThat(warnings()).anySatisfy(w -> assertThat(w)
                            .startsWith("Bean 'fieldSender' injects a KafkaTemplate<…, byte[]> without a qualifier "
                                    + "(field template of " + FieldSender.class.getName() + ")"));
                });
    }

    @Test
    void offMode_startsAndTheCheckLogsNothing() {
        // The check's logger at DEBUG, so "no line at any level" covers its DEBUG lines too.
        var previousLevel = checkLogger().getLevel();
        checkLogger().setLevel(Level.DEBUG);
        try {
            offModeContexts();
        }
        finally {
            checkLogger().setLevel(previousLevel);
        }
    }

    private void offModeContexts() {
        // A violation (fails under fail) and an ambiguous point (WARNs under fail and warn).
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("maestro.messaging.template-injection-check=off")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ByteArrayTemplateInjectionCheck.class);
                    assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                });
        coexistenceRunner().withUserConfiguration(PrimarySenderConfiguration.class)
                .withPropertyValues("maestro.messaging.template-injection-check=off")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(PrimarySender.class).legacy()).isSameAs(ctx.getBean("legacyTemplate"));
                });
        assertThat(logAppender.list).as("no line at any level").isEmpty();
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
                .withPropertyValues("spring.kafka.producer.value-serializer=" + OpaqueSerializer.class.getName())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(logAppender.list)
                            .filteredOn(e -> e.getLevel() == Level.WARN)
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .singleElement(InstanceOfAssertFactories.STRING)
                            .contains("'legacyBytesSender'", "'kafkaTemplate'", OpaqueSerializer.class.getName(),
                                    "@Qualifier(MaestroKafkaBeans.TEMPLATE)");
                });
    }

    // ── Fix wave (B1): Spring Kafka's JSON serializers write byte[] as a base64 JSON string ──

    @Test
    void aJsonValueSerializer_failsStartupBecauseItWritesBase64() {
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + "org.springframework.kafka.support.serializer.JacksonJsonSerializer")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Bean 'legacyBytesSender' injects a KafkaTemplate<…, byte[]> without "
                                    + "a qualifier (constructor parameter 0 of " + LegacyBytesSender.class.getName()
                                    + ") and received 'kafkaTemplate', whose value serializer "
                                    + JacksonJsonSerializer.class.getName()
                                    + " writes byte[] as a base64-encoded JSON string")
                            .hasMessageContaining("@Qualifier(MaestroKafkaBeans.TEMPLATE)")
                            .hasMessage(JSON_VIOLATION);
                });
    }

    @Test
    void aPointQualifiedWithTheJsonTemplatesOwnName_startsAndHoldsIt() {
        // The JSON verdict's way out for a host that sends JSON on purpose.
        coexistenceRunner().withUserConfiguration(UnseenShapesConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + "org.springframework.kafka.support.serializer.JacksonJsonSerializer")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(QualifiedByNameSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                    assertThat(warnings()).isEmpty();
                });
    }

    @Test
    void aSubclassOfAJsonSerializer_failsStartupEvenWhenItDeclaresByteArray() {
        // Its declared target is byte[], so the type rule alone would pass it.
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer=" + ByteArrayJsonSerializer.class.getName())
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("whose value serializer " + ByteArrayJsonSerializer.class.getName()
                                    + " writes byte[] as a base64-encoded JSON string");
                });
    }

    @Test
    void aJsonSerializerWritesByteArrayAsABase64String() {
        // The fact the B1 failure message states.
        try (var json = new JacksonJsonSerializer<byte[]>()) {
            assertThat(new String(json.serialize("t", "hello".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8)).isEqualTo("\"aGVsbG8=\"");
        }
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

    @Test
    void injectionShapesTheCheckDoesNotSee_startWithBootsTemplate() {
        // Each of these points receives Boot's StringSerializer template, and the
        // check does not see it: a qualified point, raw and wildcard generics,
        // Optional, a collection and a @Lazy point (a proxy; no dependency is
        // recorded at startup). Documented limits, not endorsements.
        coexistenceRunner().withUserConfiguration(UnseenShapesConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var boot = ctx.getBean("kafkaTemplate");
            assertThat(ctx.getBean(QualifiedByNameSender.class).template()).isSameAs(boot);
            assertThat(ctx.getBean(RawSender.class).template()).isSameAs(boot);
            assertThat(ctx.getBean(WildcardSender.class).template()).isSameAs(boot);
            assertThat(ctx.getBean(OptionalSender.class).template().orElseThrow()).isSameAs(boot);
            assertThat(ctx.getBean(ListSender.class).templates()).singleElement().isSameAs(boot);
            var lazy = ctx.getBean(LazySender.class);
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate"))
                    .contains("qualifiedByNameSender", "rawSender", "wildcardSender", "optionalSender", "listSender")
                    .doesNotContain("lazySender");
            assertThat(lazy.template().getProducerFactory()).isSameAs(((KafkaTemplate<?, ?>) boot).getProducerFactory());
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

    @Test
    void aSerializerNamedOnlyInTheConfigurationMap_isClassifiedWithoutBeingInstantiated() {
        // Fix wave (B5): with no serializer instance or supplier on the factory (Boot's
        // kafkaProducerFactory from spring.kafka.producer.value-serializer), the class is
        // read from the configuration map and no host serializer code runs.
        InstanceCountingSerializer.INSTANCES.set(0);
        coexistenceRunner().withUserConfiguration(LegacyBytesSenderConfiguration.class)
                .withPropertyValues("spring.kafka.producer.value-serializer="
                        + InstanceCountingSerializer.class.getName())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(LegacyBytesSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
                    assertThat(warnings()).as("classified as byte[]-capable from the class").isEmpty();
                    assertThat(InstanceCountingSerializer.INSTANCES).hasValue(0);
                });
    }

    // ── Fix round 2 (N1): constructors Spring did not autowire are not points ──

    @Test
    void aBeanBuiltByAnInstanceSupplier_startsAndHoldsTheTemplatesItLookedUp() {
        // A BeanRegistrar supplier calls getBean itself: Spring records supplierSender as a
        // dependent of kafkaTemplate, but never resolves the record's constructor parameters.
        coexistenceRunner().withUserConfiguration(SupplierSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean("supplierSender", TwoTemplateSender.class);
            assertThat(sender.text()).isSameAs(ctx.getBean("kafkaTemplate"));
            assertThat(sender.engine()).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate"))
                    .as("the check sees supplierSender among kafkaTemplate's dependents")
                    .contains("supplierSender");
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    void aBeanWithExplicitConstructorArguments_startsAndHoldsTheReferencedTemplates() {
        coexistenceRunner().withUserConfiguration(ConstructorArgsSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean("constructorArgsSender", TwoTemplateSender.class);
            assertThat(sender.text()).isSameAs(ctx.getBean("kafkaTemplate"));
            assertThat(sender.engine()).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate")).contains("constructorArgsSender");
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    void aFactoryBeanProduct_isNotScanned_theFactoryBeanIs() {
        // getType("senderFactory") is the product's class, whose constructor Spring never calls.
        coexistenceRunner().withUserConfiguration(FactoryBeanSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var sender = ctx.getBean("senderFactory", TwoTemplateSender.class);
            assertThat(sender.text()).isSameAs(ctx.getBean("kafkaTemplate"));
            assertThat(sender.engine()).isSameAs(ctx.getBean(TEMPLATE));
            assertThat(ctx.getBeanFactory().getDependentBeans("kafkaTemplate")).contains("senderFactory");
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    void aFactoryBeanWhoseOwnFieldMisinjects_stillFails() {
        coexistenceRunner().withUserConfiguration(BadFactoryBeanConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'badSenderFactory'")
                    .hasMessageContaining("field template of " + BadSenderFactoryBean.class.getName())
                    .hasMessageContaining("'kafkaTemplate'");
        });
    }

    // ── Fix round 2 (N4): a non-generic subclass point matches only its own type ──

    @Test
    void aNonGenericSubclassPoint_isCheckedAgainstItsOwnTypeOnly() {
        coexistenceRunner().withUserConfiguration(SubclassSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SubclassSender.class).template()).isSameAs(ctx.getBean("myBytesTemplate"));
            assertThat(warnings())
                    .singleElement(InstanceOfAssertFactories.STRING)
                    .contains("'subclassSender'", "received 'myBytesTemplate'",
                            OpaqueSerializer.class.getName(), "cannot classify")
                    .doesNotContain("several Kafka templates", "stringTemplate");
        });
    }

    @Test
    void aNonGenericSubclassPointThatCannotSendByteArray_failsStartup() {
        coexistenceRunner().withUserConfiguration(StringSubclassSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'subclassSender'")
                    .hasMessageContaining("received 'myBytesTemplate'")
                    .hasMessageContaining(StringSerializer.class.getName());
        });
    }

    // ── Fix wave (B4): the check runs before any Maestro consumer starts ──

    @Test
    void theCheckFailsStartupBeforeAnyMaestroSignalListenerConsumerStarts() {
        var listenerLog = new ListAppender<ILoggingEvent>();
        listenerLog.start();
        var listenerLogger = (Logger) LoggerFactory.getLogger(MaestroSignalListenerBeanPostProcessor.class);
        listenerLogger.addAppender(listenerLog);
        try {
            var runner = coexistenceRunner()
                    .withUserConfiguration(SignalListenerConfiguration.class)
                    .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class));
            // Positive control: without a misinjection, the post-processor starts the consumer.
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                var names = List.of(ctx.getBeanDefinitionNames());
                assertThat(names.indexOf("maestroKafkaTemplateInjectionCheck"))
                        .as("the check is registered, so it runs, before the post-processor")
                        .isNotNegative()
                        .isLessThan(names.indexOf("maestroSignalListenerBeanPostProcessor"));
                assertThat(listenerLog.list).extracting(ILoggingEvent::getFormattedMessage)
                        .anyMatch(m -> m.startsWith("Started Kafka consumer for @MaestroSignalListener"));
            });
            listenerLog.list.clear();
            runner.withUserConfiguration(LegacyBytesSenderConfiguration.class).run(ctx -> {
                assertThat(rootCause(ctx)).hasMessage(LEGACY_VIOLATION);
                assertThat(listenerLog.list).extracting(ILoggingEvent::getFormattedMessage)
                        .as("the post-processor's afterSingletonsInstantiated never ran: only its close, with nothing to stop")
                        .containsExactly("Stopping 0 @MaestroSignalListener container(s)");
            });
        }
        finally {
            listenerLogger.detachAppender(listenerLog);
        }
    }

    // ── Fix wave (B6): a JDK-proxied bean is scanned through its target class ──

    @Test
    void aJdkProxiedBeanThatMisinjects_failsStartupNamingItsTargetClass() {
        coexistenceRunner().withUserConfiguration(JdkProxiedSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootCause(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Bean 'jdkProxiedSender' injects")
                    .hasMessageContaining("constructor parameter 0 of " + JdkProxiedSender.class.getName())
                    .hasMessageContaining("received 'kafkaTemplate'");
        });
    }

    @Test
    void aJdkProxiedInterfaceTypedBeanMethod_isCheckedThroughItsParameters() {
        // Residual 1: the definition resolves to the interface, so no class can be scanned;
        // the @Bean method's parameters need none.
        coexistenceRunner().withUserConfiguration(JdkProxiedBeanMethodConfiguration.class).run(ctx ->
                assertThat(rootCause(ctx)).hasMessage(stringSerializerViolation("jdkProxiedSender",
                        "parameter 0 of @Bean method " + JdkProxiedBeanMethodConfiguration.class.getName()
                                + ".jdkProxiedSender")));
    }

    @Test
    void aJdkProxiedBeanThatQualifies_startsAndIsAProxy() {
        // The control: the same proxy over a qualified point starts, so the proxy is what the
        // failing test above exercises.
        coexistenceRunner().withUserConfiguration(JdkProxiedQualifiedSenderConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(Proxy.isProxyClass(ctx.getBean("jdkProxiedSender").getClass())).isTrue();
            assertThat(ctx.getBeanFactory().getDependentBeans(TEMPLATE)).contains("jdkProxiedSender");
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
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
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

    @Component("qualifiedByNameSender")
    record QualifiedByNameSender(KafkaTemplate<String, byte[]> template) {
        QualifiedByNameSender(@Qualifier("kafkaTemplate") KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }
    }

    @Component("rawSender")
    @SuppressWarnings("rawtypes")
    record RawSender(KafkaTemplate template) {}

    @Component("wildcardSender")
    record WildcardSender(KafkaTemplate<String, ?> template) {}

    @Component("optionalSender")
    record OptionalSender(Optional<KafkaTemplate<String, byte[]>> template) {}

    @Component("listSender")
    record ListSender(List<KafkaTemplate<String, byte[]>> templates) {}

    @Component("lazySender")
    record LazySender(KafkaTemplate<String, byte[]> template) {
        LazySender(@Lazy KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({QualifiedByNameSender.class, RawSender.class, WildcardSender.class, OptionalSender.class,
            ListSender.class, LazySender.class})
    static class UnseenShapesConfiguration {
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

    /** A {@code ByteArraySerializer} that counts its instances. */
    public static class InstanceCountingSerializer extends ByteArraySerializer {
        static final AtomicInteger INSTANCES = new AtomicInteger();

        public InstanceCountingSerializer() {
            INSTANCES.incrementAndGet();
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
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1"),
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

    // ── Fix round 2 fixtures ─────────────────────────────────────────────

    /** Boot's String template and Maestro's byte[] template, neither point qualified. */
    record TwoTemplateSender(KafkaTemplate<String, String> text, KafkaTemplate<String, byte[]> engine) {}

    @SuppressWarnings("unchecked")
    static class SupplierSenderRegistrar implements BeanRegistrar {
        @Override
        public void register(BeanRegistry registry, Environment env) {
            registry.registerBean("supplierSender", TwoTemplateSender.class, spec -> spec.supplier(context ->
                    new TwoTemplateSender(context.bean("kafkaTemplate", KafkaTemplate.class),
                            context.bean(TEMPLATE, KafkaTemplate.class))));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SupplierSenderRegistrar.class)
    static class SupplierSenderConfiguration {
    }

    static class ConstructorArgsSenderRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            var definition = new RootBeanDefinition(TwoTemplateSender.class);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference("kafkaTemplate"));
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, new RuntimeBeanReference(TEMPLATE));
            registry.registerBeanDefinition("constructorArgsSender", definition);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ConstructorArgsSenderRegistrar.class)
    static class ConstructorArgsSenderConfiguration {
    }

    /**
     * Field injection: a FactoryBean with constructor dependencies on the Kafka
     * templates is instantiated early for its type and runs into a circular
     * reference with Boot's KafkaAutoConfiguration, whatever Maestro does.
     */
    @Component("senderFactory")
    static class SenderFactoryBean implements FactoryBean<TwoTemplateSender> {
        @Autowired
        private KafkaTemplate<String, String> text;
        @Autowired
        @Qualifier(TEMPLATE)
        private KafkaTemplate<String, byte[]> engine;

        @Override
        public TwoTemplateSender getObject() {
            return new TwoTemplateSender(text, engine);
        }

        @Override
        public Class<?> getObjectType() {
            return TwoTemplateSender.class;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SenderFactoryBean.class)
    static class FactoryBeanSenderConfiguration {
    }

    /** A FactoryBean whose own field is an unqualified byte[] template: still a point. */
    @Component("badSenderFactory")
    static class BadSenderFactoryBean implements FactoryBean<BytesHolder> {
        @Autowired
        private KafkaTemplate<String, byte[]> template;

        @Override
        public BytesHolder getObject() {
            return new BytesHolder(template);
        }

        @Override
        public Class<?> getObjectType() {
            return BytesHolder.class;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BadSenderFactoryBean.class)
    static class BadFactoryBeanConfiguration {
    }

    /** A non-generic template subclass: a point typed with it matches only beans of this type. */
    static class MyBytesTemplate extends KafkaTemplate<String, byte[]> {
        MyBytesTemplate(ProducerFactory<String, byte[]> producerFactory) {
            super(producerFactory);
        }
    }

    @Component("subclassSender")
    record SubclassSender(MyBytesTemplate template) {}

    @Configuration(proxyBeanMethods = false)
    @Import(SubclassSender.class)
    static class SubclassSenderConfiguration {
        @Bean
        KafkaTemplate<String, String> stringTemplate() {
            return hostTemplate(StringSerializer.class);
        }

        @Bean
        MyBytesTemplate myBytesTemplate() {
            return new MyBytesTemplate(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, OpaqueSerializer.class)));
        }
    }

    /**
     * A generic serializer whose target type cannot be resolved and that is no
     * JSON serializer: genuinely unclassifiable. Never instantiated by the tests.
     */
    public static class OpaqueSerializer<T> implements Serializer<T> {
        @Override
        public byte[] serialize(String topic, T data) {
            return new byte[0];
        }
    }

    /** The interface a host's JDK proxy implements. */
    public interface BytesSending {
        KafkaTemplate<String, byte[]> template();
    }

    @Component("jdkProxiedSender")
    static class JdkProxiedSender implements BytesSending {
        private final KafkaTemplate<String, byte[]> template;

        JdkProxiedSender(KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }

        @Override
        public KafkaTemplate<String, byte[]> template() {
            return template;
        }
    }

    @Component("jdkProxiedSender")
    static class JdkProxiedQualifiedSender implements BytesSending {
        private final KafkaTemplate<String, byte[]> template;

        JdkProxiedQualifiedSender(@Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }

        @Override
        public KafkaTemplate<String, byte[]> template() {
            return template;
        }
    }

    /** Wraps {@code jdkProxiedSender} in a JDK dynamic proxy, as an interface-based AOP proxy would. */
    static class JdkProxyingPostProcessor implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!"jdkProxiedSender".equals(beanName)) {
                return bean;
            }
            return Proxy.newProxyInstance(BytesSending.class.getClassLoader(), new Class<?>[] {BytesSending.class},
                    (proxy, method, args) -> method.invoke(bean, args));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdkProxiedSender.class)
    static class JdkProxiedSenderConfiguration {
        @Bean
        static JdkProxyingPostProcessor jdkProxyingPostProcessor() {
            return new JdkProxyingPostProcessor();
        }
    }

    /** An interface-typed {@code @Bean} method, its product wrapped in a JDK proxy. */
    @Configuration(proxyBeanMethods = false)
    static class JdkProxiedBeanMethodConfiguration {
        @Bean
        static JdkProxyingPostProcessor jdkProxyingPostProcessor() {
            return new JdkProxyingPostProcessor();
        }

        @Bean
        BytesSending jdkProxiedSender(KafkaTemplate<String, byte[]> template) {
            return new JdkProxiedSender(template);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdkProxiedQualifiedSender.class)
    static class JdkProxiedQualifiedSenderConfiguration {
        @Bean
        static JdkProxyingPostProcessor jdkProxyingPostProcessor() {
            return new JdkProxyingPostProcessor();
        }
    }

    /** A {@code @MaestroSignalListener}, so the post-processor builds and starts a consumer. */
    public static class OrderSignalListener {
        @MaestroSignalListener(topic = "check.order.signals", signalName = "order")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SignalListenerConfiguration {
        @Bean
        OrderSignalListener orderSignalListener() {
            return new OrderSignalListener();
        }
    }

    /** A JSON serializer subclass that declares {@code byte[]}: it still writes a base64 JSON string. */
    public static class ByteArrayJsonSerializer extends JacksonJsonSerializer<byte[]> {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SubclassSender.class)
    static class StringSubclassSenderConfiguration {
        @Bean
        KafkaTemplate<String, String> stringTemplate() {
            return hostTemplate(StringSerializer.class);
        }

        @Bean
        MyBytesTemplate myBytesTemplate() {
            return new MyBytesTemplate(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }
    }
}
