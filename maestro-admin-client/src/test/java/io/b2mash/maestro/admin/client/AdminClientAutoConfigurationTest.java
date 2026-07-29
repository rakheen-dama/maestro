package io.b2mash.maestro.admin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApplicationContextRunner} tests for {@link AdminClientAutoConfiguration},
 * mirroring the pattern used by {@code MaestroAutoConfigurationLifecycleEventsTest}
 * in the starter module: drive the real conditional wiring rather than
 * constructing beans by hand, since that's exactly what previously let
 * {@code maestro.admin.events.enabled} silently do nothing (Issue 6, starter
 * module) — the same class of bug this configuration is equally exposed to.
 */
@DisplayName("AdminClientAutoConfiguration")
class AdminClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdminClientAutoConfiguration.class))
            .withUserConfiguration(KafkaTemplateConfiguration.class)
            .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("registers AdminEventPublisher when enabled (the default)")
    void registersPublisher_byDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AdminEventPublisher.class);
            assertThat(context).hasSingleBean(AdminClientProperties.class);
        });
    }

    @Test
    @DisplayName("registers AdminEventPublisher when maestro.admin.events.enabled=true explicitly")
    void registersPublisher_whenExplicitlyEnabled() {
        runner.withPropertyValues("maestro.admin.events.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AdminEventPublisher.class));
    }

    @Test
    @DisplayName("does not register AdminEventPublisher when maestro.admin.events.enabled=false")
    void doesNotRegisterPublisher_whenDisabled() {
        runner.withPropertyValues("maestro.admin.events.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AdminEventPublisher.class);
                });
    }

    @Test
    @DisplayName("fails to start when no KafkaTemplate bean is available, since the publisher requires one")
    void failsToStart_withoutKafkaTemplateBean() {
        // @ConditionalOnClass only gates on KafkaTemplate being on the classpath
        // (it always is here, being a compile dependency of this module); the
        // bean itself is still a hard constructor dependency of AdminEventPublisher.
        // This pins that a missing KafkaTemplate bean surfaces as a clear startup
        // failure rather than silently skipping publisher registration.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminClientAutoConfiguration.class))
                .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("binds the configured topic onto AdminClientProperties and the publisher")
    void bindsConfiguredTopic() {
        runner.withPropertyValues("maestro.admin.events.topic=my-custom-topic")
                .run(context -> {
                    var properties = context.getBean(AdminClientProperties.class);
                    assertThat(properties.getTopic()).isEqualTo("my-custom-topic");
                });
    }

    @Test
    @DisplayName("defers to a user-supplied AdminEventPublisher bean")
    void respectsExistingPublisherBean() {
        runner.withUserConfiguration(CustomPublisherConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AdminEventPublisher.class);
                    assertThat(context.getBean(AdminEventPublisher.class))
                            .isSameAs(CustomPublisherConfiguration.INSTANCE);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaTemplateConfiguration {

        @Bean
        ProducerFactory<String, byte[]> producerFactory() {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:0",
                    org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.ByteArraySerializer.class));
        }

        @Bean
        KafkaTemplate<String, byte[]> kafkaTemplate(ProducerFactory<String, byte[]> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPublisherConfiguration {

        static final AdminEventPublisher INSTANCE =
                new AdminEventPublisher(null, JsonMapper.builder().build(), "unused-topic");

        @Bean
        AdminEventPublisher adminEventPublisher() {
            return INSTANCE;
        }
    }
}
