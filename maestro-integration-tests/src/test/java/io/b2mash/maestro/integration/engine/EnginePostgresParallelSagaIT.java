package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Saga compensation after a failed {@code parallel()} fork, driven by the real
 * engine against a real Postgres store (C1).
 *
 * <p>The sequence numbers asserted here are the invariant: after the fork
 * throws, the parent counter sits past every branch block, so the saga's
 * compensation block does not coincide with a sibling branch's block.
 */
@Tag("integration")
@DisplayName("Saga compensation after a failed parallel() fork, against a real Postgres store")
class EnginePostgresParallelSagaIT extends PostgresIntegrationSupport {

    private @Nullable MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    /** Reserve and charge fork in parallel, then ship; the saga compensates on failure. */
    @DurableWorkflow(name = "ParallelSagaWorkflow")
    public static class ParallelSagaWorkflow {

        @ActivityStub
        SagaActivities activities;

        /**
         * @param item the item being ordered
         * @return the shipping result when no step fails
         */
        @Saga
        @WorkflowMethod
        public String run(String item) {
            WorkflowContext.current().parallel(List.<Callable<String>>of(
                    () -> activities.reserve(item), () -> activities.charge(item)));
            return activities.ship(item);
        }
    }

    @Test
    @DisplayName("reserve ∥ charge, charge fails: releaseReservation runs once and lands past the branch space")
    void failedBranch_compensatesInItsOwnBlock() {
        var recorder = new CountingActivities.Recorder();
        harness = node("parallel-saga", new CountingActivities.RecordingSagaActivities(recorder, "charge"));
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("parallel-saga"),
                ParallelSagaWorkflow.class, "widget");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
        assertEquals(1, recorder.count("reserve"));
        assertEquals(3, recorder.count("charge"), "the fixture's @ActivityStub default is three attempts");
        assertEquals(0, recorder.count("ship"), "the fork failed, so ship never ran");
        assertEquals(1, recorder.count("releaseReservation"),
                "the compensation must execute, not replay saga.charge's failure");
        assertEquals(0, recorder.count("refund"), "charge never succeeded, so nothing to refund");
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "2001:ACTIVITY_COMPLETED:saga.reserve",
                "3001:ACTIVITY_FAILED:saga.charge",
                "4001:COMPENSATION_STARTED:$maestro:compensation",
                "4002000:COMPENSATION_STEP_COMPLETED:saga.releaseReservation",
                "4002001:ACTIVITY_COMPLETED:saga.releaseReservation",
                "4003001:COMPENSATION_COMPLETED:$maestro:compensation",
                "4003002:WORKFLOW_FAILED:null"),
                describe(handle));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private MaestroEngineHarness node(String serviceName, SagaActivities activities) {
        var built = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(Duration.ofSeconds(2))
                .build();
        built.registerActivities(SagaActivities.class, activities);
        built.registerWorkflow(new ParallelSagaWorkflow());
        return built;
    }

    private static List<String> describe(WorkflowHandle handle) {
        return handle.events().stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }
}
