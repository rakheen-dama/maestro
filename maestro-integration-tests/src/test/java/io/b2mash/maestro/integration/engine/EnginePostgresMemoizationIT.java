package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves hybrid memoization against a real PostgreSQL event log: an activity
 * with a stored result at its sequence number is <em>served from the log</em>,
 * never re-executed.
 *
 * <h2>Why the event log is pre-seeded</h2>
 * <p>Running the workflow once and replaying it would show the same final
 * output whether the engine replayed the stored value or re-ran the activity
 * and happened to produce the same answer. Seeding the log with payloads the
 * activity could never produce makes the two outcomes distinguishable: if the
 * final output carries the seeded value, the stored event was genuinely
 * returned. The invocation counters then prove the activity never ran at all.
 *
 * <p>{@code EnginePostgresRecoveryIT} covers the same property from the other
 * direction, with a real mid-flight crash rather than a seeded log.
 */
@Tag("integration")
@DisplayName("Memoization replays stored activity results from Postgres")
class EnginePostgresMemoizationIT extends PostgresIntegrationSupport {

    private MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a completed activity with a stored result is not re-executed on replay")
    void storedActivityResult_isReplayedNotReExecuted() {
        var recorder = new CountingActivities.Recorder();
        harness = chainHarness(recorder);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("memo");
        var instance = seedRunningInstance(workflowId, "seed");
        // Values no live run could produce — if they surface in the output, the
        // engine really did read them back out of Postgres.
        seedEvent(instance, 1, EventType.ACTIVITY_COMPLETED, "chain.stepOne", "MEMOIZED-1");
        seedEvent(instance, 2, EventType.ACTIVITY_COMPLETED, "chain.stepTwo", "MEMOIZED-2");

        assertEquals(1, harness.recover(), "the RUNNING instance must be recoverable");
        awaitTerminal(workflowId);

        var recovered = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, recovered.status());
        assertEquals("MEMOIZED-2-three", serializer.deserialize(recovered.output(), String.class),
                "the output must be built from the stored payloads, not from a re-run");

        assertEquals(0, recorder.count("stepOne"), "a memoized step must not execute");
        assertEquals(0, recorder.count("stepTwo"), "a memoized step must not execute");
        assertEquals(1, recorder.count("stepThree"), "the first uncompleted step must execute exactly once");

        // Replay must not rewrite history — the seeded events survive verbatim.
        var events = store.getEvents(instance.id());
        assertEquals(List.of(1, 2, 3, 4), events.stream().map(WorkflowEvent::sequenceNumber).toList());
        assertEquals("MEMOIZED-1", serializer.deserialize(events.get(0).payload(), String.class));
        assertEquals("MEMOIZED-2", serializer.deserialize(events.get(1).payload(), String.class));
    }

    @Test
    @DisplayName("a stored activity failure is replayed as a failure rather than retried")
    void storedActivityFailure_isReplayedNotRetried() {
        var recorder = new CountingActivities.Recorder();
        harness = chainHarness(recorder);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("memo-fail");
        var instance = seedRunningInstance(workflowId, "seed");
        seedFailureEvent(instance, 1, "chain.stepOne",
                "java.lang.IllegalStateException", "stored failure at step one");

        assertEquals(1, harness.recover());
        awaitTerminal(workflowId);

        var recovered = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.FAILED, recovered.status(),
                "a replayed failure must fail the run again, not silently succeed");
        assertEquals(0, recorder.count("stepOne"), "a stored failure must not be re-executed");
        assertEquals(0, recorder.count("stepTwo"), "execution must not proceed past a replayed failure");

        // The replayed failure is surfaced as an ActivityExecutionException that
        // carries the stored message — workflow code catching it behaves the
        // same on replay as it did live.
        var failure = store.getEvents(instance.id()).getLast();
        assertEquals(EventType.WORKFLOW_FAILED, failure.eventType());
        assertEquals("io.b2mash.maestro.core.exception.ActivityExecutionException",
                failure.payload().get("exceptionType").stringValue());
        assertTrue(failure.payload().get("message").stringValue().contains("chain.stepOne"),
                "the workflow failure must name the step that failed, got: " + failure.payload());

        // The stored ACTIVITY_FAILED event is untouched: no second failure row,
        // no upgrade to a completion.
        var activityEvents = store.getEvents(instance.id()).stream()
                .filter(e -> "chain.stepOne".equals(e.stepName()))
                .toList();
        assertEquals(1, activityEvents.size(), "replay must not append a second event for the step");
        assertEquals(EventType.ACTIVITY_FAILED, activityEvents.getFirst().eventType());
    }

    @Test
    @DisplayName("replaying a run twice is stable — the second replay changes nothing")
    void repeatedReplay_isStable() {
        var firstRecorder = new CountingActivities.Recorder();
        harness = chainHarness(firstRecorder);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("memo-stable");
        var instance = seedRunningInstance(workflowId, "seed");
        seedEvent(instance, 1, EventType.ACTIVITY_COMPLETED, "chain.stepOne", "MEMOIZED-1");

        assertEquals(1, harness.recover());
        awaitTerminal(workflowId);
        var afterFirst = store.getInstance(workflowId).orElseThrow();
        var eventsAfterFirst = describe(store.getEvents(instance.id()));

        // A second node replaying the same log must observe the same history and
        // must not resurrect a terminal workflow.
        var secondRecorder = new CountingActivities.Recorder();
        try (var replayNode = chainHarness(secondRecorder)) {
            assertEquals(0, replayNode.recover(),
                    "a COMPLETED workflow is not recoverable — nothing to replay");
        }

        assertEquals(eventsAfterFirst, describe(store.getEvents(instance.id())));
        assertEquals(afterFirst.version(), store.getInstance(workflowId).orElseThrow().version());
        assertEquals(0, secondRecorder.count("stepOne"));
        assertEquals(0, secondRecorder.count("stepTwo"));
        assertEquals(0, secondRecorder.count("stepThree"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a harness wired for {@link TestWorkflows.ChainWorkflow}.
     *
     * @param recorder the recorder the chain activities report to
     * @return a harness with the chain workflow registered
     */
    private MaestroEngineHarness chainHarness(CountingActivities.Recorder recorder) {
        var built = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("memo-node")
                .lock(newLock())
                .build();
        built.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        built.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return built;
    }

    /**
     * Writes a RUNNING instance row directly, standing in for a workflow whose
     * owning process died mid-flight.
     *
     * @param workflowId the business workflow ID
     * @param input      the workflow input
     * @return the persisted instance
     */
    private WorkflowInstance seedRunningInstance(String workflowId, String input) {
        var now = Instant.now();
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("ChainWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .input(serializer.serialize(input))
                .serviceName("crashed-node")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

    /**
     * Seeds a completed-activity event so the memoization lookup finds a result
     * at that sequence number.
     *
     * @param instance the owning instance
     * @param sequence the sequence number to occupy
     * @param type     the event type to store
     * @param stepName the step name
     * @param result   the payload the replay must return
     */
    private void seedEvent(WorkflowInstance instance, int sequence, EventType type,
                           String stepName, String result) {
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), sequence,
                type, stepName, serializer.serialize(result), Instant.now()));
    }

    /**
     * Seeds a failed-activity event in the shape the engine writes, so replay
     * reconstructs the original exception.
     *
     * @param instance      the owning instance
     * @param sequence      the sequence number to occupy
     * @param stepName      the step name
     * @param exceptionType fully-qualified class name of the original failure
     * @param message       the original failure message
     */
    private void seedFailureEvent(WorkflowInstance instance, int sequence, String stepName,
                                  String exceptionType, String message) {
        JsonNode payload = objectMapper.createObjectNode()
                .put("exceptionType", exceptionType)
                .put("message", message);
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), sequence,
                EventType.ACTIVITY_FAILED, stepName, payload, Instant.now()));
    }

    /**
     * Waits until the workflow is finished in the durable log — terminal status
     * and its terminal event.
     *
     * <p>Every caller here goes on to assert on the event log (sequence
     * continuity, the last event's type, replay stability), so the status alone
     * is the wrong gate. See {@code TerminalWait}.
     *
     * @param workflowId the workflow to wait for
     */
    private void awaitTerminal(String workflowId) {
        awaitTerminal(workflowId, Duration.ofSeconds(15));
    }

    /**
     * Renders an event log as {@code sequence:type:stepName} strings.
     *
     * @param events the persisted events
     * @return one descriptor per event, in sequence order
     */
    private static List<String> describe(List<WorkflowEvent> events) {
        return events.stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }
}
