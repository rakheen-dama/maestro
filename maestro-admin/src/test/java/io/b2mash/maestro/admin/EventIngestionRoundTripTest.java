package io.b2mash.maestro.admin;

import io.b2mash.maestro.admin.repository.EventRepository;
import io.b2mash.maestro.admin.repository.MetricsRepository;
import io.b2mash.maestro.admin.repository.ServiceRepository;
import io.b2mash.maestro.admin.repository.WorkflowRepository;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the full ingestion pipeline: a {@link WorkflowLifecycleEvent}
 * published to the configured Kafka topic is picked up by
 * {@link io.b2mash.maestro.admin.kafka.AdminEventConsumer}, projected by
 * {@link io.b2mash.maestro.admin.projection.EventProjector}, and ends up
 * persisted and queryable through every repository and — end to end — through
 * {@link io.b2mash.maestro.admin.controller.DashboardController#workflowDetail}.
 *
 * <p>This is the one place in the module's suite that exercises the
 * asynchronous Kafka-to-Postgres path rather than driving repositories or
 * controllers directly; every assertion here therefore polls with Awaitility
 * instead of asserting immediately after publish.
 */
@SpringBootTest(properties = {
        "maestro.admin.events-topic=" + EventIngestionRoundTripTest.ADMIN_TOPIC,
        "maestro.admin.consumer-group=" + EventIngestionRoundTripTest.GROUP
})
@AutoConfigureMockMvc
@DisplayName("Kafka lifecycle event -> admin database round trip")
class EventIngestionRoundTripTest extends AdminAppTestSupport {

    static final String ADMIN_TOPIC = "admin-ingestion.events";
    static final String GROUP = "admin-ingestion-group";

    private static final Duration BOUND = Duration.ofSeconds(15);

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private MetricsRepository metricsRepository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createTopic() throws ExecutionException, InterruptedException {
        createTopics(ADMIN_TOPIC);
    }

    @Test
    @DisplayName("WORKFLOW_STARTED then WORKFLOW_COMPLETED projects a RUNNING-then-COMPLETED workflow, "
            + "an event timeline, a discovered service, and updated metrics — all queryable")
    void startedThenCompleted_projectsFullState() throws Exception {
        var instanceId = UUID.randomUUID();
        var workflowId = "ingest-" + instanceId;
        var serviceName = "ingestion-test-service-" + instanceId;
        var startedAt = Instant.now().minusSeconds(5);
        var completedAt = Instant.now();

        publish(ADMIN_TOPIC, workflowId, new WorkflowLifecycleEvent(
                instanceId, workflowId, "IngestionTestWorkflow", serviceName, "default",
                LifecycleEventType.WORKFLOW_STARTED, null, null, startedAt));

        // The workflow and its owning service must appear before the completion
        // event arrives, and status starts at RUNNING.
        await().atMost(BOUND).untilAsserted(() -> {
            var workflow = workflowRepository.findByWorkflowId(workflowId);
            assertThat(workflow).isPresent();
            assertThat(workflow.get().status()).isEqualTo("RUNNING");
            assertThat(workflow.get().workflowInstanceId()).isEqualTo(instanceId);
            assertThat(workflow.get().serviceName()).isEqualTo(serviceName);
        });
        assertThat(serviceRepository.findByName(serviceName)).isPresent();

        publish(ADMIN_TOPIC, workflowId, new WorkflowLifecycleEvent(
                instanceId, workflowId, "IngestionTestWorkflow", serviceName, "default",
                LifecycleEventType.WORKFLOW_COMPLETED, null, null, completedAt));

        await().atMost(BOUND).untilAsserted(() -> {
            var workflow = workflowRepository.findByWorkflowId(workflowId);
            assertThat(workflow).isPresent();
            assertThat(workflow.get().status()).isEqualTo("COMPLETED");
            assertThat(workflow.get().completedAt()).isNotNull();
            assertThat(workflow.get().eventCount()).isEqualTo(2);
        });

        // The full event timeline is queryable, oldest first.
        var timeline = eventRepository.findByWorkflowInstanceId(instanceId);
        assertThat(timeline).extracting(e -> e.eventType())
                .containsExactly("WORKFLOW_STARTED", "WORKFLOW_COMPLETED");

        // Metrics reflect the terminal state: one COMPLETED, zero RUNNING for this service.
        var overview = metricsRepository.getOverview();
        assertThat(overview.get(serviceName))
                .containsEntry("COMPLETED", 1L)
                .doesNotContainEntry("RUNNING", 1L);

        // And the whole thing is reachable through the real controller + view.
        mockMvc.perform(get("/admin/workflows/{workflowId}", workflowId))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("workflow", "events"));
    }
}
