package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.FaultInjectingDataSource;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 26 "done when", on real Postgres: fault-inject the store between the
 * terminal status write and the terminal event write, and prove the only two
 * outcomes are "both visible" or "neither visible" — never a terminal status
 * with its terminal event missing.
 *
 * <p>{@link FaultInjectingDataSource} fails the event {@code INSERT} inside
 * {@code completeInstance}'s transaction, <em>after</em> the status
 * {@code UPDATE} has executed on the same connection. Every terminal write the
 * run attempts (the {@code COMPLETED} one, and the {@code FAILED} one its
 * failure handling then tries) therefore fails mid-transaction. The pin is that
 * the instance stays non-terminal and recoverable with no terminal event, and
 * that once the fault clears, ordinary recovery replays the memoized steps and
 * finalises the workflow exactly once.
 */
@Tag("integration")
@DisplayName("Issue 26: the terminal write is atomic against a real mid-transaction failure")
class TerminalWriteAtomicityIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    private CountingActivities.Recorder recorder;

    @BeforeEach
    void newRecorder() {
        recorder = new CountingActivities.Recorder();
    }

    @Test
    @DisplayName("Issue 26: a failed terminal write leaves the instance non-terminal and recoverable; recovery then finalises it")
    void failedTerminalWrite_leavesInstanceRecoverable_thenRecoveryCompletesIt() {
        var faulty = new FaultInjectingDataSource(newDataSource());
        var faultyStore = new PostgresWorkflowStore(faulty, objectMapper);
        faulty.failEventInsertsInsideTransactions();
        try (var harness = MaestroEngineHarness.builder(faultyStore, objectMapper).build()) {
            harness.registerActivities(CountingActivities.ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
            var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("atomic"),
                    TestWorkflows.ChainWorkflow.class, "seed");

            await().atMost(BOUND).until(() -> faulty.injectedFailures() >= 1);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                    .until(() -> !store.getInstance(handle.workflowId()).orElseThrow().status().isTerminal());
            assertTrue(store.getEvents(handle.instanceId()).stream()
                            .noneMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED
                                    || e.eventType() == EventType.WORKFLOW_FAILED),
                    "the rolled-back transaction must not have left a terminal event behind");
            assertTrue(store.getRecoverableInstances().stream()
                            .anyMatch(i -> i.workflowId().equals(handle.workflowId())),
                    "a workflow whose terminal write failed must stay recoverable");

            faulty.disarm();
            await().atMost(BOUND).until(() -> !harness.executor().isRunning(handle.workflowId()));
            assertEquals(1, harness.recover());
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
            assertEquals(1, handle.events().stream()
                    .filter(e -> e.eventType() == EventType.WORKFLOW_COMPLETED).count());
            assertEquals(0, handle.events().stream()
                    .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count(),
                    "the failed FAILED write must not have left a WORKFLOW_FAILED either");
            assertEquals(3, handle.events().stream()
                            .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
                    "the three activity memos were replayed, not re-executed, by the recovering run");
            assertEquals(1, recorder.count("stepOne"), "stepOne must execute exactly once across both runs");
            assertEquals(1, recorder.count("stepTwo"), "stepTwo must execute exactly once across both runs");
            assertEquals(1, recorder.count("stepThree"), "stepThree must execute exactly once across both runs");
            assertTrue(store.getRecoverableInstances().stream()
                            .noneMatch(i -> i.workflowId().equals(handle.workflowId())),
                    "a finalised workflow must never be re-entered by recovery");
        }
    }
}
