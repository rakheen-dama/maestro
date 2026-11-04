package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.FencedWriteException;
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
    @DisplayName("the context overload makes the stale cause sticky: the run's RunCancellation is STALE before "
            + "the StaleRunException propagates (round 2, R33)")
    void contextOverload_requestsStaleBeforeThrowing() {
        seed("w1c", WorkflowStatus.COMPLETED);
        var ctx = new io.b2mash.maestro.core.context.WorkflowContext(UUID.randomUUID(), "w1c", UUID.randomUUID(),
                "TestWorkflow", "default", "test-service", 0, false);
        assertThrows(StaleRunException.class,
                () -> InstanceStatusWriter.write(store, ctx, WorkflowStatus.RUNNING));
        assertEquals(io.b2mash.maestro.core.context.RunCancellation.Cause.STALE, ctx.cancellation().cause(),
                "a swallowed StaleRunException must still stop the run's next live activity");
    }

    @Test
    @DisplayName("a fenced status write (another node adopted the instance) throws FencedWriteException, writes "
            + "nothing, and sets the run STALE — it is not a version conflict to retry (Issue 11)")
    void contextOverload_fencedWriteIsStickyAndNotRetried() {
        seed("w1f", WorkflowStatus.RUNNING);
        var instance = store.getInstance("w1f").orElseThrow();
        var ctx = new io.b2mash.maestro.core.context.WorkflowContext(instance.id(), "w1f", instance.runId(),
                "TestWorkflow", "default", "test-service", 0, false, null,
                new io.b2mash.maestro.core.context.RunCancellation(), 0L);
        // Another node adopts: the row's epoch moves past this run's.
        store.forceLeaseEpoch("w1f", 1);
        var attemptsBefore = store.updateAttempts();

        var thrown = assertThrows(FencedWriteException.class,
                () -> InstanceStatusWriter.write(store, ctx, WorkflowStatus.WAITING_SIGNAL));
        assertEquals(instance.id(), thrown.instanceId());
        assertEquals(0, thrown.leaseEpoch());
        assertEquals(io.b2mash.maestro.core.context.RunCancellation.Cause.STALE, ctx.cancellation().cause(),
                "a swallowed FencedWriteException must still stop the run's next live activity");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("w1f").orElseThrow().status(), "nothing written");
        assertEquals(1, store.updateAttempts() - attemptsBefore,
                "a fence is not a lost CAS — the write must not be retried against a fresh read");
    }

    @Test
    @DisplayName("the unfenced workflowId overload ignores the epoch — it is for writers that are not a run")
    void workflowIdOverload_isUnfenced() {
        seed("w1u", WorkflowStatus.RUNNING);
        store.forceLeaseEpoch("w1u", 5);
        InstanceStatusWriter.write(store, "w1u", WorkflowStatus.WAITING_TIMER);
        assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance("w1u").orElseThrow().status());
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
