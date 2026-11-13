package io.b2mash.maestro.messaging.kafka.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDto;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.PropertiesConfiguration;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
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
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.PRODUCER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins WP7 Task 4 (B): a plain {@code @Bean} replacing
 * {@code maestroKafkaProducerFactory} fails startup in Spring Boot's
 * {@code kafkaTemplate} with an error that never mentions Maestro, and
 * {@code ProducerFactoryReplacementFailureAnalyzer} explains it — only that
 * failure, never another missing {@code ProducerFactory}.
 *
 * <p>Each application is a real {@link SpringApplication} over the REAL
 * {@code KafkaMessagingAutoConfiguration} and Spring Boot's
 * {@code KafkaAutoConfiguration} ({@code @ImportAutoConfiguration}, sorted as
 * Boot sorts them), so the analysis comes through Boot's own
 * {@code spring.factories} discovery and {@code LoggingFailureAnalysisReporter},
 * exactly as a host sees it. Boot's logging system is set to {@code none} for
 * the run so it does not reconfigure Logback under the other tests in this JVM.
 */
class ProducerFactoryReplacementFailureAnalyzerTest {

    static final String DESCRIPTION = "The bean 'maestroKafkaProducerFactory' replaces Maestro's engine producer "
            + "factory but is a default candidate, so Spring Boot's kafkaProducerFactory backed off. Spring Boot's "
            + "kafkaTemplate (parameter 0 of method kafkaTemplate in "
            + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration) then found no "
            + "ProducerFactory<Object, Object> to use.";

    static final String ACTION = "Declare the replacement with @Bean(name = MaestroKafkaBeans.PRODUCER_FACTORY, "
            + "defaultCandidate = false), so Spring Boot's kafkaProducerFactory registers beside it, or define your "
            + "own KafkaTemplate bean, so Spring Boot's kafkaTemplate backs off. "
            + "See docs/configuration.md § Kafka Client Configuration.";

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
    void aPlainProducerFactoryReplacement_isExplainedWhenTheApplicationFailsToStart() {
        var failed = startFailing(PlainProducerFactoryReplacement.class);

        // The failure itself is the one KafkaCoexistenceContextTest's matrix pins.
        assertThat(rootCause(failed.failure()))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("No qualifying bean of type "
                        + "'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'");
        // Boot picked the analyzer up from spring.factories and reported it.
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
        var failed = startFailing(PlainProducerFactoryReplacement.class);

        var analysis = analyze(failed);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(analysis.getAction()).isEqualTo(ACTION);
    }

    @Test
    void itIsConsultedBeforeBootsNoSuchBeanAnalyzer_whateverTheClasspathOrder() {
        // Spring Boot uses the first non-null analysis. Its NoSuchBeanDefinitionFailureAnalyzer
        // is unordered and analyzes this failure too (it did before this analyzer existed),
        // so classpath order alone must not decide which one a host sees.
        for (var bootFirst : List.of(false, true)) {
            var loader = new SpringFactoriesOrderClassLoader(getClass().getClassLoader(), bootFirst);
            var analyzers = SpringFactoriesLoader.forDefaultResourceLocation(loader).load(FailureAnalyzer.class,
                    ArgumentResolver.of(BeanFactory.class, new DefaultListableBeanFactory())
                            .and(Environment.class, new StandardEnvironment()),
                    FailureHandler.handleMessage((message, failure) -> { }));
            var names = analyzers.stream().map(a -> a.getClass().getName()).toList();
            assertThat(names)
                    .as("spring.factories %s", bootFirst ? "with spring-boot-autoconfigure first" : "in classpath order")
                    .contains(ProducerFactoryReplacementFailureAnalyzer.class.getName(), BOOT_NO_SUCH_BEAN_ANALYZER);
            assertThat(names.indexOf(ProducerFactoryReplacementFailureAnalyzer.class.getName()))
                    .isLessThan(names.indexOf(BOOT_NO_SUCH_BEAN_ANALYZER));
        }
    }

    @Test
    void theActionsSecondFix_aHostTemplate_starts() {
        coexistenceRunner().withUserConfiguration(PlainProducerFactoryReplacement.class, HostBytesTemplate.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean("kafkaTemplate"));
    }

    // ── Never another missing ProducerFactory ────────────────────────────

    @Test
    void aMissingProducerFactoryWithNoMaestroReplacement_isNotAnalyzed() {
        // The host declares only a ProducerFactory for its own type: Boot's
        // kafkaProducerFactory backs off and Boot's kafkaTemplate fails exactly as
        // above, but no maestroKafkaProducerFactory replacement is involved.
        var failed = startFailing(HostOwnProducerFactoryOnly.class);

        assertThat(innermostUnsatisfiedDependency(failed.failure()).getBeanName()).isEqualTo("kafkaTemplate");
        assertThat(rootCause(failed.failure())).isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThat(analyze(failed)).isNull();
        assertThat(failed.reported())
                .as("nothing reported names Maestro's replacement")
                .noneMatch(report -> report.contains("maestroKafkaProducerFactory"));
    }

    @Test
    void aPlainReplacementBesideAnotherDefaultCandidateFactory_isNotAnalyzed() {
        // Declaring the replacement defaultCandidate = false would not bring Boot's
        // factory back here: the host's own factory also makes it back off.
        var failed = startFailing(PlainProducerFactoryReplacement.class, HostOwnProducerFactoryOnly.class);

        assertThat(innermostUnsatisfiedDependency(failed.failure()).getBeanName()).isEqualTo("kafkaTemplate");
        assertThat(analyze(failed)).isNull();
    }

    @Test
    void aMissingProducerFactoryOfAHostBean_isNotAnalyzed() {
        // Same replacement, but the first bean to fail is the host's own, not
        // Boot's kafkaTemplate.
        var failed = startFailing(PlainProducerFactoryReplacement.class, HostFactoryUser.class);

        assertThat(innermostUnsatisfiedDependency(failed.failure()).getBeanName()).isEqualTo("hostFactoryUser");
        assertThat(analyze(failed)).isNull();
    }

    @Test
    void otherMissingBeans_areNotAnalyzed() {
        var failed = startFailing(PlainProducerFactoryReplacement.class);
        var analyzer = new ProducerFactoryReplacementFailureAnalyzer(failed.context().getBeanFactory());

        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(ObjectMapper.class)))
                .as("another type")
                .isNull();
        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(ProducerFactory.class)))
                .as("a ProducerFactory, but not a dependency of Boot's kafkaTemplate")
                .isNull();
    }

    @Test
    void maestrosOwnFactoryDefinition_isNeverBlamed() {
        // The real failure, analyzed against a bean factory whose
        // maestroKafkaProducerFactory is Maestro's own kind: not a default candidate.
        var failed = startFailing(PlainProducerFactoryReplacement.class);

        assertThat(new ProducerFactoryReplacementFailureAnalyzer(withProducerFactoryDefinition(false))
                .analyze(failed.failure())).isNull();
        assertThat(new ProducerFactoryReplacementFailureAnalyzer(withProducerFactoryDefinition(true))
                .analyze(failed.failure()))
                .as("the same definition as a default candidate is the replacement this analyzer explains")
                .isNotNull();
    }

    private static DefaultListableBeanFactory withProducerFactoryDefinition(boolean defaultCandidate) {
        var beanFactory = new DefaultListableBeanFactory();
        var definition = new RootBeanDefinition(DefaultKafkaProducerFactory.class);
        definition.setDefaultCandidate(defaultCandidate);
        beanFactory.registerBeanDefinition(PRODUCER_FACTORY, definition);
        return beanFactory;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final String BOOT_NO_SUCH_BEAN_ANALYZER =
            "org.springframework.boot.autoconfigure.diagnostics.analyzer.NoSuchBeanDefinitionFailureAnalyzer";

    /** Serves {@code META-INF/spring.factories} with spring-boot-autoconfigure's copy first, or as found. */
    private static final class SpringFactoriesOrderClassLoader extends ClassLoader {

        private final boolean bootAutoconfigureFirst;

        SpringFactoriesOrderClassLoader(ClassLoader parent, boolean bootAutoconfigureFirst) {
            super(parent);
            this.bootAutoconfigureFirst = bootAutoconfigureFirst;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            var urls = Collections.list(super.getResources(name));
            if (bootAutoconfigureFirst && SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION.equals(name)) {
                urls.sort(Comparator.comparing(url -> !url.toString().contains("spring-boot-autoconfigure")));
            }
            return Collections.enumeration(urls);
        }
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
                "spring.kafka.bootstrap-servers", "localhost:1",
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

    private static @Nullable FailureAnalysis analyze(Failed failed) {
        return new ProducerFactoryReplacementFailureAnalyzer(failed.context().getBeanFactory()).analyze(failed.failure());
    }

    private static Throwable rootCause(Throwable t) {
        var cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static UnsatisfiedDependencyException innermostUnsatisfiedDependency(Throwable t) {
        UnsatisfiedDependencyException found = null;
        for (var cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnsatisfiedDependencyException ude) {
                found = ude;
            }
        }
        assertThat(found).as("an UnsatisfiedDependencyException in the chain").isNotNull();
        return found;
    }

    private static Logger reporterLogger() {
        return (Logger) LoggerFactory.getLogger(LoggingFailureAnalysisReporter.class);
    }

    private static Map<String, Object> producerProps(Class<?> valueSerializer) {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
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

    /** Replaces Maestro's producer factory by name with a plain {@code @Bean} (a default candidate). */
    @Configuration(proxyBeanMethods = false)
    static class PlainProducerFactoryReplacement {
        @Bean(name = PRODUCER_FACTORY)
        ProducerFactory<String, byte[]> replacement() {
            return new DefaultKafkaProducerFactory<>(producerProps(ByteArraySerializer.class));
        }
    }

    /** The action's second fix: the host's own template, over the replacement. */
    @Configuration(proxyBeanMethods = false)
    static class HostBytesTemplate {
        @Bean
        KafkaTemplate<String, byte[]> hostTemplate(@Qualifier(PRODUCER_FACTORY) ProducerFactory<String, byte[]> pf) {
            return new KafkaTemplate<>(pf);
        }

        @Bean
        Object usesTheEngineTemplate(@Qualifier(TEMPLATE) KafkaTemplate<String, byte[]> template) {
            return template;
        }
    }

    /** A host that declares only a producer factory for its own type, and no Maestro replacement. */
    @Configuration(proxyBeanMethods = false)
    static class HostOwnProducerFactoryOnly {
        @Bean
        ProducerFactory<String, HostDto> hostProducerFactory() {
            return new DefaultKafkaProducerFactory<>(producerProps(JacksonJsonSerializer.class));
        }
    }

    /** A host bean that needs a {@code ProducerFactory} for its own type and finds none. */
    @Configuration(proxyBeanMethods = false)
    static class HostFactoryUser {
        @Bean
        Object hostFactoryUser(ProducerFactory<String, HostDto> pf) {
            return pf;
        }
    }
}
