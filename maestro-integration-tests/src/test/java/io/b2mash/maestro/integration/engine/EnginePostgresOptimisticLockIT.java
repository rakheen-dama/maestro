package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pin for <b>BUG1</b> — the optimistic-lock version convention.
 *
 * <h2>Do not delete or weaken this suite</h2>
 * <p>Maestro once shipped a build where {@link io.b2mash.maestro.core.spi.WorkflowStore#updateInstance}
 * compared the caller's version against the stored row while the engine had
 * already pre-incremented it. Every first update against the JDBC store failed,
 * yet the whole build was green: every engine test ran against in-memory fakes
 * whose CAS used the other half of the mismatch. Only a live run caught it.
 *
 * <p>The canonical convention, restated:
 * <blockquote>the caller builds the complete new state including
 * {@code version = current + 1}; the store persists it if and only if the
 * stored row is still at {@code version - 1}.</blockquote>
 *
 * <p>Each half is pinned separately below — the happy path, the "caller forgot
 * to increment" shape that was the actual defect, and a genuine concurrent
 * conflict — plus an end-to-end engine run, because it is the composition of
 * engine and real store that the fakes could not check.
 */
@Tag("integration")
@DisplayName("The optimistic-lock version convention holds against real Postgres")
class EnginePostgresOptimisticLockIT extends PostgresIntegrationSupport {

    private MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("an update whose version is the stored version plus one is persisted")
    void preIncrementedVersion_isPersisted() {
        var instance = createInstance("optlock-happy");
        assertEquals(0, instance.version(), "a created instance starts at version 0");

        store.updateInstance(instance.toBuilder()
                .status(WorkflowStatus.WAITING_SIGNAL)
                .updatedAt(Instant.now())
                .version(instance.version() + 1)
                .build());

        var stored = store.getInstance(instance.workflowId()).orElseThrow();
        assertEquals(1, stored.version(), "the persisted row carries the caller's new version");
        assertEquals(WorkflowStatus.WAITING_SIGNAL, stored.status());

        // And the convention composes: the next writer reads 1 and writes 2.
        store.updateInstance(stored.toBuilder()
                .status(WorkflowStatus.RUNNING)
                .updatedAt(Instant.now())
                .version(stored.version() + 1)
                .build());
        assertEquals(2, store.getInstance(instance.workflowId()).orElseThrow().version());
    }

    @Test
    @DisplayName("an update that forgets to pre-increment the version is rejected — the BUG1 shape")
    void nonIncrementedVersion_throwsOptimisticLockException() {
        var instance = createInstance("optlock-noincrement");

        // Exactly what the buggy call site did: build the new state, keep the
        // version it read. Under the broken convention this silently "worked"
        // against one store and failed against the other. It must now be a hard
        // error everywhere.
        var notIncremented = instance.toBuilder()
                .status(WorkflowStatus.COMPLETED)
                .updatedAt(Instant.now())
                .build();

        var conflict = assertThrows(OptimisticLockException.class,
                () -> store.updateInstance(notIncremented));
        assertEquals(instance.workflowId(), conflict.workflowId());
        assertEquals(-1, conflict.expectedVersion(),
                "the store CASes against version - 1, so a version-0 write expects -1");
        assertEquals(0, conflict.actualVersion());

        var stored = store.getInstance(instance.workflowId()).orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, stored.status(), "a rejected update must not be applied");
        assertEquals(0, stored.version());
    }

    @Test
    @DisplayName("the loser of a concurrent update sees an OptimisticLockException and writes nothing")
    void concurrentUpdate_secondWriterThrowsOptimisticLockException() {
        var instance = createInstance("optlock-conflict");

        // Two readers each hold the same snapshot at version 0 and each builds
        // its own version 1 — the classic lost-update race.
        var winner = instance.toBuilder()
                .status(WorkflowStatus.WAITING_TIMER)
                .updatedAt(Instant.now())
                .version(instance.version() + 1)
                .build();
        var loser = instance.toBuilder()
                .status(WorkflowStatus.COMPENSATING)
                .updatedAt(Instant.now())
                .version(instance.version() + 1)
                .build();

        store.updateInstance(winner);
        var conflict = assertThrows(OptimisticLockException.class, () -> store.updateInstance(loser));

        assertEquals(instance.workflowId(), conflict.workflowId());
        assertEquals(0, conflict.expectedVersion(), "the loser expected the row to still be at version 0");
        assertEquals(1, conflict.actualVersion(), "the winner had already moved it to version 1");

        var stored = store.getInstance(instance.workflowId()).orElseThrow();
        assertEquals(WorkflowStatus.WAITING_TIMER, stored.status(), "the winner's state must survive");
        assertEquals(1, stored.version(), "a rejected update must not advance the version");
    }

    @Test
    @DisplayName("updating an instance that does not exist is a not-found error, not a version conflict")
    void unknownInstance_throwsWorkflowNotFound() {
        var ghost = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("optlock-ghost")
                .runId(UUID.randomUUID())
                .workflowType("ChainWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("optlock-node")
                .eventSequence(0)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(1)
                .build();

        assertThrows(WorkflowNotFoundException.class, () -> store.updateInstance(ghost));
    }

    @Test
    @DisplayName("a full engine run against Postgres completes, so every engine write obeys the convention")
    void engineRun_updatesInstanceWithoutVersionConflict() {
        var recorder = new CountingActivities.Recorder();
        harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("optlock-node")
                .lock(newLock())
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());

        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("optlock-engine"),
                TestWorkflows.ChainWorkflow.class, "seed");

        // Under the shipped mismatch the terminal update threw
        // OptimisticLockException and the workflow never reached COMPLETED —
        // this assertion is the whole regression pin at the composition level.
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)));

        var instance = handle.instance();
        assertEquals(1, instance.version(),
                "one engine status transition must move the row by exactly one version");
        assertTrue(instance.output() != null, "the terminal update must have persisted the output");
        assertEquals(3, recorder.invocations().size());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Creates a RUNNING instance at version 0 to update against.
     *
     * @param workflowId the business workflow ID
     * @return the persisted instance
     */
    private WorkflowInstance createInstance(String workflowId) {
        var now = Instant.now();
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("ChainWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("optlock-node")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }
}
