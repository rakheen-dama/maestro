package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * At-least-once delivery, end to end: the same business event arrives twice and
 * the workflow must still run once.
 *
 * <p>Duplicate tolerance was previously asserted only at the unit level against
 * an in-memory store. Here the duplicate is a genuine second Kafka record,
 * routed by a real listener into a real signal table, so the tolerated extra row
 * and the consumed-flag CAS are both observed on Postgres.
 *
 * <p>The contract being pinned: an extra row is <b>tolerated, never discarded</b>
 * — each await consumes exactly one row and surplus rows stay unconsumed.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class,
                KafkaDuplicateDeliveryIT.RouterConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaDuplicateDeliveryIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaDuplicateDeliveryIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false"
        })
@Tag("integration")
@DisplayName("A duplicated Kafka event runs the workflow exactly once")
class KafkaDuplicateDeliveryIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-duplicate";

    static final String EVENT_TOPIC = "it.duplicate.approvals";

    static final String ADMIN_TOPIC = "it.duplicate.admin";

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createDuplicateTopics() throws ExecutionException, InterruptedException {
        createTopics(EVENT_TOPIC, ADMIN_TOPIC, "maestro.signals." + SERVICE);
    }

    @Test
    @DisplayName("the same event delivered twice is consumed once and leaves one surplus row")
    void duplicateEvent_isConsumedOnceAndCompletesTheWorkflowOnce() throws Exception {
        var workflowId = "duplicate-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        var event = new ApprovalEvent(workflowId, "approved");
        publish(EVENT_TOPIC, workflowId, event);
        publish(EVENT_TOPIC, workflowId, event);

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:approved", serializer.deserialize(completed.output(), String.class));

        // Both deliveries are durable — the engine never drops a signal — but
        // only one is claimed.
        await().atMost(BOUND).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals(2, signalRows(workflowId).size(),
                        "both deliveries must be persisted; duplicates are tolerated, not discarded"));

        var rows = signalRows(workflowId);
        assertEquals(1, rows.stream().filter(SignalRow::consumed).count(),
                "exactly one row may be consumed");
        assertTrue(rows.stream().allMatch(r -> completed.id().equals(r.workflowInstanceId())),
                "both rows belong to the single instance");

        // One consume means one replay-visible signal event…
        var received = store.getEvents(completed.id()).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                .toList();
        assertEquals(1, received.size(), "a duplicate must not append a second SIGNAL_RECEIVED event");

        // …and one completion, observed from outside the engine.
        assertEquals(1, lifecycleEvents(workflowId, LifecycleEventType.WORKFLOW_COMPLETED).size(),
                "the workflow must complete exactly once");
    }

    @Test
    @DisplayName("duplicates that arrive before the workflow exists are both adopted and one is consumed")
    void duplicateEventBeforeStart_isAdoptedAndConsumedOnce() throws Exception {
        var workflowId = "duplicate-orphan-" + UUID.randomUUID().toString().substring(0, 8);

        var event = new ApprovalEvent(workflowId, "early");
        publish(EVENT_TOPIC, workflowId, event);
        publish(EVENT_TOPIC, workflowId, event);

        await().atMost(BOUND).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals(2, signalRows(workflowId).size(),
                        "both pre-delivered signals must be persisted as orphans"));

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:early", serializer.deserialize(completed.output(), String.class));

        var rows = signalRows(workflowId);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> completed.id().equals(r.workflowInstanceId())),
                "adoption must claim every orphaned row, not just the first");
        assertEquals(1, rows.stream().filter(SignalRow::consumed).count(),
                "exactly one row may be consumed");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Reads the admin topic from the beginning and keeps one workflow's events
     * of one type.
     *
     * @param workflowId the workflow to filter on
     * @param type       the lifecycle event type to keep
     * @return the matching events
     */
    private List<WorkflowLifecycleEvent> lifecycleEvents(String workflowId, LifecycleEventType type) {
        try (var recorder = recorderFor(ADMIN_TOPIC)) {
            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertTrue(recorder.messages(WorkflowLifecycleEvent.class).stream()
                                    .anyMatch(e -> e.workflowId().equals(workflowId) && e.eventType() == type),
                            "no " + type + " event for " + workflowId + " on " + ADMIN_TOPIC));
            return recorder.messages(WorkflowLifecycleEvent.class).stream()
                    .filter(e -> e.workflowId().equals(workflowId) && e.eventType() == type)
                    .toList();
        }
    }

    // ── test-local fixtures ─────────────────────────────────────────────

    /** Registers this suite's router; see {@code KafkaSignalListenerRoundTripIT}. */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router under test */
        @Bean
        DuplicateRouter duplicateRouter() {
            return new DuplicateRouter();
        }
    }

    /**
     * Routes every delivery — including a redelivery — to the same workflow and
     * signal, exactly as a production router would.
     *
     * <h2>Thread Safety</h2>
     * <p>Stateless; invoked on the listener container's consumer thread.
     */
    public static class DuplicateRouter {

        /**
         * @param event the domain event
         * @return the signal routing for that event
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
