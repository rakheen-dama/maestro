package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RULING 10 — an instance {@code status} string this build does not define must
 * not throw out of the row mapper.
 *
 * <h2>Blast radius</h2>
 * <p>A status is worse than a single event. {@code WorkflowStatus.valueOf} threw
 * {@link IllegalArgumentException} out of {@code getInstance}, which is read on
 * the recovery path ({@code WorkflowExecutor.launchWorkflow}'s pre-resume
 * re-check), on the workflow thread ({@code InstanceStatusWriter.write},
 * {@code transitionToTerminal}, {@code SagaManager.transitionToCompensating})
 * and on the signal-delivery path ({@code SignalManager.deliverSignal} — where
 * it means a signal is never persisted, breaking "never discard a signal").
 * {@code WorkflowExecutor.recoverWorkflows} wraps none of its per-instance work
 * in a {@code try}/{@code catch}, so one such throw ends the whole pass.
 *
 * <p>The fix is to skip that ONE row with a WARN carrying the raw string. Every
 * caller already has a defined, non-destructive answer for an absent instance.
 */
@DisplayName("PostgresWorkflowStore skips an instance whose status this build does not define")
class PostgresUnknownStatusMappingTest extends PostgresTestSupport {

    /** A status string no build of this repository will ever define. */
    private static final String FUTURE_STATUS = "HIBERNATING_IN_A_NEWER_MAESTRO";

    @Test
    @DisplayName("getInstance returns empty for a future status — it does not throw")
    void getInstanceSkipsAnUnknownStatus() throws SQLException {
        var instance = createInstance("status-read", WorkflowStatus.RUNNING);
        forceRawStatus(instance.workflowId(), FUTURE_STATUS);

        var found = assertDoesNotThrow(() -> store.getInstance("status-read"),
                "throwing here ends the caller's recovery pass, its status write, or its "
                        + "signal delivery — none of which is a workflow failure");

        assertTrue(found.isEmpty(),
                "this node cannot interpret that row, so it must report the instance as "
                        + "invisible rather than guess at its status");
    }

    /**
     * Records a fact worth pinning even though it holds <em>both</em> before and
     * after the fix: {@code getRecoverableInstances} filters
     * {@code status IN (<this build's active statuses>)}, so a row carrying a
     * status string this build does not define is never selected and the
     * unknown status never reaches the mapper <em>from that query</em>. RULING
     * 10's stated mechanism ("aborts the recoverable-instances query") is
     * therefore not the door the damage comes through — {@code getInstance},
     * which has no status filter, is. Pinned so the SQL filter is not
     * "simplified" away later, which would make that query abort exactly as the
     * ruling describes.
     */
    @Test
    @DisplayName("getRecoverableInstances never selects an unknown-status row in the first place")
    void recoverableScanSurvivesAnUnknownStatusRow() throws SQLException {
        var healthyA = createInstance("status-scan-a", WorkflowStatus.RUNNING);
        var poisoned = createInstance("status-scan-poison", WorkflowStatus.RUNNING);
        var healthyB = createInstance("status-scan-b", WorkflowStatus.WAITING_SIGNAL);
        forceRawStatus(poisoned.workflowId(), FUTURE_STATUS);

        var recoverable = assertDoesNotThrow(() -> store.getRecoverableInstances());

        var ids = recoverable.stream().map(WorkflowInstance::workflowId).toList();
        assertAll(
                () -> assertTrue(ids.contains(healthyA.workflowId()),
                        "every readable instance must survive the scan: " + ids),
                () -> assertTrue(ids.contains(healthyB.workflowId()), "got: " + ids),
                () -> assertEquals(2, recoverable.size(),
                        "exactly the two readable instances, and not the poisoned one: " + ids));
    }

    @Test
    @DisplayName("a row this build CAN read is untouched by the guard")
    void knownStatusesAreUnaffected() {
        for (var status : WorkflowStatus.values()) {
            var instance = createInstance("status-ok-" + status, status);
            assertEquals(status, store.getInstance(instance.workflowId()).orElseThrow().status(),
                    "the guard must not disturb any status this build defines");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private WorkflowInstance createInstance(String workflowId, WorkflowStatus status) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(status)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

    /** Writes a status string this build does not define — what a newer node does. */
    private void forceRawStatus(String workflowId, String rawStatus) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_instance SET status = ? WHERE workflow_id = ?")) {
            ps.setString(1, rawStatus);
            ps.setString(2, workflowId);
            assertEquals(1, ps.executeUpdate());
        }
    }
}
