package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link TerminalWrites#requireValid}: the terminal status and the
 * terminal event must describe the same outcome. A {@code COMPLETED} row with a
 * {@code WORKFLOW_FAILED} event (or the reverse) would hand every reader a log
 * that contradicts its own row.
 */
@DisplayName("TerminalWrites.requireValid pairs the terminal status with its event type")
class TerminalWritesTest {

    private final UUID instanceId = UUID.randomUUID();

    @Test
    @DisplayName("COMPLETED with WORKFLOW_COMPLETED and FAILED with WORKFLOW_FAILED are accepted")
    void matchingPairs_areAccepted() {
        assertDoesNotThrow(() -> TerminalWrites.requireValid(
                instance(WorkflowStatus.COMPLETED), event(EventType.WORKFLOW_COMPLETED)));
        assertDoesNotThrow(() -> TerminalWrites.requireValid(
                instance(WorkflowStatus.FAILED), event(EventType.WORKFLOW_FAILED)));
    }

    @Test
    @DisplayName("COMPLETED with WORKFLOW_FAILED is rejected")
    void completedWithFailedEvent_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.COMPLETED), event(EventType.WORKFLOW_FAILED)));
    }

    @Test
    @DisplayName("FAILED with WORKFLOW_COMPLETED is rejected")
    void failedWithCompletedEvent_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.FAILED), event(EventType.WORKFLOW_COMPLETED)));
    }

    @Test
    @DisplayName("TERMINATED is rejected with either event — there is no WORKFLOW_TERMINATED event to pair it with")
    void terminated_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.TERMINATED), event(EventType.WORKFLOW_COMPLETED)));
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.TERMINATED), event(EventType.WORKFLOW_FAILED)));
    }

    @Test
    @DisplayName("a non-terminal status and a foreign instance id are still rejected")
    void existingChecks_stillHold() {
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.RUNNING), event(EventType.WORKFLOW_COMPLETED)));
        var foreign = new WorkflowEvent(UUID.randomUUID(), UUID.randomUUID(), 2,
                EventType.WORKFLOW_COMPLETED, null, null, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> TerminalWrites.requireValid(
                instance(WorkflowStatus.COMPLETED), foreign));
    }

    private WorkflowInstance instance(WorkflowStatus status) {
        return WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("wf-terminal-writes")
                .runId(UUID.randomUUID())
                .workflowType("T")
                .taskQueue("default")
                .status(status)
                .serviceName("svc")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private WorkflowEvent event(EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, 2, type, null, null, Instant.now());
    }
}
