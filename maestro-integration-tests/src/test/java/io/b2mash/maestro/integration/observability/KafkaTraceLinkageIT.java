package io.b2mash.maestro.integration.observability;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.OtelTracingFixture;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.spring.observe.TracingEngineObserver;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-service single-connected-trace assertion (observability design doc
 * §8.3): service A publishes a signal inside a span; service B's engine consumes
 * it off a real broker, resumes a workflow parked on that signal, and B's
 * resumed run segment lands in <b>A's</b> trace.
 *
 * <p>Everything real: a Testcontainers Kafka broker, a Testcontainers Postgres
 * store (so the trace context makes its durable hop through the
 * {@code maestro_workflow_signal.trace_context} column, not through a shared
 * thread), a real {@link io.b2mash.maestro.core.engine.WorkflowExecutor} on B,
 * and a <b>separate OpenTelemetry SDK per service</b> — B's exporter reporting
 * A's trace ID is then evidence of propagation, not of a shared tracer.
 *
 * <p>Deliberately one narrow assertion chain: the contract tests in
 * {@code maestro-messaging-kafka} and the starter carry the detail.
 */
@Tag("integration")
@DisplayName("A signal published by service A and consumed by service B yields one connected trace")
class KafkaTraceLinkageIT extends PostgresIntegrationSupport {

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")).withKraft();

    static {
        KAFKA.start();
    }

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private static final String SEGMENT_SPAN = "maestro.workflow.run";
    private static final String ACTIVITY_SPAN = "maestro.activity";

    @Test
    @DisplayName("B's resumed run segment carries A's trace ID, parented to A's publish span, "
            + "and the durable signal row is what carried it")
    void crossServiceSignalYieldsOneConnectedTrace() throws Exception {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var serviceB = "svc-b-" + suffix;
        var signalTopic = "maestro.signals." + serviceB;
        createTopics(signalTopic, "maestro.admin.events." + suffix);

        var recorder = new CountingActivities.Recorder();
        var parked = new CountDownLatch(1);

        try (var otelA = new OtelTracingFixture("service-a");
             var otelB = new OtelTracingFixture("service-b")) {

            var propagationA = new KafkaTracePropagation(otelA.tracer(), otelA.propagator());
            var propagationB = new KafkaTracePropagation(otelB.tracer(), otelB.propagator());

            var messagingA = messaging(suffix + "-a", propagationA);
            var messagingB = messaging(suffix + "-b", propagationB);

            var nodeB = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName(serviceB)
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otelB.tracer(), otelB.propagator()))
                    .build();
            nodeB.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

            // B's inbound signal channel — the production ingestion path:
            // KafkaWorkflowMessaging restores the remote context around this
            // handler, and deliverSignal runs synchronously on that same thread.
            messagingB.subscribeSignals(serviceB, message ->
                    nodeB.deliverSignal(message.workflowId(), message.signalName(),
                            message.payload() == null ? null
                                    : serializer.deserialize(message.payload(), String.class)));
            Thread.sleep(1000);

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("trace-linkage");
            var handle = nodeB.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

            // ── Service A publishes the signal inside its own span ──
            var publishSpan = otelA.tracer().nextSpan().name("service-a.approve").start();
            String publishTraceId;
            String publishSpanId;
            try (var ignored = otelA.tracer().withSpan(publishSpan)) {
                publishTraceId = publishSpan.context().traceId();
                publishSpanId = publishSpan.context().spanId();
                messagingA.publishSignal(serviceB, new SignalMessage(
                        workflowId, TestWorkflows.SignalWorkflow.SIGNAL,
                        objectMapper.valueToTree("approved")));
            } finally {
                publishSpan.end();
            }

            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));

            // ── The durable hop actually happened ──
            var traceContexts = signalTraceContexts(workflowId);
            assertEquals(1, traceContexts.size(), "exactly one signal row for this workflow");
            assertNotNull(traceContexts.getFirst(),
                    "the signal row must carry the traceparent the listener extracted");
            assertEquals(publishTraceId, traceContexts.getFirst().substring(3, 35),
                    "the persisted traceparent must name service A's trace");

            // ── The single connected trace ──
            var resumed = otelB.spansNamed(SEGMENT_SPAN).stream()
                    .filter(s -> s.getTraceId().equals(publishTraceId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no run segment on service B joined service A's trace " + publishTraceId
                                    + "; B's segments were in traces "
                                    + otelB.spansNamed(SEGMENT_SPAN).stream()
                                    .map(s -> s.getTraceId()).toList()));
            assertEquals(publishSpanId, resumed.getParentSpanId(),
                    "B's resumed segment must be parented to A's publish span");

            assertTrue(otelB.spansNamed(ACTIVITY_SPAN).stream()
                            .anyMatch(s -> s.getTraceId().equals(publishTraceId)),
                    "the work B did after the resume must be in the same trace as A's publish");

            messagingA.destroy();
            messagingB.destroy();
            nodeB.close();
        }
    }

    /**
     * Fix round 1, F1 — the invariant that outranks tracing: <b>never discard a
     * signal</b>.
     *
     * <p>The inbound {@code traceparent} is persisted verbatim on the signal
     * row's {@code VARCHAR(128)} column. Before validation was moved to
     * extraction, an over-long header made {@code saveSignal} throw, and because
     * {@code deliverSignal} runs inside the Kafka listener that failure meant the
     * record was never acked — bounded redelivery, then the dead-letter topic.
     * A workflow would wait forever for a signal that was thrown away because its
     * decorative metadata was too long. This test drives the real column.
     */
    @Test
    @DisplayName("a signal carrying an over-long traceparent is still delivered and consumed — "
            + "the workflow completes and the resumed segment is simply a fresh root")
    void oversizedTraceparentNeverDiscardsTheSignal() throws Exception {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var serviceB = "svc-b-bad-" + suffix;
        var signalTopic = "maestro.signals." + serviceB;
        createTopics(signalTopic, "maestro.admin.events." + suffix);

        var recorder = new CountingActivities.Recorder();
        var parked = new CountDownLatch(1);

        try (var otelB = new OtelTracingFixture("service-b")) {
            var messagingB = messaging(suffix + "-b",
                    new KafkaTracePropagation(otelB.tracer(), otelB.propagator()));

            var nodeB = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName(serviceB)
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otelB.tracer(), otelB.propagator()))
                    .build();
            nodeB.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

            messagingB.subscribeSignals(serviceB, message ->
                    nodeB.deliverSignal(message.workflowId(), message.signalName(),
                            message.payload() == null ? null
                                    : serializer.deserialize(message.payload(), String.class)));
            Thread.sleep(1000);

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("trace-oversize");
            var handle = nodeB.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

            // 512 characters — four times the trace_context column width.
            publishRawSignal(signalTopic, workflowId,
                    new SignalMessage(workflowId, TestWorkflows.SignalWorkflow.SIGNAL,
                            objectMapper.valueToTree("approved")),
                    "00-" + "a".repeat(509));

            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND),
                    "the signal must still wake the workflow — a bad trace header may never "
                            + "cost a signal its delivery");

            var traceContexts = signalTraceContexts(workflowId);
            assertEquals(1, traceContexts.size(), "the signal row was written");
            assertNull(traceContexts.getFirst(),
                    "the over-long value must have been dropped at extraction, never persisted");

            assertTrue(otelB.spansNamed(SEGMENT_SPAN).stream()
                            .noneMatch(sp -> sp.getParentSpanContext().isRemote()),
                    "with no usable remote context the resumed segment degrades to a fresh root");

            messagingB.destroy();
            nodeB.close();
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private KafkaWorkflowMessaging messaging(String group, KafkaTracePropagation propagation) {
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all")));
        ConsumerFactory<String, byte[]> consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        var config = new KafkaMessagingConfig(
                null, null, "maestro.admin.events." + group, "trace-linkage-" + group,
                2, Duration.ofMillis(50), 2.0, Duration.ofMillis(200), ".DLT");
        return new KafkaWorkflowMessaging(template, consumers, objectMapper, config, propagation);
    }

    /** Publishes with an arbitrary, deliberately unvalidated traceparent header. */
    private void publishRawSignal(String topic, String key, SignalMessage message,
                                  String traceparent) throws Exception {
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all")));
        var record = new ProducerRecord<String, byte[]>(
                topic, key, objectMapper.writeValueAsBytes(message));
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        template.send(record).get();
        template.destroy();
    }

    private static void createTopics(String... topics) throws ExecutionException, InterruptedException {
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(Arrays.stream(topics)
                    .map(name -> new NewTopic(name, 1, (short) 1))
                    .toList()).all().get();
        }
    }

    /** Reads the trace_context column straight from Postgres — the durable hop. */
    private List<String> signalTraceContexts(String workflowId) throws SQLException {
        var values = new java.util.ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT trace_context FROM maestro_workflow_signal WHERE workflow_id = ?")) {
            statement.setString(1, workflowId);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString("trace_context"));
                }
            }
        }
        return values;
    }
}
