package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle events reaching the admin topic on a real broker, with the shape the
 * dashboard consumes.
 *
 * <p>{@code KafkaWorkflowMessagingTest} proves the adapter can publish a
 * hand-built event; this suite proves the <b>engine</b> publishes the right
 * events, with the right identity fields, for a workflow that actually ran.
 *
 * <h2>Which property names the topic</h2>
 * <p>The topic comes from {@code maestro.messaging.topics.admin-events}, the
 * canonical property. {@code maestro.admin.events.topic} is a deprecated
 * alias for it (used only when the canonical property is left at its
 * default — see {@code KafkaMessagingAutoConfigurationAdminTopicAliasTest}
 * for the precedence contract), and {@code maestro.admin.events.enabled}
 * gates lifecycle publishing entirely at the engine (see
 * {@code MaestroAutoConfigurationLifecycleEventsTest}). This suite
 * deliberately configures the canonical property.
 */
@SpringBootTest(
        classes = KafkaSignalTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaLifecycleEventsIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaLifecycleEventsIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false"
        })
@Tag("integration")
@DisplayName("The engine publishes workflow lifecycle events to the admin topic")
class KafkaLifecycleEventsIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-lifecycle";

    static final String ADMIN_TOPIC = "it.lifecycle.admin";

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private KafkaMessagingConfig messagingConfig;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createLifecycleTopics() throws ExecutionException, InterruptedException {
        createTopics(ADMIN_TOPIC, "maestro.signals." + SERVICE);
    }

    @Test
    @DisplayName("maestro.messaging.topics.admin-events names the topic the adapter publishes to")
    void adminEventsProperty_isTheTopicTheAdapterUses() {
        // Pinned separately because a silent mismatch here is invisible:
        // publishLifecycleEvent is fire-and-forget, so a wrong topic name loses
        // every event without an error anywhere.
        assertEquals(ADMIN_TOPIC, messagingConfig.adminEventsTopic());
    }

    @Test
    @DisplayName("a completed workflow emits WORKFLOW_STARTED then WORKFLOW_COMPLETED, keyed by workflow ID")
    void completedWorkflow_emitsStartedThenCompleted() {
        var workflowId = "lifecycle-ok-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ImmediateWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        var instance = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);

        try (var recorder = recorderFor(ADMIN_TOPIC)) {
            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(
                            List.of(LifecycleEventType.WORKFLOW_STARTED, LifecycleEventType.WORKFLOW_COMPLETED),
                            typesFor(recorder, workflowId),
                            "the admin topic must carry exactly a start then a completion"));

            var events = eventsFor(recorder, workflowId);
            var started = events.getFirst();
            assertEquals(instance.id(), started.workflowInstanceId());
            assertEquals(workflowId, started.workflowId());
            assertEquals("KafkaImmediateWorkflow", started.workflowType());
            assertEquals(SERVICE, started.serviceName(), "events must be attributed to the publishing service");
            assertEquals("default", started.taskQueue());
            assertNull(started.stepName(), "workflow-level events carry no step name");
            assertNotNull(started.timestamp());

            var completed = events.get(1);
            assertEquals(instance.id(), completed.workflowInstanceId());
            assertEquals(LifecycleEventType.WORKFLOW_COMPLETED, completed.eventType());
            assertTrue(!completed.timestamp().isBefore(started.timestamp()),
                    "completion cannot precede the start it completes");

            // Partition key: a workflow's events must stay in order on one partition.
            var keys = keysFor(recorder, workflowId);
            assertEquals(List.of(workflowId, workflowId), keys);
        }
    }

    @Test
    @DisplayName("a failing workflow emits WORKFLOW_FAILED after its start event")
    void failingWorkflow_emitsWorkflowFailed() {
        var workflowId = "lifecycle-fail-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ExplodingWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        var instance = awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);

        try (var recorder = recorderFor(ADMIN_TOPIC)) {
            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(
                            List.of(LifecycleEventType.WORKFLOW_STARTED, LifecycleEventType.WORKFLOW_FAILED),
                            typesFor(recorder, workflowId),
                            "a failure must be observable on the admin topic"));

            var failed = eventsFor(recorder, workflowId).get(1);
            assertEquals(instance.id(), failed.workflowInstanceId());
            assertEquals("KafkaExplodingWorkflow", failed.workflowType());
            assertEquals(SERVICE, failed.serviceName());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * @param recorder   the open admin-topic recorder
     * @param workflowId the workflow to filter on
     * @return that workflow's lifecycle events, in publication order
     */
    private static List<WorkflowLifecycleEvent> eventsFor(RecordRecorder recorder, String workflowId) {
        return recorder.messages(WorkflowLifecycleEvent.class).stream()
                .filter(e -> e.workflowId().equals(workflowId))
                .toList();
    }

    /**
     * @param recorder   the open admin-topic recorder
     * @param workflowId the workflow to filter on
     * @return that workflow's event types, in publication order
     */
    private static List<LifecycleEventType> typesFor(RecordRecorder recorder, String workflowId) {
        return eventsFor(recorder, workflowId).stream().map(WorkflowLifecycleEvent::eventType).toList();
    }

    /**
     * @param recorder   the open admin-topic recorder
     * @param workflowId the workflow to filter on
     * @return the record keys of that workflow's events
     */
    private static List<String> keysFor(RecordRecorder recorder, String workflowId) {
        var messages = recorder.messages(WorkflowLifecycleEvent.class);
        var keys = recorder.keys();
        return java.util.stream.IntStream.range(0, Math.min(messages.size(), keys.size()))
                .filter(i -> messages.get(i).workflowId().equals(workflowId))
                .mapToObj(keys::get)
                .toList();
    }
}
