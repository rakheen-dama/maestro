package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for the tests that pin how Spring Boot's Kafka beans and
 * Maestro's engine Kafka beans coexist (WP7, 3.4).
 *
 * <p>Public so the {@code listener} and root test packages can reuse the same
 * runner and host types.
 */
public final class CoexistenceFixtures {

    private CoexistenceFixtures() {
    }

    /** A host's own Kafka payload type, unrelated to Maestro's {@code byte[]}. */
    public record HostDto(String name) {}

    /** A host bean that injects a {@code KafkaTemplate} for its own type, without a qualifier. */
    public record HostDtoSender(KafkaTemplate<String, HostDto> template) {}

    /** Declares {@link HostDtoSender} the way a host would: an unqualified typed template parameter. */
    @Configuration(proxyBeanMethods = false)
    public static class HostSenderConfiguration {

        @Bean
        HostDtoSender hostDtoSender(KafkaTemplate<String, HostDto> template) {
            return new HostDtoSender(template);
        }
    }

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    public static class PropertiesConfiguration {
    }

    /**
     * A runner built from the REAL {@code KafkaMessagingAutoConfiguration} and
     * Spring Boot's {@code KafkaAutoConfiguration}, sorted the way Boot sorts
     * them. No broker is behind {@code localhost:1}; nothing here sends or
     * polls, and topic verification is off.
     *
     * @return a fresh runner
     */
    public static ApplicationContextRunner coexistenceRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaMessagingAutoConfiguration.class,
                        KafkaAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "maestro.service-name=coexist",
                        "spring.kafka.bootstrap-servers=localhost:1",
                        "maestro.messaging.topics.verify-on-startup=off");
    }

    /**
     * The same context as {@link #coexistenceRunner()}, but with Spring Boot's
     * {@code KafkaAutoConfiguration} evaluated FIRST: {@code UserConfigurations}
     * keep their declared order, where {@code AutoConfigurations} sorts
     * Maestro's configuration first (alphabetically, with no declared relation).
     * Use {@link #assertBootEvaluatedFirst} to prove the order really held.
     *
     * @return a fresh runner
     */
    public static ApplicationContextRunner bootFirstRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(
                        KafkaAutoConfiguration.class,
                        KafkaMessagingAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "maestro.service-name=coexist",
                        "spring.kafka.bootstrap-servers=localhost:1",
                        "maestro.messaging.topics.verify-on-startup=off");
    }

    /**
     * Asserts that Boot's configuration was evaluated before Maestro's: Boot's
     * {@code kafkaTemplate} definition precedes {@code maestroKafkaTemplate}.
     *
     * @param ctx a started context from {@link #bootFirstRunner()}
     */
    public static void assertBootEvaluatedFirst(ApplicationContext ctx) {
        var names = List.of(ctx.getBeanDefinitionNames());
        assertThat(names.indexOf("kafkaTemplate"))
                .as("this context really evaluated Boot's configuration first")
                .isNotNegative()
                .isLessThan(names.indexOf(MaestroKafkaBeans.TEMPLATE));
    }

    /**
     * The configuration of the named producer factory.
     *
     * @param ctx      the context
     * @param beanName the {@code ProducerFactory} bean's name
     * @return its {@code getConfigurationProperties()}
     */
    public static Map<String, Object> producerConfig(ApplicationContext ctx, String beanName) {
        ProducerFactory<?, ?> factory = ctx.getBean(beanName, ProducerFactory.class);
        return factory.getConfigurationProperties();
    }

    /**
     * The configuration of the consumer factory a listener container was built over.
     *
     * @param container the container
     * @return its consumer factory's {@code getConfigurationProperties()}
     */
    public static Map<String, Object> consumerConfig(MessageListenerContainer container) {
        var factory = (ConsumerFactory<?, ?>) Objects.requireNonNull(
                ReflectionTestUtils.getField(container, "consumerFactory"));
        return factory.getConfigurationProperties();
    }
}
