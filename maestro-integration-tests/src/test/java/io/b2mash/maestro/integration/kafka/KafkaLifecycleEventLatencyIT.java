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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * meaningful workflow-thread time — not on {@code startWorkflow}, and not on
 * any activity, timer, signal or compensation step either.
 *
 * <p>{@code KafkaTemplate.send} blocks synchronously inside {@code send()}
 * while it fetches metadata — up to {@code max.block.ms} — for a topic that
 * does not exist and cannot be auto-created. Before the first fix, that call
 * ran inline inside {@code startWorkflow}: a missing admin topic in production
 * timed out every one of the six loan-origination E2E scenarios. Before the
 * second (Feature 3, {@code LifecycleEventSink}), it still ran inline for every
 * {@code ACTIVITY_*}, {@code TIMER_*}, {@code SIGNAL_RECEIVED} and
 * {@code COMPENSATION_*} event: a ten-activity workflow paid
 * {@code 20 × max.block.ms}. This suite proves both paths against a real
 * broker with a real, genuinely-missing topic — not a mock.
 *
 * <p>Auto-create is disabled on this suite's own, dedicated broker (never the
 * shared {@link KafkaSpringIntegrationSupport} one — this test needs a topic
 * that is guaranteed to stay missing) so the metadata fetch keeps retrying
 * rather than resolving instantly. {@code max.block.ms} is lowered from
 * Kafka's default (60s; Maestro's auto-configured default is 10s) to
 * {@value #MAX_BLOCK_MS}ms so a regression fails fast instead of hanging the
 * suite — the assertion bound is still a fraction of a <em>single</em> block,
 * and the ten-activity workflow would need twenty of them.
 */
@Tag("integration")
@DisplayName("A missing admin-events topic costs no meaningful workflow-thread time")
class KafkaLifecycleEventLatencyIT extends PostgresIntegrationSupport {

    private static final Logger log = LoggerFactory.getLogger(KafkaLifecycleEventLatencyIT.class);

    /** How long the producer is allowed to block fetching metadata before giving up. */
    private static final int MAX_BLOCK_MS = 2_000;

    /**
     * startWorkflow must return — and the ten-activity workflow must complete —
     * well inside this bound even though the producer above may stall on
     * every one of its publishes.
     */
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
        var producerFactory = producerFactory();
        var messaging = messagingPublishingTo("it.lifecycle.missing." + UUID.randomUUID(), producerFactory);

        try (var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("lifecycle-latency")
                .messaging(messaging)
                .build()) {
            harness.registerWorkflow(KafkaTestWorkflows.ImmediateWorkflow.class);

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

    @Test
    @DisplayName("a ten-activity workflow completes in under a second even though every one of its "
            + "22 lifecycle publishes would block for max.block.ms")
    void tenActivityWorkflow_completesPromptly_whenAdminTopicIsMissing() {
        var producerFactory = producerFactory();
        var messaging = messagingPublishingTo("it.lifecycle.missing." + UUID.randomUUID(), producerFactory);

        try (var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("lifecycle-latency-steps")
                .messaging(messaging)
                .build()) {
            harness.registerActivities(KafkaTestWorkflows.TenStepActivities.class,
                            new KafkaTestWorkflows.TenStepActivitiesImpl())
                    .registerWorkflow(KafkaTestWorkflows.TenStepWorkflow.class);

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("lat-steps");
            var start = System.nanoTime();
            var handle = harness.start(workflowId, KafkaTestWorkflows.TenStepWorkflow.class, "seed");
            // Bounded well above PROMPT_BOUND so a regression fails on the
            // assertion below with the measured time, not on an opaque timeout —
            // but far below the 20 × max.block.ms (40s) the inline path would cost.
            var status = handle.awaitTerminal(Duration.ofSeconds(15));
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            log.info("[latency] {} activities with a missing admin-events topic (max.block.ms={}): "
                    + "start → COMPLETED in {} ms", KafkaTestWorkflows.TenStepWorkflow.STEPS, MAX_BLOCK_MS,
                    elapsed.toMillis());
            assertEquals(WorkflowStatus.COMPLETED, status);
            assertTrue(elapsed.compareTo(PROMPT_BOUND) < 0,
                    "a workflow's activity lifecycle events must never block its thread on Kafka "
                            + "(20 ACTIVITY_* publishes × max.block.ms=" + MAX_BLOCK_MS + "ms would be "
                            + Duration.ofMillis(20L * MAX_BLOCK_MS) + "), took " + elapsed);
        } finally {
            producerFactory.destroy();
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static DefaultKafkaProducerFactory<String, byte[]> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS));
    }

    /**
     * A real {@link KafkaWorkflowMessaging} whose admin-events topic is
     * {@code missingTopic} — which nothing ever creates on this broker.
     */
    private KafkaWorkflowMessaging messagingPublishingTo(
            String missingTopic, DefaultKafkaProducerFactory<String, byte[]> producerFactory) {
        var kafkaTemplate = new KafkaTemplate<>(producerFactory);
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-lifecycle-latency",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        // This suite only publishes, so the redelivery policy is immaterial —
        // it is the production default. The 9-argument constructor is the
        // compatibility form; verify-on-startup is irrelevant to a directly
        // constructed adapter (verification runs from the Spring post-processor).
        var redelivery = MaestroProperties.RedeliveryProperties.defaults();
        var messagingConfig = new KafkaMessagingConfig(
                null, missingTopic, "it-lifecycle-latency",
                redelivery.maxAttempts(), redelivery.initialInterval(), redelivery.multiplier(),
                redelivery.maxInterval(), redelivery.deadLetterSuffix(), redelivery.enabled());
        return new KafkaWorkflowMessaging(kafkaTemplate, consumerFactory, objectMapper, messagingConfig);
    }
}
