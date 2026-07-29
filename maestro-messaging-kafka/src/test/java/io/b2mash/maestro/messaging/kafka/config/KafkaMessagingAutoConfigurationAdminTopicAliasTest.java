package io.b2mash.maestro.messaging.kafka.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.slf4j.event.Level;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the precedence between {@code maestro.messaging.topics.admin-events}
 * and the deprecated {@code maestro.admin.events.topic} alias (Issue 6).
 *
 * <p>{@code maestro.admin.events.topic} bound into {@link MaestroProperties}
 * but nothing read it. Rather than delete the block — disabling dashboard
 * events without touching the messaging block is a legitimate need — it is
 * now treated as a deprecated alias for
 * {@code maestro.messaging.topics.admin-events}: if only the alias is set, it
 * is used; if both are set, the messaging property wins and a WARN is logged
 * so the conflict is not silently swallowed.
 */
@DisplayName("The admin-events topic alias and its precedence")
class KafkaMessagingAutoConfigurationAdminTopicAliasTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=alias-test");

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
            var config = context.getBean(KafkaMessagingConfig.class);
            assertThat(config.adminEventsTopic()).isEqualTo("maestro.admin.events");
        });
    }

    @Test
    @DisplayName("only maestro.messaging.topics.admin-events set — used as-is")
    void onlyMessagingTopicSet_isUsed() {
        runner.withPropertyValues("maestro.messaging.topics.admin-events=custom.messaging.topic")
                .run(context -> {
                    var config = context.getBean(KafkaMessagingConfig.class);
                    assertThat(config.adminEventsTopic()).isEqualTo("custom.messaging.topic");
                });
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("only the deprecated maestro.admin.events.topic alias set — used as a fallback")
    void onlyAliasSet_isUsedAsFallback() {
        runner.withPropertyValues("maestro.admin.events.topic=legacy.admin.topic")
                .run(context -> {
                    var config = context.getBean(KafkaMessagingConfig.class);
                    assertThat(config.adminEventsTopic()).isEqualTo("legacy.admin.topic");
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
                    var config = context.getBean(KafkaMessagingConfig.class);
                    assertThat(config.adminEventsTopic())
                            .as("maestro.messaging.topics.admin-events must win the conflict")
                            .isEqualTo("custom.messaging.topic");
                });

        assertThat(warnMessages())
                .as("a conflicting alias must be logged, not silently dropped")
                .anySatisfy(message -> assertThat(message)
                        .contains("custom.messaging.topic")
                        .contains("legacy.admin.topic"));
    }

    @Test
    @DisplayName("both set to the same value — no conflict, no warning")
    void bothSetToSameValue_noWarningNeeded() {
        runner.withPropertyValues(
                        "maestro.messaging.topics.admin-events=same.topic",
                        "maestro.admin.events.topic=same.topic")
                .run(context -> {
                    var config = context.getBean(KafkaMessagingConfig.class);
                    assertThat(config.adminEventsTopic()).isEqualTo("same.topic");
                });
        assertThat(warnMessages()).isEmpty();
    }

    // ── Log capture helpers ─────────────────────────────────────────────

    private static ch.qos.logback.classic.Logger logbackLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);
    }

    private java.util.List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().toString().equals(Level.WARN.toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Enables the properties without dragging in the rest of the engine. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
