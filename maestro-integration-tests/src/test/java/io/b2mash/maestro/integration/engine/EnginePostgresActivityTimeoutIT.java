package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code startToCloseTimeout} is enforced per attempt, driven by the real
 * engine against a real Postgres store (C11).
 *
 * <p>The first attempt sleeps 5 s against a 0.3 s timeout. With enforcement
 * the attempt is cancelled at the timeout and becomes an ordinary, retryable
 * {@link ActivityTimeoutException}: retried when the policy allows (and the
 * fast second attempt completes the workflow), persisted as
 * {@code ACTIVITY_FAILED} with the timeout's type when it does not. Both bounds
 * sit well under the 5 s sleep, so a build that does not enforce the timeout
 * fails them instead of passing slowly.
 */
@Tag("integration")
@DisplayName("startToCloseTimeout bounds each activity attempt against a real Postgres store")
class EnginePostgresActivityTimeoutIT extends PostgresIntegrationSupport {

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
    @DisplayName("an attempt exceeding startToCloseTimeout is retried; the second attempt completes the workflow")
    void timedOutFirstAttempt_isRetriedAndCompletes() {
        harness = newHarness();
        harness.registerActivities(FlakyActivities.class,
                new CountingActivities.SlowFirstAttemptActivities(recorder, Duration.ofSeconds(5)));
        harness.registerWorkflow(new TestWorkflows.TimeoutRetryWorkflow());
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("timeout-retry"),
                TestWorkflows.TimeoutRetryWorkflow.class, "x");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)));   // << 5 s sleep + retry
        assertEquals("x-ok-after-2", handle.result(String.class));
        assertEquals(2, recorder.count("attempt"));
        assertEquals(1, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count());
        assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.ACTIVITY_FAILED));
    }

    @Test
    @DisplayName("with maxAttempts = 1 a timeout is persisted as ACTIVITY_FAILED with the timeout's type and the workflow FAILS")
    void timedOutOnlyAttempt_persistsTimeoutFailure() {
        harness = newHarness();
        harness.registerActivities(FlakyActivities.class,
                new CountingActivities.SlowFirstAttemptActivities(recorder, Duration.ofSeconds(5)));
        harness.registerWorkflow(new TestWorkflows.TimeoutNoRetryWorkflow());
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("timeout-fail"),
                TestWorkflows.TimeoutNoRetryWorkflow.class, "x");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
        var failed = handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_FAILED).toList();
        assertEquals(1, failed.size());
        assertEquals(ActivityTimeoutException.class.getName(),
                failed.get(0).payload().get("exceptionType").stringValue());
        assertEquals(1, recorder.count("attempt"));
    }

    /** @return a single-node harness with a Postgres-backed instance lock */
    private MaestroEngineHarness newHarness() {
        return MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("timeout-node")
                .lock(newLock())
                .build();
    }
}
