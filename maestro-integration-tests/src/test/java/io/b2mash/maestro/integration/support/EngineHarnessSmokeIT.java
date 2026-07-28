package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the shared fixtures actually drive the real engine against a real
 * Postgres backend.
 *
 * <p>This is the scaffold's own test: it exists so that the P0–P2 suites can
 * trust {@link MaestroEngineHarness}, {@link WorkflowHandle} and the workflow
 * fixtures rather than debugging them. It deliberately asserts on persisted
 * state — instance status, the event log and activity invocation counts — not
 * on the harness's in-memory view.
 */
@Tag("integration")
@DisplayName("Engine harness drives the real engine against Postgres")
class EngineHarnessSmokeIT extends PostgresIntegrationSupport {

    private MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("an activity chain completes and persists its events to Postgres")
    void activityChain_completesAndPersistsEvents() {
        var recorder = new CountingActivities.Recorder();
        harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("smoke-node")
                .lock(newLock())
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("smoke");
        var handle = harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals("seed-one-two-three", handle.result(String.class));

        // Each step ran exactly once — no accidental replay in a straight-line run.
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(1, recorder.count("stepThree"));

        // The start of a run is recorded on the instance row, not in the event
        // log — EventType.WORKFLOW_STARTED is never appended; the engine
        // publishes LifecycleEventType.WORKFLOW_STARTED to messaging instead.
        var instance = handle.instance();
        assertEquals("seed", serializer.deserialize(instance.input(), String.class));
        assertTrue(instance.startedAt() != null, "start time must be persisted");

        // The event log is what recovery replays from, so it must be durable.
        var events = handle.events();
        assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED),
                "expected a WORKFLOW_COMPLETED event, got " + eventTypes(events));
        assertEquals(3, events.stream()
                        .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED)
                        .count(),
                "expected one ACTIVITY_COMPLETED per step, got " + eventTypes(events));
    }

    private static String eventTypes(java.util.List<io.b2mash.maestro.core.model.WorkflowEvent> events) {
        return events.stream().map(e -> e.eventType().name()).toList().toString();
    }
}
