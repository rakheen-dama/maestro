package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.FlakyActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec C8 on real PostgreSQL: a JVM {@link Error} escaping the workflow method
 * ends the workflow {@code FAILED} — it is not left {@code RUNNING} for the
 * recovery poller to re-adopt forever. The exception (R20) is a node-level
 * error — a {@code LinkageError} or a {@code VirtualMachineError} other than
 * {@code StackOverflowError} — which abandons the run loudly and leaves the
 * instance recoverable instead.
 */
@Tag("integration")
@DisplayName("A JVM Error escaping the workflow body ends it FAILED on Postgres")
class ErrorEscapeFailsWorkflowIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Test
    @DisplayName("AssertionError: FAILED, WORKFLOW_FAILED recorded, observer fired, lock released, nothing to recover")
    void assertionErrorEndsFailedOnPostgres() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var observer = new RecordingObserver();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("error-escape");
        try (var node = MaestroEngineHarness.builder(store, objectMapper).serviceName("node-a")
                .lock(newLock()).observer(observer).build()) {
            node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
            node.registerWorkflow(new TestWorkflows.ErrorThrowingWorkflow());
            var handle = node.start(workflowId, TestWorkflows.ErrorThrowingWorkflow.class, "seed");

            assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
            var failed = store.getEvents(handle.instanceId()).stream()
                    .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).toList();
            assertAll(
                    () -> assertEquals(1, failed.size()),
                    () -> assertEquals("java.lang.AssertionError",
                            failed.getFirst().payload().path("exceptionType").asString()),
                    () -> assertEquals(List.of(workflowId), observer.failed()),
                    () -> assertTrue(observer.standDowns().isEmpty()),
                    () -> assertEquals(1, recorder.count("stepOne")),
                    () -> assertFalse(lockRowHeld(workflowId)),
                    () -> assertEquals(0, node.recover(), "FAILED is terminal: nothing to re-adopt"));
        }
    }

    @Test
    @DisplayName("NoClassDefFoundError (R20): a node error — stays RUNNING, nothing written, lock released, "
            + "abandoned as NODE_ERROR, and re-adopted by recovery")
    void noClassDefFoundErrorAbandonsAndStaysRecoverableOnPostgres() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var observer = new RecordingObserver();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("node-error");
        try (var node = MaestroEngineHarness.builder(store, objectMapper).serviceName("node-a")
                .lock(newLock()).observer(observer).build()) {
            node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
            node.registerWorkflow(new TestWorkflows.NodeErrorWorkflow());
            var handle = node.start(workflowId, TestWorkflows.NodeErrorWorkflow.class, "seed");

            await().atMost(BOUND).until(() -> !observer.abandoned().isEmpty());
            await().atMost(BOUND).until(() -> !node.executor().isRunning(workflowId));
            assertAll(
                    () -> assertEquals(List.of(AbandonReason.NODE_ERROR), observer.abandoned()),
                    () -> assertEquals(WorkflowStatus.RUNNING,
                            store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(0, store.getEvents(handle.instanceId()).stream()
                            .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count()),
                    () -> assertTrue(observer.failed().isEmpty()),
                    () -> assertTrue(observer.compensating().isEmpty()),
                    () -> assertTrue(observer.standDowns().isEmpty()),
                    () -> assertEquals(1, recorder.count("stepOne")),
                    () -> assertFalse(lockRowHeld(workflowId)));

            // Still recoverable: the poller re-adopts it (and, the node still
            // broken, it abandons loudly again — never FAILED).
            assertEquals(1, node.recover());
            await().atMost(BOUND).until(() -> observer.abandoned().size() == 2);
            await().atMost(BOUND).until(() -> !node.executor().isRunning(workflowId));
            assertAll(
                    () -> assertEquals(List.of(AbandonReason.NODE_ERROR, AbandonReason.NODE_ERROR),
                            observer.abandoned()),
                    () -> assertEquals(WorkflowStatus.RUNNING,
                            store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(1, recorder.count("stepOne"), "replayed, never re-executed"));
        }
    }

    @Test
    @DisplayName("NoClassDefFoundError thrown INSIDE an activity (R28): not retried, no ACTIVITY_FAILED, "
            + "stays RUNNING, abandoned as NODE_ERROR")
    void nodeErrorInsideActivityAbandonsWithoutRecordingAFailureOnPostgres() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var observer = new RecordingObserver();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("node-error-activity");
        try (var node = MaestroEngineHarness.builder(store, objectMapper).serviceName("node-a")
                .lock(newLock()).observer(observer).build()) {
            // RetryWorkflow's stub uses the default policy (3 attempts, 1 s backoff):
            // before R28 the error was wrapped, retried twice, memoized as
            // ACTIVITY_FAILED and the workflow ended FAILED.
            node.registerActivities(FlakyActivities.class, input -> {
                recorder.record("attempt");
                throw new NoClassDefFoundError("com/acme/RemovedDependency");
            });
            node.registerWorkflow(new TestWorkflows.RetryWorkflow());
            var handle = node.start(workflowId, TestWorkflows.RetryWorkflow.class, "seed");

            await().atMost(BOUND).until(() -> !observer.abandoned().isEmpty() || !observer.failed().isEmpty());
            await().atMost(BOUND).until(() -> !node.executor().isRunning(workflowId));
            assertAll(
                    () -> assertEquals(List.of(AbandonReason.NODE_ERROR), observer.abandoned()),
                    () -> assertEquals(1, recorder.count("attempt"), "a node error is never retried"),
                    () -> assertEquals(WorkflowStatus.RUNNING,
                            store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(0, store.getEvents(handle.instanceId()).stream()
                            .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED
                                    || e.eventType() == EventType.WORKFLOW_FAILED).count(),
                            "nothing about the node error may be memoized"),
                    () -> assertTrue(observer.failed().isEmpty()),
                    () -> assertTrue(observer.compensating().isEmpty()),
                    () -> assertFalse(lockRowHeld(workflowId)));
        }
    }

    /** @return whether the Postgres lock backend still holds this instance's lock */
    private boolean lockRowHeld(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM maestro_distributed_lock "
                             + "WHERE lock_key = ? AND expires_at > now()")) {
            ps.setString(1, "maestro:lock:workflow:" + workflowId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
