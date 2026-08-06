package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 3: a missing or unreachable admin-events topic must cost no
 * meaningful workflow-thread time.
 *
 * <p>{@code KafkaTemplate.send} blocks synchronously inside {@code send()}
 * while it fetches metadata — up to {@code max.block.ms} — for a topic that
 * does not exist and cannot be auto-created. Before the fix, that call ran
 * inline inside {@code startWorkflow}: a missing admin topic in production
 * timed out every one of the six loan-origination E2E scenarios. This suite
 * proves {@code startWorkflow} returns promptly regardless, against a real
 * broker with a real, genuinely-missing topic — not a mock.
 *
 * <p>Auto-create is disabled on this suite's own, dedicated broker (never the
 * shared {@link KafkaSpringIntegrationSupport} one — this test needs a topic
 * that is guaranteed to stay missing) so the metadata fetch keeps retrying
 * rather than resolving instantly. {@code max.block.ms} is lowered from the
 * production default (60s) to a few seconds so a regression fails fast
 * instead of hanging the suite — the assertion bound is a small fraction of
 * even that reduced value.
 */
@Tag("integration")
@DisplayName("A missing admin-events topic costs no meaningful workflow-thread time")
class KafkaLifecycleEventLatencyIT extends PostgresIntegrationSupport {

    /** How long the producer is allowed to block fetching metadata before giving up. */
    private static final int MAX_BLOCK_MS = 5_000;

    /** startWorkflow must return well inside this bound even though the producer above may stall. */
    private static final Duration PROMPT_BOUND = Duration.ofSeconds(1);

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft()
                    // The topic under test must never spring into existence — that is the
                    // whole point of this suite. Without this, the broker's own auto-create
                    // would make the metadata fetch resolve instantly and the test would
                    // pass without ever exercising the off-thread publishing path.
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @BeforeAll
    static void startKafka() {
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    @DisplayName("startWorkflow returns promptly even though the admin-events topic does not exist")
    void startWorkflow_returnsPromptly_whenAdminTopicIsMissing() {
        var missingTopic = "it.lifecycle.missing." + UUID.randomUUID();
        var producerFactory = new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS));
        var kafkaTemplate = new KafkaTemplate<>(producerFactory);
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-lifecycle-latency",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        // This suite only publishes, so the redelivery policy is immaterial —
        // it is the production default.
        var redelivery = MaestroProperties.RedeliveryProperties.defaults();
        var messagingConfig = new KafkaMessagingConfig(
                null, null, missingTopic, "it-lifecycle-latency",
                redelivery.maxAttempts(), redelivery.initialInterval(), redelivery.multiplier(),
                redelivery.maxInterval(), redelivery.deadLetterSuffix(), redelivery.enabled());
        var messaging = new KafkaWorkflowMessaging(kafkaTemplate, consumerFactory, objectMapper, messagingConfig);

        try (var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("lifecycle-latency")
                .messaging(messaging)
                .build()) {
            harness.registerWorkflow(new KafkaTestWorkflows.ImmediateWorkflow());

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("lat");
            var start = System.nanoTime();
            var handle = harness.start(workflowId, KafkaTestWorkflows.ImmediateWorkflow.class, "seed");
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(elapsed.compareTo(PROMPT_BOUND) < 0,
                    "startWorkflow must not block on a missing admin-events topic (max.block.ms="
                            + MAX_BLOCK_MS + "ms), took " + elapsed);

            // The workflow itself must still run to completion — the missing topic
            // only affects the (best-effort, off-thread) lifecycle event.
            var status = handle.awaitTerminal(Duration.ofSeconds(10));
            assertEquals(WorkflowStatus.COMPLETED, status);
        } finally {
            producerFactory.destroy();
        }
    }
}
