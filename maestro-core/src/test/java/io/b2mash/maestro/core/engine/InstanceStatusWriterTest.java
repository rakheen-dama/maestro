package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link InstanceStatusWriter}'s terminal guard (C5): a fresh read of
 * <em>any</em> terminal status means another runner owns the outcome, so the
 * write is refused and the caller's run is told to stand down — never a quiet
 * return that lets the run carry on past a finalised row.
 */
@DisplayName("InstanceStatusWriter refuses to write over any terminal status")
class InstanceStatusWriterTest {

    private VersionedInMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
    }

    @Test
    @DisplayName("a fresh read of COMPLETED throws StaleRunException — another runner finalised it")
    void freshReadCompleted_throwsStaleRun() {
        seed("w1", WorkflowStatus.COMPLETED);
        var thrown = assertThrows(StaleRunException.class,
                () -> InstanceStatusWriter.write(store, "w1", WorkflowStatus.RUNNING));
        assertEquals(WorkflowStatus.COMPLETED, thrown.observedStatus());
        assertEquals("w1", thrown.workflowId());
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("w1").orElseThrow().status(), "nothing written");
    }

    @Test
    @DisplayName("a fresh read of FAILED throws StaleRunException")
    void freshReadFailed_throwsStaleRun() {
        seed("w2", WorkflowStatus.FAILED);
        var thrown = assertThrows(StaleRunException.class,
                () -> InstanceStatusWriter.write(store, "w2", WorkflowStatus.WAITING_TIMER));
        assertEquals(WorkflowStatus.FAILED, thrown.observedStatus());
        assertEquals(WorkflowStatus.FAILED, store.getInstance("w2").orElseThrow().status(), "nothing written");
    }

    @Test
    @DisplayName("a fresh read of TERMINATED still throws WorkflowTerminatedException")
    void freshReadTerminated_throwsWorkflowTerminated() {
        seed("w3", WorkflowStatus.TERMINATED);
        assertThrows(WorkflowTerminatedException.class,
                () -> InstanceStatusWriter.write(store, "w3", WorkflowStatus.WAITING_SIGNAL));
    }

    private void seed(String workflowId, WorkflowStatus status) {
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0)
                .build());
        store.forceStatus(workflowId, status);
    }
}
