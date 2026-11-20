package io.b2mash.maestro.messaging.kafka;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.spring.config.MaestroProperties.TopicsProperties.TopicVerification;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit pins for {@link KafkaTopicVerifier}, no broker: the required-topic
 * composition, the missing/inconclusive classification against a mocked
 * {@link Admin}, and the fail/warn policy. The real-broker counterpart is
 * {@link KafkaTopicVerificationStartupTest}.
 */
@DisplayName("KafkaTopicVerifier")
class KafkaTopicVerifierTest {

    private static final String BOOTSTRAP = "broker:9092";

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

    // ── requiredTopics ────────────────────────────────────────────────────

    @Nested
    @DisplayName("requiredTopics")
    class RequiredTopics {

        @Test
        @DisplayName("engine signal topic + .DLT, admin-events, and each listener topic + .DLT, in order, deduplicated")
        void fullComposition() {
            var config = config(null, true, TopicVerification.FAIL);

            var required = KafkaTopicVerifier.requiredTopics(config, "orders", true,
                    List.of("payments.results", "payments.results", "shipping.events"));

            assertThat(required).containsExactly(
                    "maestro.signals.orders", "maestro.signals.orders.DLT",
                    "maestro.admin.events",
                    "payments.results", "payments.results.DLT",
                    "shipping.events", "shipping.events.DLT");
        }

        @Test
        @DisplayName("a fixed maestro.messaging.topics.signals replaces the per-service name")
        void fixedSignalTopic() {
            var config = config("custom.signals", true, TopicVerification.FAIL);

            var required = KafkaTopicVerifier.requiredTopics(config, "orders", true, List.of());

            assertThat(required).containsExactly("custom.signals", "custom.signals.DLT", "maestro.admin.events");
        }

        @Test
        @DisplayName("redelivery disabled excludes every .DLT companion")
        void redeliveryOffExcludesDeadLetterTopics() {
            var config = config(null, false, TopicVerification.FAIL);

            var required = KafkaTopicVerifier.requiredTopics(config, "orders", true, List.of("payments.results"));

            assertThat(required).containsExactly("maestro.signals.orders", "maestro.admin.events", "payments.results");
        }

        @Test
        @DisplayName("admin events disabled excludes the admin-events topic")
        void adminEventsOffExcludesAdminTopic() {
            var config = config(null, true, TopicVerification.FAIL);

            var required = KafkaTopicVerifier.requiredTopics(config, "orders", false, List.of());

            assertThat(required).containsExactly("maestro.signals.orders", "maestro.signals.orders.DLT");
        }

        @Test
        @DisplayName("no service name and no fixed signal topic — the signal topic cannot be named and is left out")
        void noServiceName() {
            var config = config(null, true, TopicVerification.FAIL);

            var required = KafkaTopicVerifier.requiredTopics(config, null, true, List.of("x"));

            assertThat(required).containsExactly("maestro.admin.events", "x", "x.DLT");
        }
    }

    // ── verify ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("classifies present, confirmed-missing and inconclusive topics in required order")
        void classifiesEachTopic() {
            var admin = adminReturning(Map.of(
                    "a", present("a"),
                    "b.DLT", failed(new UnknownTopicOrPartitionException("no such topic")),
                    "c", failed(new TopicAuthorizationException("denied")),
                    "d", failed(new org.apache.kafka.common.errors.TimeoutException("slow"))));

            var result = KafkaTopicVerifier.verify(admin, List.of("a", "b.DLT", "c", "d"));

            assertThat(result.present()).containsExactly("a");
            assertThat(result.missing()).as("only UnknownTopicOrPartitionException means missing")
                    .containsExactly("b.DLT");
            assertThat(result.inconclusive()).as("authorization and timeout failures are inconclusive, never missing")
                    .containsExactly("c", "d");
            assertThat(result.allPresent()).isFalse();
        }

        /**
         * PR #78 CI run 44613617971: right after {@code createTopics} succeeds a
         * broker can still answer {@code UnknownTopicOrPartition} for the new
         * topic — "It may take several seconds after CreateTopicsResult returns
         * success for all the brokers to become aware that the topics have been
         * created" (Admin#createTopics). A topic is confirmed missing only if the
         * broker keeps saying so for the probe's window; one it describes on a
         * re-check within it is present.
         */
        @Test
        @DisplayName("a topic the broker describes on a re-check within the probe window is present, not missing")
        void aTopicNotYetVisibleOnTheFirstDescribeIsPresentOnceDescribed() {
            var first = mock(DescribeTopicsResult.class);
            when(first.topicNameValues()).thenReturn(new LinkedHashMap<>(Map.of(
                    "a", present("a"),
                    "new", failed(new UnknownTopicOrPartitionException("not yet in this broker's metadata")))));
            var second = mock(DescribeTopicsResult.class);
            when(second.topicNameValues()).thenReturn(new LinkedHashMap<>(Map.of("new", present("new"))));
            var admin = mock(Admin.class);
            when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(first, second);

            var result = KafkaTopicVerifier.verify(admin, List.of("a", "new"));

            assertThat(result.present()).containsExactly("a", "new");
            assertThat(result.missing()).isEmpty();
            assertThat(result.allPresent()).isTrue();
        }

        /**
         * A re-check can only clear a topic the broker reported unknown: a
         * re-check that times out or fails otherwise leaves it confirmed missing
         * (found looping KafkaTopicVerificationStartupTest: a last re-check at the
         * end of the window timed out and turned "missing" into "inconclusive",
         * so a context with genuinely missing topics started).
         */
        @Test
        @DisplayName("a re-check that times out leaves a topic the broker reported unknown confirmed missing")
        void aRecheckThatTimesOutDoesNotClearAMissingTopic() {
            var first = mock(DescribeTopicsResult.class);
            when(first.topicNameValues()).thenReturn(new LinkedHashMap<>(Map.of(
                    "gone", failed(new UnknownTopicOrPartitionException("no such topic")))));
            var timedOut = mock(DescribeTopicsResult.class);
            when(timedOut.topicNameValues()).thenReturn(new LinkedHashMap<>(Map.of(
                    "gone", failed(new org.apache.kafka.common.errors.TimeoutException("slow re-check")))));
            var admin = mock(Admin.class);
            when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(first, timedOut);

            var result = KafkaTopicVerifier.verify(admin, List.of("gone"));

            assertThat(result.missing()).containsExactly("gone");
            assertThat(result.inconclusive()).isEmpty();
        }

        /**
         * The other half of Issue 90's fix: re-checking must not let a
         * genuinely missing topic through. A broker that answers
         * {@code UnknownTopicOrPartition} on every re-check for the whole probe
         * window leaves every such topic confirmed missing, within the window's
         * bound, and {@code fail} still refuses to start naming each of them.
         */
        @Test
        @DisplayName("topics the broker reports unknown for the whole probe window stay missing, and fail still refuses startup naming each")
        void genuinelyMissingTopicsStillFailStartupAfterTheRecheckWindow() {
            var admin = adminReturning(Map.of(
                    "a", present("a"),
                    "gone.one", failed(new UnknownTopicOrPartitionException("no such topic")),
                    "gone.two.DLT", failed(new UnknownTopicOrPartitionException("no such topic"))));

            var started = System.nanoTime();
            var result = KafkaTopicVerifier.verify(admin, List.of("a", "gone.one", "gone.two.DLT"));
            var took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result.present()).containsExactly("a");
            assertThat(result.missing()).containsExactly("gone.one", "gone.two.DLT");
            assertThat(result.inconclusive()).isEmpty();
            assertThat(took).as("bounded by the probe window, plus scheduling slack")
                    .isLessThan(KafkaTopicVerifier.PROBE_TIMEOUT.plusSeconds(2));
            verify(admin, atLeast(2)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
            assertThatThrownBy(() -> KafkaTopicVerifier.enforce(TopicVerification.FAIL, result, BOOTSTRAP))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2 of 3 Kafka topic(s)")
                    .hasMessageContaining("kafka-topics.sh --bootstrap-server " + BOOTSTRAP + " --create --topic gone.one")
                    .hasMessageContaining("kafka-topics.sh --bootstrap-server " + BOOTSTRAP
                            + " --create --topic gone.two.DLT");
        }

        @Test
        @DisplayName("a probe that cannot run at all reports every topic inconclusive and never throws")
        void probeFailureIsInconclusive() {
            var admin = mock(Admin.class);
            when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class)))
                    .thenThrow(new IllegalStateException("admin client closed"));

            var result = KafkaTopicVerifier.verify(admin, List.of("a", "b"));

            assertThat(result.missing()).isEmpty();
            assertThat(result.inconclusive()).containsExactly("a", "b");
        }

        @Test
        @DisplayName("an empty requirement is trivially all-present")
        void emptyIsAllPresent() {
            var result = KafkaTopicVerifier.verify(mock(Admin.class), List.of());

            assertThat(result.allPresent()).isTrue();
        }
    }

    // ── enforce ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enforce")
    class Enforce {

        @Test
        @DisplayName("fail: one IllegalStateException naming every missing topic, with a kafka-topics.sh line each")
        void failThrowsNamingEveryMissingTopicWithCliLines() {
            var result = new KafkaTopicVerifier.Result(
                    List.of("maestro.admin.events"),
                    List.of("maestro.signals.orders.DLT", "payments.results.DLT"),
                    List.of());

            assertThatThrownBy(() -> KafkaTopicVerifier.enforce(TopicVerification.FAIL, result, BOOTSTRAP))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2 of 3 Kafka topic(s)")
                    .hasMessageContaining("maestro.signals.orders.DLT")
                    .hasMessageContaining("payments.results.DLT")
                    .hasMessageContaining("kafka-topics.sh --bootstrap-server " + BOOTSTRAP
                            + " --create --topic maestro.signals.orders.DLT")
                    .hasMessageContaining("kafka-topics.sh --bootstrap-server " + BOOTSTRAP
                            + " --create --topic payments.results.DLT")
                    .hasMessageContaining(KafkaTopicVerifier.PROPERTY + "=warn");
        }

        @Test
        @DisplayName("warn: the same message at WARN, and startup continues")
        void warnLogsAndContinues() {
            var result = new KafkaTopicVerifier.Result(List.of(), List.of("maestro.signals.orders.DLT"), List.of());

            assertThatCode(() -> KafkaTopicVerifier.enforce(TopicVerification.WARN, result, BOOTSTRAP))
                    .doesNotThrowAnyException();

            assertThat(warnMessages()).anySatisfy(m -> assertThat(m)
                    .contains("maestro.signals.orders.DLT")
                    .contains("kafka-topics.sh"));
        }

        @Test
        @DisplayName("fail: an inconclusive probe only WARNs — a broker blip must not become a restart loop")
        void inconclusiveNeverFails() {
            var result = KafkaTopicVerifier.Result.inconclusive(List.of("maestro.signals.orders"));

            assertThatCode(() -> KafkaTopicVerifier.enforce(TopicVerification.FAIL, result, BOOTSTRAP))
                    .doesNotThrowAnyException();

            assertThat(warnMessages()).anySatisfy(m -> assertThat(m)
                    .contains("Could not verify")
                    .contains("maestro.signals.orders")
                    .contains("not fatal on a missing topic"));
        }

        @Test
        @DisplayName("all present: INFO only, no WARN")
        void allPresentIsQuiet() {
            var result = new KafkaTopicVerifier.Result(List.of("a", "b"), List.of(), List.of());

            KafkaTopicVerifier.enforce(TopicVerification.FAIL, result, BOOTSTRAP);

            assertThat(warnMessages()).isEmpty();
            assertThat(logAppender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("Verified 2 Kafka topic(s)"));
        }
    }

    // ── fatalOnMissing ────────────────────────────────────────────────────

    @Nested
    @DisplayName("fatalOnMissing — the one rule both container builders follow")
    class FatalOnMissing {

        private final KafkaTopicVerifier.Result allPresent =
                new KafkaTopicVerifier.Result(List.of("a", "a.DLT"), List.of(), List.of());
        private final KafkaTopicVerifier.Result inconclusive =
                KafkaTopicVerifier.Result.inconclusive(List.of("a", "a.DLT"));
        private final KafkaTopicVerifier.Result partlyInconclusive =
                new KafkaTopicVerifier.Result(List.of("a"), List.of(), List.of("a.DLT"));

        @Test
        @DisplayName("fail + every topic confirmed present → containers are fatal on a missing topic")
        void failAndConfirmedPresent() {
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.FAIL, allPresent)).isTrue();
        }

        @Test
        @DisplayName("fail + inconclusive probe → NOT fatal: Spring Kafka would count the unreachable broker "
                + "as missing and fail start() right after the 'starting anyway' WARN")
        void failAndInconclusive() {
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.FAIL, inconclusive)).isFalse();
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.FAIL, partlyInconclusive)).isFalse();
        }

        @Test
        @DisplayName("warn and off are never fatal, whatever the probe said")
        void warnAndOffNeverFatal() {
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.WARN, allPresent)).isFalse();
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.OFF, allPresent)).isFalse();
            assertThat(KafkaTopicVerifier.fatalOnMissing(TopicVerification.WARN, inconclusive)).isFalse();
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static KafkaMessagingConfig config(String signalsTopic, boolean redelivery, TopicVerification mode) {
        return new KafkaMessagingConfig(signalsTopic, "maestro.admin.events", "maestro-orders",
                3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), ".DLT", redelivery, mode);
    }

    private static Admin adminReturning(Map<String, KafkaFuture<TopicDescription>> futures) {
        // DescribeTopicsResult's factories are package-private in kafka-clients
        // 4.x, so the result is a mock too — only topicNameValues() is read.
        var result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(new LinkedHashMap<>(futures));
        var admin = mock(Admin.class);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        return admin;
    }

    private static KafkaFuture<TopicDescription> present(String topic) {
        return KafkaFuture.completedFuture(new TopicDescription(topic, false, List.of()));
    }

    private static KafkaFuture<TopicDescription> failed(Throwable cause) {
        var future = new KafkaFutureImpl<TopicDescription>();
        future.completeExceptionally(cause);
        return future;
    }

    private static ch.qos.logback.classic.Logger logbackLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KafkaTopicVerifier.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> "WARN".equals(event.getLevel().toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
