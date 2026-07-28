package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headline P1 suite: a business event published to a domain topic on a real
 * broker is routed by a real {@code @MaestroSignalListener} bean, persisted as a
 * signal in a real Postgres store, and wakes a workflow parked on
 * {@code awaitSignal} until it completes.
 *
 * <p>Until this suite existed the round trip was verified only by the manual
 * loan-origination E2E script, so a regression anywhere along
 * {@code Kafka → BeanPostProcessor → deliverSignal → store → unpark} could ship
 * green.
 *
 * <p>Nothing here is stubbed: the listener container, the consumer group, the
 * serialization and the signal table are all the production path. Assertions
 * read persisted rows rather than in-memory state.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class,
                KafkaSignalListenerRoundTripIT.RouterConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaSignalListenerRoundTripIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaSignalListenerRoundTripIT.ADMIN_TOPIC,
                // The periodic recovery poller would race per-test truncation
                // with instances left by other suites; startup recovery still runs.
                "maestro.recovery.enabled=false"
        })
@Tag("integration")
@DisplayName("A Kafka event routed by @MaestroSignalListener wakes a parked workflow")
class KafkaSignalListenerRoundTripIT extends KafkaSpringIntegrationSupport {

    /** Service name — also the consumer-group and signal-topic discriminator. */
    static final String SERVICE = "kafka-roundtrip";

    /** The domain topic the listener under test consumes. */
    static final String EVENT_TOPIC = "it.roundtrip.approvals";

    static final String ADMIN_TOPIC = "it.roundtrip.admin";

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    /**
     * Pre-creates every topic this context touches. Runs before the Spring
     * context is loaded, because JUnit creates the test instance — which is what
     * triggers context loading — only after {@code @BeforeAll}.
     *
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createRoundTripTopics() throws ExecutionException, InterruptedException {
        createTopics(EVENT_TOPIC, ADMIN_TOPIC, "maestro.signals." + SERVICE);
    }

    @Test
    @DisplayName("an event published while the workflow is parked wakes it and completes it")
    void publishedEvent_wakesTheParkedWorkflowAndCompletesIt() throws Exception {
        var workflowId = "roundtrip-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");

        // WAITING_SIGNAL is persisted only on the park path, so reaching it
        // proves the wake below travelled through park/unpark rather than
        // through the already-arrived shortcut.
        var parked = awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        publish(EVENT_TOPIC, workflowId, new ApprovalEvent(workflowId, "approved"));

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:approved", serializer.deserialize(completed.output(), String.class));
        assertEquals(parked.id(), completed.id(), "the workflow must not have been restarted");

        // The signal is durable before it is delivered: exactly one row, bound
        // to the instance and consumed by the await.
        var rows = signalRows(workflowId);
        assertEquals(1, rows.size(), "one event must produce exactly one signal row");
        assertEquals(KafkaTestWorkflows.APPROVAL_SIGNAL, rows.getFirst().signalName());
        assertEquals(completed.id(), rows.getFirst().workflowInstanceId());
        assertTrue(rows.getFirst().consumed());

        var received = store.getEvents(completed.id()).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                .toList();
        assertEquals(1, received.size(), "expected exactly one SIGNAL_RECEIVED event");
        assertEquals("approved", serializer.deserialize(received.getFirst().payload(), String.class));
    }

    @Test
    @DisplayName("an event routed before the workflow exists is persisted as an orphan and adopted on start")
    void eventBeforeWorkflowStart_isPersistedAndAdopted() throws Exception {
        var workflowId = "roundtrip-orphan-" + UUID.randomUUID().toString().substring(0, 8);

        publish(EVENT_TOPIC, workflowId, new ApprovalEvent(workflowId, "early"));

        // Pre-delivery: the listener persisted a row with no instance to point at.
        await().atMost(BOUND).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            var rows = signalRows(workflowId);
            assertEquals(1, rows.size(), "the routed signal must be persisted before any workflow exists");
            assertNull(rows.getFirst().workflowInstanceId(),
                    "a signal for a non-existent workflow must persist with a null instance ID");
            assertFalse(rows.getFirst().consumed());
        });
        assertTrue(store.getInstance(workflowId).isEmpty(), "no instance should exist yet");

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:early", serializer.deserialize(completed.output(), String.class));

        var adopted = signalRows(workflowId).getFirst();
        assertEquals(completed.id(), adopted.workflowInstanceId(),
                "adoptOrphanedSignals must link the pre-delivered signal to the new instance");
        assertTrue(adopted.consumed());
    }

    // ── test-local fixtures ─────────────────────────────────────────────

    /**
     * Registers the router as a bean without {@code @Component}: a component-scanned
     * router would join every P1 context, and each suite must own its listener.
     *
     * <p>Listed explicitly in {@code @SpringBootTest(classes = ...)} — Boot only
     * auto-detects a nested {@code @TestConfiguration} when the test declares no
     * configuration classes of its own, and this suite declares the application
     * class. {@code @TestConfiguration} still matters: it is excluded from the
     * application's component scan, so this router joins only this suite's
     * context.
     */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router under test */
        @Bean
        ApprovalRouter approvalRouter() {
            return new ApprovalRouter();
        }
    }

    /**
     * A production-shaped router: it maps a domain event onto a workflow ID and
     * a signal payload, and nothing else.
     *
     * <h2>Thread Safety</h2>
     * <p>Stateless; invoked on the listener container's consumer thread.
     */
    public static class ApprovalRouter {

        /**
         * @param event the domain event consumed from {@link #EVENT_TOPIC}
         * @return where the signal goes and what it carries
         */
        @MaestroSignalListener(topic = EVENT_TOPIC, signalName = KafkaTestWorkflows.APPROVAL_SIGNAL)
        public SignalRouting route(ApprovalEvent event) {
            return SignalRouting.builder()
                    .workflowId(event.workflowId())
                    .payload(event.decision())
                    .build();
        }
    }
}
