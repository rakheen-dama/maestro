package io.b2mash.maestro.integration.shutdown;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Workflow fixtures specific to the shutdown contract (P5).
 *
 * <p>{@code workflows.TestWorkflows} covers the shapes P0–P2 needed; the one
 * shape it lacks is a saga that has <em>already committed compensable work</em>
 * and is then parked. That is the shape where the shutdown bug is most
 * expensive — a graceful deploy would refund a customer and release their stock
 * for a workflow that was simply waiting for an approval — so it lives here.
 *
 * <p>Every fixture obeys the determinism constraint: no wall-clock reads, no
 * randomness and no I/O between activity calls. The latch is an observation
 * point for the test thread only; it never influences the persisted decision
 * sequence.
 */
public final class ShutdownWorkflows {

    private ShutdownWorkflows() {
    }

    /**
     * Reserves stock and charges the customer — both compensable — then parks
     * awaiting an approval signal before shipping.
     *
     * <p>Shutting down at the park must leave {@code reserve} and {@code charge}
     * standing. Compensating them would undo committed business work for a
     * workflow that has not failed.
     */
    @DurableWorkflow(name = "ParkingSagaWorkflow")
    public static class ParkingSagaWorkflow {

        /** Signal this workflow awaits before shipping. */
        public static final String SIGNAL = "approval";

        @ActivityStub
        SagaActivities activities;

        private final CountDownLatch parked;

        /** @param parked counted down once the workflow reaches its await point */
        public ParkingSagaWorkflow(CountDownLatch parked) {
            this.parked = parked;
        }

        /**
         * @param item the item being ordered
         * @return the shipping result, once approval arrives
         */
        @Saga
        @WorkflowMethod
        public String run(String item) {
            activities.reserve(item);
            activities.charge(item);
            parked.countDown();
            var decision = WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofMinutes(10));
            return activities.ship(item + ":" + decision);
        }
    }
}
