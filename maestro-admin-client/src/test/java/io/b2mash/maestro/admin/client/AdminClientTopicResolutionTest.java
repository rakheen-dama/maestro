package io.b2mash.maestro.admin.client;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the precedence between {@code maestro.messaging.topics.admin-events}
 * (the canonical property, owned by the starter/messaging-kafka module) and
 * the deprecated {@code maestro.admin.events.topic} alias, from the
 * {@code maestro-admin-client} side (audit F10).
 *
 * <p>{@code maestro-admin-client} does not depend on the starter, so it
 * cannot reuse {@code KafkaMessagingAutoConfiguration.resolveAdminEventsTopic}
 * — {@link AdminClientAutoConfiguration#resolveTopic} mirrors that logic over
 * {@link org.springframework.core.env.Environment} instead. This test mirrors
 * {@code KafkaMessagingAutoConfigurationAdminTopicAliasTest} in
 * {@code maestro-messaging-kafka}, which pins the twin's behaviour.
 *
 * <p>Before the fix, {@code adminEventPublisher(...)} read only
 * {@link AdminClientProperties#getTopic()} — the deprecated alias — and had
 * no knowledge of {@code maestro.messaging.topics.admin-events} at all, so a
 * service that only set the canonical property (as the docs recommend)
 * published lifecycle events to the wrong topic.
 */
@DisplayName("AdminClientAutoConfiguration — admin-events topic resolution (audit F10)")
class AdminClientTopicResolutionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdminClientAutoConfiguration.class))
            .withUserConfiguration(KafkaTemplateConfiguration.class)
            .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build());

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logbackLogger().detachAppender(logAppender);
    }

    @Test
    @DisplayName("neither set — the documented default topic is used")
    void neitherSet_usesDefaultTopic() {
        runner.run(context -> {
            var publisher = context.getBean(AdminEventPublisher.class);
            assertThat(publisher.topic()).isEqualTo("maestro.admin.events");
        });
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("only maestro.messaging.topics.admin-events set — used as-is")
    void onlyMessagingTopicSet_isUsed() {
        runner.withPropertyValues("maestro.messaging.topics.admin-events=custom.messaging.topic")
                .run(context -> {
                    var publisher = context.getBean(AdminEventPublisher.class);
                    assertThat(publisher.topic()).isEqualTo("custom.messaging.topic");
                });
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("only the deprecated maestro.admin.events.topic alias set — used as a fallback")
    void onlyAliasSet_isUsedAsFallback() {
        runner.withPropertyValues("maestro.admin.events.topic=legacy.admin.topic")
                .run(context -> {
                    var publisher = context.getBean(AdminEventPublisher.class);
                    assertThat(publisher.topic()).isEqualTo("legacy.admin.topic");
                });
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("both set to different values — the messaging property wins and a WARN is logged")
    void bothSetDifferently_messagingWinsAndWarns() {
        runner.withPropertyValues(
                        "maestro.messaging.topics.admin-events=custom.messaging.topic",
                        "maestro.admin.events.topic=legacy.admin.topic")
                .run(context -> {
                    var publisher = context.getBean(AdminEventPublisher.class);
                    assertThat(publisher.topic())
                            .as("maestro.messaging.topics.admin-events must win the conflict")
                            .isEqualTo("custom.messaging.topic");
                });

        assertThat(warnMessages())
                .as("a conflicting alias must be logged, not silently dropped")
                .anySatisfy(message -> assertThat(message)
                        .contains("custom.messaging.topic")
                        .contains("legacy.admin.topic"));
    }

    // ── Pin (CR-7): a blank canonical topic is rejected at startup ──────

    @Test
    @DisplayName("a blank maestro.messaging.topics.admin-events is rejected at startup, "
            + "not silently defaulted")
    void blankMessagingTopic_rejectedAtStartup() {
        // Environment#getProperty(key, default) only falls back to the default
        // when the property is ABSENT — a present-but-blank value flows
        // through unvalidated unless resolveTopic rejects it explicitly.
        runner.withPropertyValues("maestro.messaging.topics.admin-events=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .rootCause()
                            .hasMessageContaining("maestro.messaging.topics.admin-events");
                });
    }

    // ── Log capture helpers ─────────────────────────────────────────────

    private static ch.qos.logback.classic.Logger logbackLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AdminClientAutoConfiguration.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().toString().equals(Level.WARN.toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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
}
