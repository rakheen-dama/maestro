package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.FlakyActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminate against a live activity, driven by the real engine against a real
 * Postgres store (C6, C11c).
 *
 * <p>The owning node's terminate sets the run's in-memory
 * {@code RunCancellation}: a retry backoff in progress is abandoned at once
 * and no further attempt starts. Asserted here against the durable record — a
 * {@code TERMINATED} row, and no {@code ACTIVITY_FAILED} or
 * {@code WORKFLOW_FAILED} event, because an abandoned backoff is not an
 * exhausted-retries failure.
 */
@Tag("integration")
@DisplayName("Terminate stops live activity work against a real Postgres store")
class EnginePostgresTerminateIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    private @Nullable MaestroEngineHarness harness;
    private CountingActivities.Recorder recorder;

    @BeforeEach
    void newRecorder() {
        recorder = new CountingActivities.Recorder();
    }

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    @DisplayName("terminate while a live activity is mid-retry backoff abandons the backoff; no further attempt runs")
    void terminateDuringRetryBackoff_runsNoFurtherAttempt() {
        harness = newHarness();
        harness.registerActivities(FlakyActivities.class,
                new CountingActivities.FailNTimesActivities(recorder, Integer.MAX_VALUE));
        harness.registerWorkflow(new TestWorkflows.SlowRetryWorkflow());
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("term-backoff");
        var handle = harness.start(workflowId, TestWorkflows.SlowRetryWorkflow.class, "x");
        await().atMost(BOUND).until(() -> recorder.count("attempt") == 1);

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                harness.executor().terminateWorkflow(workflowId, "ops"));

        await().atMost(Duration.ofSeconds(10)).until(() -> !handle.isRunningLocally());   // << the 30 s backoff
        assertEquals(1, recorder.count("attempt"));
        assertEquals(WorkflowStatus.TERMINATED, handle.status());
        assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.ACTIVITY_FAILED),
                "no ACTIVITY_FAILED may be persisted for an abandoned backoff");
        assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.WORKFLOW_FAILED));
    }

    /** @return a single-node harness with a Postgres-backed instance lock */
    private MaestroEngineHarness newHarness() {
        return MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("terminate-node")
                .lock(newLock())
                .observer(new RecordingObserver())
                .build();
    }
}
