package io.b2mash.maestro.samples.loan.underwriting.activity.impl;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.samples.loan.underwriting.queue.PendingReviewRegistry;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@link KafkaDecisionMessagingActivities} sends through Maestro's
 * {@code byte[]} template, not Spring Boot's.
 *
 * <p>Maestro's Kafka beans are not default candidates, so Boot's
 * {@code kafkaTemplate} (default {@code StringSerializer}) registers beside
 * them, and an unqualified {@code KafkaTemplate<String, byte[]>} resolves to
 * Boot's. Startup still succeeds; every send would throw
 * {@code SerializationException}. The workflow tests use in-memory doubles and
 * never build this bean, so only a context test sees which template it holds.
 *
 * <p>The context is built from the REAL {@code KafkaMessagingAutoConfiguration}
 * and Boot's {@code KafkaAutoConfiguration}, with this sample's
 * {@code application.yml} loaded. Nothing connects: no broker is behind
 * {@code localhost:1}, nothing sends, and topic verification is off.
 */
class KafkaDecisionMessagingActivitiesTemplateTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class MaestroPropertiesConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    KafkaMessagingAutoConfiguration.class,
                    KafkaAutoConfiguration.class))
            .withUserConfiguration(MaestroPropertiesConfiguration.class, KafkaDecisionMessagingActivities.class,
                    PendingReviewRegistry.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:1",
                    "maestro.messaging.topics.verify-on-startup=off");

    @Test
    @DisplayName("the messaging activities hold maestroKafkaTemplate, whose value serializer is ByteArraySerializer")
    void theActivitiesHoldMaestrosByteArrayTemplate() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var held = (KafkaTemplate<?, ?>) ReflectionTestUtils.getField(
                    ctx.getBean(KafkaDecisionMessagingActivities.class), "kafkaTemplate");

            var heldConfig = held.getProducerFactory().getConfigurationProperties();
            var bootsTemplate = ctx.getBean("kafkaTemplate");

            assertThat(held)
                    .as("the activities' template (is Boot's kafkaTemplate: %s; value serializer: %s)",
                            held == bootsTemplate, heldConfig.get(VALUE_SERIALIZER_CLASS_CONFIG))
                    .isSameAs(ctx.getBean(MaestroKafkaBeans.TEMPLATE))
                    .isNotSameAs(bootsTemplate);
            assertThat(heldConfig).containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        });
    }
}
