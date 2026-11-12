package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract of the {@link SeededHistory} fixture API that WP9, WP1 and
 * WP3 assert through: {@link SeededHistory#awaitStatusOrLast} returns the
 * awaited status only when the run really reached it, so a caller's assertion
 * cannot pass on a run whose terminal event never landed.
 */
@Tag("integration")
@DisplayName("SeededHistory fixture API")
class SeededHistoryFixtureIT {

    @Test
    @DisplayName("awaitStatusOrLast returns a status that was reached, finalised in the log")
    void awaitStatusOrLast_returnsAReachedTerminalStatus() {
        var seeded = SeededHistory.load();
        assertEquals(WorkflowStatus.FAILED,
                seeded.awaitStatusOrLast(Seeds.ACTIVITY_FAILED, WorkflowStatus.FAILED, Duration.ofMillis(300)));
    }

    @Test
    @DisplayName("awaitStatusOrLast returns the status last observed when the awaited one is not reached")
    void awaitStatusOrLast_returnsTheLastObservedStatus() {
        var seeded = SeededHistory.load();
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                seeded.awaitStatusOrLast(Seeds.PARKED_APPROVAL, WorkflowStatus.COMPLETED, Duration.ofMillis(300)));
    }

    @Test
    @DisplayName("awaitStatusOrLast refuses a terminal status whose terminal event is missing")
    void awaitStatusOrLast_refusesAnUnfinalisedTerminalStatus() throws Exception {
        var seeded = SeededHistory.load();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("""
                     DELETE FROM maestro_workflow_event e USING maestro_workflow_instance i
                     WHERE i.id = e.workflow_instance_id AND i.workflow_id = ? AND e.event_type = 'WORKFLOW_FAILED'""")) {
            ps.setString(1, Seeds.ACTIVITY_FAILED);
            assertEquals(1, ps.executeUpdate());
        }
        var thrown = assertThrows(IllegalStateException.class,
                () -> seeded.awaitStatusOrLast(Seeds.ACTIVITY_FAILED, WorkflowStatus.FAILED, Duration.ofMillis(300)));
        assertTrue(thrown.getMessage().contains("is FAILED but its terminal event is not in the log"),
                thrown.getMessage());
    }
}
