package io.b2mash.maestro.messaging.kafka.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDto;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.PropertiesConfiguration;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the fix wave's B3: a host with a {@code KafkaTemplate} of its own makes
 * Spring Boot's {@code kafkaTemplate} back off, so an unqualified
 * {@code KafkaTemplate<String, byte[]>} point (the old docs' advice) finds no
 * candidate at all, and startup fails with a raw
 * {@code NoSuchBeanDefinitionException} that never mentions Maestro. A Spring
 * Boot application reports it with an analysis that names the qualifier.
 *
 * <p>Each application is a real {@link SpringApplication} over the REAL
 * {@code KafkaMessagingAutoConfiguration} and Spring Boot's
 * {@code KafkaAutoConfiguration}, as in
 * {@code ProducerFactoryReplacementFailureAnalyzerTest}.
 */
class ByteArrayTemplateMissingFailureAnalyzerTest {

    static final String DESCRIPTION = "Bean 'legacyBytesSender' injects a KafkaTemplate<…, byte[]> without a "
            + "qualifier (constructor parameter 0 of " + LegacyBytesSender.class.getName() + "), and no Kafka "
            + "template can be injected there by type. Maestro's 'maestroKafkaTemplate' is not a default candidate: "
            + "Maestro's Kafka beans are injected by qualifier only. Spring Boot's kafkaTemplate backs off when the "
            + "application declares a KafkaTemplate of its own (here: hostStringTemplate).";

    static final String ACTION = "Inject Maestro's template with @Qualifier(MaestroKafkaBeans.TEMPLATE) "
            + "(\"maestroKafkaTemplate\"). See docs/configuration.md § Kafka Client Configuration.";

    private ListAppender<ILoggingEvent> reporterLog;

    @BeforeEach
    void attachLogAppender() {
        reporterLog = new ListAppender<>();
        reporterLog.start();
        reporterLogger().addAppender(reporterLog);
    }

    @AfterEach
    void detachLogAppender() {
        reporterLogger().detachAppender(reporterLog);
    }

    @Test
    void anUnqualifiedByteArrayPointBesideAHostTemplate_isExplainedWhenTheApplicationFailsToStart() {
        var failed = startFailing(HostStringTemplate.class, LegacyBytesSenderConfiguration.class);

        assertThat(rootCause(failed.failure()))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("No qualifying bean of type "
                        + "'org.springframework.kafka.core.KafkaTemplate<java.lang.String, byte[]>'");
        assertThat(failed.reported())
                .singleElement()
                .satisfies(report -> assertThat(report)
                        .contains("APPLICATION FAILED TO START")
                        .contains("Description:" + System.lineSeparator() + System.lineSeparator() + DESCRIPTION)
                        .contains("Action:" + System.lineSeparator() + System.lineSeparator() + ACTION)
                        .doesNotContain("Consider defining a bean of type"));
    }

    @Test
    void theAnalysisOfTheRealFailure() {
        var failed = startFailing(HostStringTemplate.class, LegacyBytesSenderConfiguration.class);

        var analysis = analyze(failed);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(analysis.getAction()).isEqualTo(ACTION);
    }

    @Test
    void theActionsFix_aQualifiedPoint_starts() {
        coexistenceRunner().withUserConfiguration(HostStringTemplate.class, QualifiedBytesSenderConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaTemplate");
                    assertThat(ctx.getBean(QualifiedBytesSender.class).template()).isSameAs(ctx.getBean(TEMPLATE));
                });
    }

    @Test
    void itIsConsultedBeforeBootsNoSuchBeanAnalyzer_whateverTheClasspathOrder() {
        for (var bootFirst : List.of(false, true)) {
            var loader = new ProducerFactoryReplacementFailureAnalyzerTest.SpringFactoriesOrderClassLoader(
                    getClass().getClassLoader(), bootFirst);
            var analyzers = SpringFactoriesLoader.forDefaultResourceLocation(loader).load(FailureAnalyzer.class,
                    ArgumentResolver.of(BeanFactory.class, new DefaultListableBeanFactory())
                            .and(Environment.class, new StandardEnvironment()),
                    FailureHandler.handleMessage((message, failure) -> { }));
            var names = analyzers.stream().map(a -> a.getClass().getName()).toList();
            assertThat(names).contains(ByteArrayTemplateMissingFailureAnalyzer.class.getName(), BOOT_NO_SUCH_BEAN_ANALYZER);
            assertThat(names.indexOf(ByteArrayTemplateMissingFailureAnalyzer.class.getName()))
                    .isLessThan(names.indexOf(BOOT_NO_SUCH_BEAN_ANALYZER));
        }
    }

    // ── Never another missing bean ───────────────────────────────────────

    @Test
    void aMissingTemplateForAHostType_isNotAnalyzed() {
        var failed = startFailing(HostStringTemplate.class, HostDtoTemplateUser.class);

        assertThat(rootCause(failed.failure())).isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("KafkaTemplate<java.lang.String, "
                        + "io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures$HostDto>");
        assertThat(analyze(failed)).isNull();
    }

    @Test
    void aQualifiedByteArrayPoint_isNotAnalyzed() {
        var failed = startFailing(QualifiedMissingTemplateUser.class);

        assertThat(rootCause(failed.failure())).isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("KafkaTemplate<java.lang.String, byte[]>");
        assertThat(analyze(failed)).isNull();
    }

    @Test
    void withoutABeanNamedMaestroKafkaTemplate_itIsNotAnalyzed() {
        var failed = startFailing(HostStringTemplate.class, LegacyBytesSenderConfiguration.class);

        assertThat(new ByteArrayTemplateMissingFailureAnalyzer(new DefaultListableBeanFactory())
                .analyze(failed.failure())).isNull();
        var withTemplate = new DefaultListableBeanFactory();
        withTemplate.registerBeanDefinition(TEMPLATE, new RootBeanDefinition(KafkaTemplate.class));
        assertThat(new ByteArrayTemplateMissingFailureAnalyzer(withTemplate).analyze(failed.failure()))
                .as("the same failure, with a definition of that name")
                .isNotNull();
    }

    @Test
    void otherMissingBeans_areNotAnalyzed() {
        var failed = startFailing(HostStringTemplate.class, LegacyBytesSenderConfiguration.class);
        var analyzer = new ByteArrayTemplateMissingFailureAnalyzer(failed.context().getBeanFactory());

        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(ObjectMapper.class))).as("another type").isNull();
        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(ResolvableType.forClassWithGenerics(
                KafkaTemplate.class, String.class, byte[].class))))
                .as("a byte[] template, but not the dependency of an injection point")
                .isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final String BOOT_NO_SUCH_BEAN_ANALYZER =
            "org.springframework.boot.autoconfigure.diagnostics.analyzer.NoSuchBeanDefinitionFailureAnalyzer";

    private static @Nullable FailureAnalysis analyze(Failed failed) {
        return new ByteArrayTemplateMissingFailureAnalyzer(failed.context().getBeanFactory()).analyze(failed.failure());
    }

    record Failed(Throwable failure, ConfigurableApplicationContext context, List<String> reported) {}

    private Failed startFailing(Class<?>... hostConfigurations) {
        var sources = new ArrayList<Class<?>>();
        sources.add(Application.class);
        sources.addAll(List.of(hostConfigurations));
        var app = new SpringApplication(sources.toArray(Class<?>[]::new));
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of(
                "maestro.service-name", "coexist",
                "spring.kafka.bootstrap-servers", "127.0.0.1:1",
                "maestro.messaging.topics.verify-on-startup", "off"));
        var context = new AtomicReference<ConfigurableApplicationContext>();
        app.setApplicationContextFactory(type -> {
            var created = new AnnotationConfigApplicationContext();
            context.set(created);
            return created;
        });
        var previous = System.getProperty(LoggingSystem.SYSTEM_PROPERTY);
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        try {
            app.run().close();
            return fail("the application started");
        }
        catch (RuntimeException ex) {
            var reported = reporterLog.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            return new Failed(ex, context.get(), reported);
        }
        finally {
            if (previous == null) {
                System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
            }
            else {
                System.setProperty(LoggingSystem.SYSTEM_PROPERTY, previous);
            }
        }
    }

    private static Throwable rootCause(Throwable t) {
        var cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Logger reporterLogger() {
        return (Logger) LoggerFactory.getLogger(LoggingFailureAnalysisReporter.class);
    }

    private static <V> KafkaTemplate<String, V> template() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, V>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    // ── Application and host configurations ──────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({KafkaMessagingAutoConfiguration.class, KafkaAutoConfiguration.class})
    @Import(PropertiesConfiguration.class)
    static class Application {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /** The host's own template for its own traffic: Boot's kafkaTemplate backs off. */
    @Configuration(proxyBeanMethods = false)
    static class HostStringTemplate {
        @Bean
        KafkaTemplate<String, String> hostStringTemplate() {
            return template();
        }
    }

    /** The old docs' advice: an unqualified byte[] template, by constructor. */
    @Component("legacyBytesSender")
    static class LegacyBytesSender {
        LegacyBytesSender(KafkaTemplate<String, byte[]> template) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LegacyBytesSender.class)
    static class LegacyBytesSenderConfiguration {
    }

    @Component("qualifiedBytesSender")
    record QualifiedBytesSender(KafkaTemplate<String, byte[]> template) {
        QualifiedBytesSender(@Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> template) {
            this.template = template;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(QualifiedBytesSender.class)
    static class QualifiedBytesSenderConfiguration {
    }

    /** A host bean that needs a template for its own type and finds none. */
    @Configuration(proxyBeanMethods = false)
    static class HostDtoTemplateUser {
        @Bean
        Object hostDtoTemplateUser(KafkaTemplate<String, HostDto> template) {
            return template;
        }
    }

    /** A qualified byte[] point naming a bean that does not exist. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedMissingTemplateUser {
        @Bean
        Object qualifiedMissingTemplateUser(@Qualifier("noSuchTemplate") KafkaTemplate<String, byte[]> template) {
            return template;
        }
    }
}
