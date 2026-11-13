package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDto;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostDtoSender;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.HostSenderConfiguration;
import io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.PropertiesConfiguration;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.messaging.kafka.listener.MaestroSignalListenerBeanPostProcessor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On a real broker with auto-create off ({@link KafkaTestSupport}), one
 * application holds Spring Boot's Kafka beans for its own traffic and
 * Maestro's for the engine's (WP7, 3.4), and neither changes the other's bytes:
 * <ul>
 *   <li>the host's {@code KafkaTemplate<String, HostDto>} is Boot's and writes
 *       JSON ({@code spring.kafka.producer.value-serializer}); the host's plain
 *       {@code @KafkaListener} reads it back as a {@code HostDto} through Boot's
 *       consumer factory ({@code spring.kafka.consumer.value-deserializer});</li>
 *   <li>the engine's signal publish writes exactly the bytes its
 *       {@link ObjectMapper} produces for the envelope, under the workflow id
 *       key and the message-id header;</li>
 *   <li>a {@code @MaestroSignalListener} receives raw bytes through Maestro's
 *       consumer factory and hands the engine the event its own mapper reads
 *       from them;</li>
 *   <li>a poison record for a {@code @MaestroSignalListener} is dead-lettered
 *       with its original bytes, through Maestro's {@code byte[]} template, not
 *       Boot's JSON one.</li>
 * </ul>
 *
 * <p>Every topic is pre-created, {@code .DLT} companions included, and the
 * startup verification stays at its default {@code fail}, so a topic this test
 * forgot would fail the context. The {@code @MaestroSignalListener} topics are
 * compile-time constants (the post-processor reads the attribute verbatim), so
 * they carry no per-run suffix; the broker is this class's own. No fixed
 * sleeps: each consumer is awaited until it holds its partition before
 * anything is produced, and every read is a bounded poll.
 */
@DisplayName("Boot's and Maestro's Kafka beans side by side on a real broker")
class KafkaCoexistenceRoundTripTest extends KafkaTestSupport {

    static final String GOOD = "coexist.roundtrip.good";
    static final String POISON = "coexist.roundtrip.poison";
    static final Duration AWAIT = Duration.ofSeconds(30);

    private String svc;
    private String hostTopic;
    private WorkflowExecutor executor;

    @BeforeEach
    void createTopicsAndExecutor() throws Exception {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        svc = "coexist-" + suffix;
        hostTopic = "coexist.host." + suffix;
        createTopics(hostTopic, "maestro.signals." + svc, "maestro.signals." + svc + ".DLT",
                GOOD, GOOD + ".DLT", POISON, POISON + ".DLT");
        executor = mock(WorkflowExecutor.class);
        when(executor.deliverSignal(anyString(), eq("poison"), any(), any()))
                .thenThrow(new IllegalStateException("poison"));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaMessagingAutoConfiguration.class,
                        KafkaAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class, HostSenderConfiguration.class,
                        HostListenerConfiguration.class, SignalListenerConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WorkflowExecutor.class, () -> executor)
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "maestro.service-name=" + svc,
                        "spring.kafka.producer.value-serializer="
                                + "org.springframework.kafka.support.serializer.JacksonJsonSerializer",
                        "spring.kafka.consumer.value-deserializer="
                                + "org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                        "spring.kafka.consumer.properties.spring.json.trusted.packages="
                                + HostDto.class.getPackageName(),
                        // Explicit, so a change of its default does not change this test.
                        "maestro.admin.events.enabled=false",
                        "maestro.messaging.redelivery.max-attempts=1",
                        "coexist.host-topic=" + hostTopic);
        // maestro.messaging.topics.verify-on-startup stays at its default, fail.
    }

    @Test
    @DisplayName("on a real broker: the host's template writes JSON, the engine writes its own bytes, a dead-lettered signal keeps its bytes")
    void bootsTemplateServesTheHostAndMaestrosServesTheEngine() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();

            // Every consumer holds its partition before anything is produced.
            var hostContainer = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("coexist-host");
            List<MessageListenerContainer> maestroContainers = ReflectionTestUtils.invokeMethod(
                    ctx.getBean(MaestroSignalListenerBeanPostProcessor.class), "containersForTesting");
            assertThat(maestroContainers).hasSize(2);
            awaitAssignment(hostContainer, "the host @KafkaListener");
            maestroContainers.forEach(c -> awaitAssignment(c, "a @MaestroSignalListener"));

            // The host's own traffic: Boot's template writes JSON, Boot's consumer reads a HostDto.
            ctx.getBean(HostDtoSender.class).template().send(hostTopic, "k", new HostDto("widget")).get(10, SECONDS);
            assertThat(new String(readOne(hostTopic).value(), UTF_8)).isEqualTo("{\"name\":\"widget\"}");
            assertThat(ctx.getBean(HostListener.class).received.poll(AWAIT.toSeconds(), SECONDS))
                    .as("the host @KafkaListener received the host's own type")
                    .isEqualTo(new HostDto("widget"));

            // The engine's own publish: exactly its mapper's bytes, key and message-id header.
            var mapper = ctx.getBean(ObjectMapper.class);
            var message = new SignalMessage("wf-1", "go", mapper.valueToTree(Map.of("n", 1)), "client:coexist-1");
            ctx.getBean(KafkaWorkflowMessaging.class).publishSignal(svc, message);
            var engineRecord = readOne("maestro.signals." + svc);
            assertThat(engineRecord.value()).isEqualTo(mapper.writeValueAsBytes(message));   // no tracing in this context
            assertThat(engineRecord.key()).isEqualTo("wf-1");
            assertThat(MaestroSignalHeaders.messageId(engineRecord.headers())).isEqualTo("client:coexist-1");

            // A @MaestroSignalListener receives raw bytes; Maestro's mapper reads the event from them.
            var good = mapper.writeValueAsBytes(new GoodEvent("7", 42));
            kafkaTemplate.send(new ProducerRecord<>(GOOD, "g", good)).get(10, SECONDS);   // KafkaTestSupport's byte[] template
            var payload = ArgumentCaptor.forClass(Object.class);
            var messageId = ArgumentCaptor.forClass(String.class);
            verify(executor, timeout(AWAIT.toMillis()))
                    .deliverSignal(eq("wf-7"), eq("good"), payload.capture(), messageId.capture());
            assertThat(payload.getValue()).isEqualTo(new GoodEvent("7", 42));
            assertThat(messageId.getValue()).startsWith("kafka:");

            // A poison record is dead-lettered with its original bytes.
            var poison = "\"boom\"".getBytes(UTF_8);
            kafkaTemplate.send(new ProducerRecord<>(POISON, "p", poison)).get(10, SECONDS);
            var deadLetter = readOne(POISON + ".DLT");
            assertThat(deadLetter.value()).isEqualTo(poison);
            assertThat(deadLetter.key()).isEqualTo("p");
        });
    }

    private static void awaitAssignment(MessageListenerContainer container, String what) {
        await().atMost(AWAIT).untilAsserted(() -> assertThat(container.getAssignedPartitions())
                .as("%s holds its partition", what)
                .hasSize(1));
    }

    /**
     * The first record on partition 0 of {@code topic}, read from the beginning
     * by a fresh {@link KafkaTestSupport} {@code byte[]} consumer, polling for at
     * most {@link #AWAIT}.
     */
    private ConsumerRecord<String, byte[]> readOne(String topic) {
        try (var consumer = consumerFactory.createConsumer()) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var deadline = System.nanoTime() + AWAIT.toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }
        throw new AssertionError("no record on " + topic + " within " + AWAIT.toSeconds() + " s");
    }

    /** The payload a {@code @MaestroSignalListener} reads from raw bytes. */
    public record GoodEvent(String id, int n) {}

    /** A host's plain {@code @KafkaListener} for its own type. */
    public static class HostListener {
        final BlockingQueue<HostDto> received = new LinkedBlockingQueue<>();

        @KafkaListener(id = "coexist-host", topics = "${coexist.host-topic}", groupId = "coexist-host-group",
                properties = "auto.offset.reset=earliest")
        public void onDto(HostDto dto) {
            received.add(dto);
        }
    }

    /** Two {@code @MaestroSignalListener}s: one that delivers, one whose delivery fails. */
    public static class SignalListeners {
        @MaestroSignalListener(topic = GOOD, signalName = "good")
        public SignalRouting onGood(GoodEvent event) {
            return SignalRouting.builder().workflowId("wf-" + event.id()).payload(event).build();
        }

        @MaestroSignalListener(topic = POISON, signalName = "poison")
        public SignalRouting onPoison(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostListenerConfiguration {
        @Bean
        HostListener hostListener() {
            return new HostListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SignalListenerConfiguration {
        @Bean
        SignalListeners signalListeners() {
            return new SignalListeners();
        }
    }
}
