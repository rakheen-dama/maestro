package io.b2mash.maestro.messaging.kafka;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit-level pins for {@link KafkaDeadLetterTopicCheck} against a real broker
 * (Issue 24): does it actually warn when a dead-letter topic is missing, stay
 * silent when it is present, and never throw regardless of what the probe
 * itself does.
 */
@DisplayName("KafkaDeadLetterTopicCheck against a real broker")
class KafkaDeadLetterTopicCheckTest extends KafkaTestSupport {

    private Admin admin;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUpAdmin() {
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDownAdmin() {
        admin.close();
        logbackLogger().detachAppender(logAppender);
    }

    @Test
    @DisplayName("warns and reports a topic whose dead-letter companion does not exist")
    void warnsForMissingDeadLetterTopic() {
        var topic = "check.missing." + UUID.randomUUID();

        var missing = KafkaDeadLetterTopicCheck.warnOnMissing(admin, List.of(topic), ".DLT");

        assertThat(missing).containsExactly(topic + ".DLT");
        assertThat(warnMessages())
                .as("the WARN must name both the missing dead-letter topic and the source topic")
                .anySatisfy(message -> assertThat(message)
                        .contains(topic + ".DLT")
                        .contains(topic));
    }

    @Test
    @DisplayName("does not warn when the dead-letter topic exists")
    void noWarningWhenDeadLetterTopicExists() throws Exception {
        var topic = "check.present." + UUID.randomUUID();
        createTopics(topic + ".DLT");

        var missing = KafkaDeadLetterTopicCheck.warnOnMissing(admin, List.of(topic), ".DLT");

        assertThat(missing).isEmpty();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("an empty topic collection is a no-op")
    void emptyTopicsIsNoOp() {
        var missing = KafkaDeadLetterTopicCheck.warnOnMissing(admin, List.of(), ".DLT");

        assertThat(missing).isEmpty();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("a probe that cannot run — a closed Admin client — never throws, and logs no WARN")
    void probeFailureNeverThrowsAndNeverWarns() {
        admin.close();

        var thrown = catchThrowable(() -> KafkaDeadLetterTopicCheck.warnOnMissing(admin, List.of("x"), ".DLT"));

        assertThat(thrown).as("the check's own probe failure must never propagate").isNull();
        assertThat(warnMessages())
                .as("an inconclusive probe must never produce a false WARN")
                .isEmpty();
    }

    // ── Log capture helpers ─────────────────────────────────────────────

    private static ch.qos.logback.classic.Logger logbackLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KafkaDeadLetterTopicCheck.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().toString().equals(Level.WARN.toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
