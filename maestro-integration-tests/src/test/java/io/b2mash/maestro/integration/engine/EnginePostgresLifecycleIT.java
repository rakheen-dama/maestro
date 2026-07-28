package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the full workflow lifecycle — start, activities, terminal transition —
 * against a real PostgreSQL store.
 *
 * <p>Everything asserted here is read back from Postgres, never from the
 * engine's in-memory view: the event log's sequence numbers and step names, the
 * instance row's {@code event_sequence} and {@code version} columns, and the
 * serialized output payload. Those columns are the contract recovery depends
 * on, and they had never been exercised against a real database before this
 * suite (see {@code docs/test-plan.md} §P0).
 */
@Tag("integration")
@DisplayName("The engine runs a workflow lifecycle end to end against Postgres")
class EnginePostgresLifecycleIT extends PostgresIntegrationSupport {

    private MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a completed chain persists one event per activity plus a completion event")
    void completedChain_persistsOneEventPerActivityPlusCompletion() {
        var recorder = new CountingActivities.Recorder();
        harness = chainHarness(recorder);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lifecycle");
        var handle = harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals("seed-one-two-three", handle.result(String.class));

        var events = handle.events();
        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                describe(events),
                "the persisted event log is the replay contract");

        // Each activity's result is stored verbatim — that is what replay returns.
        assertEquals("seed-one", serializer.deserialize(events.get(0).payload(), String.class));
        assertEquals("seed-one-two", serializer.deserialize(events.get(1).payload(), String.class));
        assertEquals("seed-one-two-three", serializer.deserialize(events.get(2).payload(), String.class));

        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(1, recorder.count("stepThree"));
    }

    @Test
    @DisplayName("the instance row's event sequence and version march with the run")
    void completedChain_marchesEventSequenceAndVersion() {
        harness = chainHarness(new CountingActivities.Recorder());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lifecycle");
        var handle = harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        var instance = handle.instance();
        // event_sequence is the last sequence the run consumed before the
        // terminal transition — three activities, so 3.
        assertEquals(3, instance.eventSequence());
        // The terminal WORKFLOW_COMPLETED event is appended after the instance
        // update, one past event_sequence.
        assertEquals(instance.eventSequence() + 1, handle.events().getLast().sequenceNumber());
        // Exactly one persisted status transition (RUNNING → COMPLETED), so
        // exactly one version increment from the version 0 written at creation.
        assertEquals(1, instance.version());

        assertEquals("integration-test-node", instance.serviceName());
        assertEquals("seed", serializer.deserialize(instance.input(), String.class));
        assertNotNull(instance.startedAt(), "started_at must be persisted");
        assertNotNull(instance.completedAt(), "completed_at must be persisted on a terminal run");
        assertTrue(store.getRecoverableInstances().isEmpty(),
                "a completed workflow must not be offered for recovery");
    }

    @Test
    @DisplayName("a workflow whose activity exhausts its retries is persisted as FAILED with an error payload")
    void exhaustedActivity_persistsFailedInstanceAndFailureEvents() {
        var recorder = new CountingActivities.Recorder();
        harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("integration-test-node")
                .lock(newLock())
                .build();
        // Always fails: more injected failures than the stub's maxAttempts (3).
        harness.registerActivities(CountingActivities.FlakyActivities.class,
                new CountingActivities.FailNTimesActivities(recorder, 99));
        harness.registerWorkflow(new TestWorkflows.RetryWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lifecycle-fail");
        var handle = harness.start(workflowId, TestWorkflows.RetryWorkflow.class, "seed");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals(3, recorder.count("attempt"), "the default retry policy allows three attempts");

        var events = handle.events();
        assertEquals(
                List.of("1:ACTIVITY_FAILED:flaky.attempt",
                        "2:WORKFLOW_FAILED:null"),
                describe(events),
                "a failed run must leave a failure trail in the event log");

        // The activity failure records the original exception, the workflow
        // failure records what actually escaped the workflow method.
        assertEquals("java.lang.IllegalStateException",
                events.getFirst().payload().get("exceptionType").stringValue());
        assertEquals("io.b2mash.maestro.core.exception.ActivityExecutionException",
                events.getLast().payload().get("exceptionType").stringValue());

        var instance = handle.instance();
        assertEquals(1, instance.eventSequence());
        assertEquals(1, instance.version());
        assertNotNull(instance.completedAt(), "a failed run is terminal and must record completed_at");
        assertEquals("io.b2mash.maestro.core.exception.ActivityExecutionException",
                instance.output().get("exceptionType").stringValue(),
                "the failure detail is persisted as the instance output");
        assertTrue(store.getRecoverableInstances().isEmpty(),
                "a failed workflow must not be offered for recovery");
    }

    @Test
    @DisplayName("starting a second workflow with an existing ID is rejected by the store constraint")
    void duplicateWorkflowId_throwsWorkflowAlreadyExists() {
        harness = chainHarness(new CountingActivities.Recorder());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lifecycle-dup");
        var handle = harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        var duplicate = assertThrows(WorkflowAlreadyExistsException.class,
                () -> harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed"));
        assertTrue(duplicate.getMessage().contains(workflowId),
                "the exception must name the conflicting workflow ID, got: " + duplicate.getMessage());

        // The rejected start must leave the first run's state untouched.
        assertEquals(WorkflowStatus.COMPLETED, handle.status());
        assertEquals(4, handle.events().size());

        // The rejected start acquires the instance lock before it discovers the
        // clash (the lock is taken before createInstance so a recovery poll
        // cannot steal a just-created workflow). It must give it back: a
        // stranded row would block recovery of that workflow ID until the TTL
        // expired.
        assertEquals(0, instanceLockRows(workflowId),
                "a rejected duplicate start must not strand the instance lock");
    }

    @Test
    @DisplayName("a completed run leaves no instance lock behind")
    void completedRun_releasesInstanceLock() {
        harness = chainHarness(new CountingActivities.Recorder());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("lifecycle-lock");
        var handle = harness.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        assertEquals(0, instanceLockRows(workflowId),
                "the instance lock must be released when the run ends, not left to its TTL");
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
                .serviceName("integration-test-node")
                .lock(newLock())
                .build();
        built.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        built.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return built;
    }

    /**
     * Counts live rows in the Postgres lock table for a workflow's instance lock.
     *
     * @param workflowId the workflow whose instance lock is inspected
     * @return the number of unexpired lock rows for that key
     * @throws IllegalStateException if the lock table cannot be queried
     */
    private int instanceLockRows(String workflowId) {
        var sql = "SELECT count(*) FROM maestro_distributed_lock "
                + "WHERE lock_key = ? AND expires_at > now()";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "maestro:lock:workflow:" + workflowId);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the lock table", e);
        }
    }

    /**
     * Renders an event log as {@code sequence:type:stepName} strings so a failed
     * assertion shows the whole log rather than a single mismatched field.
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
