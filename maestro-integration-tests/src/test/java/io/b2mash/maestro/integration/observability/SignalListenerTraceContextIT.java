package io.b2mash.maestro.integration.observability;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.OtelTracingFixture;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.messaging.kafka.listener.MaestroSignalListenerBeanPostProcessor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.config.MaestroProperties;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Issue 23 done-when (b): an inbound {@code traceparent} header on a
 * {@code @MaestroSignalListener} topic is persisted into the signal row's
 * {@code trace_context} — over a real broker, through the real
 * annotation-driven listener path ({@code MaestroSignalListenerBeanPostProcessor}),
 * onto the real Postgres column.
 *
 * <p>This is deliberately a different call site from {@link KafkaTraceLinkageIT}
 * in this package: that suite proves the cross-service single-connected-trace
 * story for the {@code WorkflowMessaging} SPI's own {@code subscribeSignals}
 * path. A {@code @MaestroSignalListener} bean is routed by a separate
 * component — {@code MaestroSignalListenerBeanPostProcessor} — which wraps its
 * {@code handleMessage} call in {@code KafkaTracePropagation
 * .runWithExtractedContext} at its own call site (fix commit 472e87d, Issue 23
 * part 2). {@code MaestroSignalListenerContainerConfigTest} in
 * {@code maestro-messaging-kafka} pins the same wrapping with a mocked
 * {@code KafkaTracePropagation} and no broker; this is the real-infra
 * counterpart the module's build contract requires for a must-work path.
 *
 * <p>No workflow needs to run for this pin: {@code SignalManager.deliverSignal}
 * persists a signal row — instance-less if none exists yet — reading
 * {@code TraceContextHolder.current()} at write time regardless of whether a
 * workflow is parked on it. That is exactly the hop under test, so the fixture
 * stays minimal: a real {@link WorkflowExecutor} over the real Postgres store,
 * wired only far enough to receive the {@code deliverSignal} call, activated
 * through the real Spring auto-configuration chain
 * ({@link KafkaMessagingAutoConfiguration}) against a real Testcontainers
 * broker — mirroring {@code KafkaSignalListenerRoundTripIT}'s use of the real
 * chain and {@link KafkaTraceLinkageIT}'s use of {@link OtelTracingFixture} for
 * a working {@code Tracer}/{@code Propagator} pair.
 */
@Tag("integration")
@DisplayName("An inbound traceparent header on a @MaestroSignalListener topic reaches the signal row's trace_context")
class SignalListenerTraceContextIT extends PostgresIntegrationSupport {

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")).withKraft();

    static {
        KAFKA.start();
    }

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final String TOPIC = "it.signal-trace-context.events";
    private static final String SIGNAL_NAME = "trace.probe";
    private static final String SERVICE = "signal-trace-context-it";

    @Test
    @DisplayName("a valid W3C traceparent on the routed event survives BeanPostProcessor -> deliverSignal -> "
            + "saveSignal and lands on the signal row carrying the same trace id")
    void inboundTraceparentIsPersistedOnTheSignalRow() throws Exception {
        createTopics(TOPIC);

        var workflowId = "trace-probe-" + UUID.randomUUID().toString().substring(0, 8);
        var traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        try (var otel = new OtelTracingFixture("event-source");
             var harness = MaestroEngineHarness.builder(store, objectMapper)
                     .serviceName(SERVICE)
                     .build()) {

            var runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
                    .withUserConfiguration(PropertiesConfiguration.class, RouterConfiguration.class)
                    .withBean(WorkflowExecutor.class, harness::executor)
                    .withBean(ObjectMapper.class, () -> objectMapper)
                    .withBean(Tracer.class, otel::tracer)
                    .withBean(Propagator.class, otel::propagator)
                    .withPropertyValues(
                            "maestro.service-name=" + SERVICE,
                            "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());

            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                // Sanity: the listener actually activated (a container was
                // created and started) rather than the router silently never
                // registering — containersForTesting() itself is package-private
                // to maestro-messaging-kafka, so this only confirms the bean exists.
                assertThat(ctx.getBean(MaestroSignalListenerBeanPostProcessor.class)).isNotNull();

                // Let the listener container get its partition assignment before
                // publishing — the one Thread.sleep this module's timing rules
                // permit (SPEC.md), mirroring KafkaTraceLinkageIT.
                Thread.sleep(1000);

                publishWithTraceparent(workflowId, traceId);

                // First: await the row's existence — a plain boolean poll, so a
                // hang fails as a timeout on "row never appeared", not as an
                // ambiguous ConditionTimeout wrapping the content check below.
                await().atMost(BOUND).pollInterval(Duration.ofMillis(200))
                        .until(() -> !signalTraceContexts(workflowId).isEmpty());

                // Then: assert on the collected value itself, outside the await —
                // a failure here reads as "expected: <...4bf92f...> but was: <null>",
                // not a bare ConditionTimeoutException.
                var stored = signalTraceContexts(workflowId).getFirst();
                assertThat(stored)
                        .as("trace_context on the persisted signal row for workflow " + workflowId)
                        .isNotNull()
                        .contains(traceId);
            });
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private static void createTopics(String... topics) throws ExecutionException, InterruptedException {
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(Arrays.stream(topics)
                    .map(name -> new NewTopic(name, 1, (short) 1))
                    .toList()).all().get();
        }
    }

    /** Publishes directly, with a raw {@code traceparent} header — never through the engine's own producer. */
    private void publishWithTraceparent(String workflowId, String traceId) throws Exception {
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all")));
        try {
            var record = new ProducerRecord<String, byte[]>(
                    TOPIC, workflowId, objectMapper.writeValueAsBytes(workflowId));
            record.headers().add("traceparent",
                    ("00-" + traceId + "-00f067aa0ba902b7-01").getBytes(StandardCharsets.UTF_8));
            template.send(record).get(10, TimeUnit.SECONDS);
        } finally {
            template.destroy();
        }
    }

    /** Reads the trace_context column straight from Postgres — the durable hop. */
    private List<String> signalTraceContexts(String workflowId) throws SQLException {
        var values = new ArrayList<String>();
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

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }

    /**
     * Registers the router as a bean without {@code @Component} — this suite
     * owns its listener rather than joining a component scan, mirroring
     * {@code KafkaSignalListenerRoundTripIT.RouterConfiguration}.
     */
    @Configuration(proxyBeanMethods = false)
    static class RouterConfiguration {

        /** @return the router under test */
        @Bean
        ProbeRouter probeRouter() {
            return new ProbeRouter();
        }
    }

    /**
     * A minimal production-shaped router: the event body is just the workflow
     * ID as a JSON string, because this suite's assertion is entirely about the
     * trace context the listener extracts from the record's headers — not
     * about routing a richer payload.
     *
     * <h2>Thread Safety</h2>
     * <p>Stateless; invoked on the listener container's consumer thread.
     */
    public static class ProbeRouter {

        /**
         * @param workflowId the workflow ID, deserialized straight from the record value
         * @return where the signal goes
         */
        @MaestroSignalListener(topic = TOPIC, signalName = SIGNAL_NAME)
        public SignalRouting route(String workflowId) {
            return SignalRouting.builder().workflowId(workflowId).build();
        }
    }
}
