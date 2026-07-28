package io.b2mash.maestro.integration.workflows;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.FlakyActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Deterministic workflow fixtures shared by the P0–P2 integration suites.
 *
 * <p>Every workflow here obeys the determinism constraint: no wall-clock reads,
 * no randomness and no I/O between activity calls. Coordination with the test
 * thread happens through latches, which are observation points only — they
 * never influence the persisted decision sequence.
 */
public final class TestWorkflows {

    private TestWorkflows() {
    }

    /**
     * Three activities in sequence — the baseline for memoization and replay
     * assertions.
     */
    @DurableWorkflow(name = "ChainWorkflow")
    public static class ChainWorkflow {

        @ActivityStub
        ChainActivities activities;

        /**
         * @param input the seed value
         * @return the accumulated result of all three steps
         */
        @WorkflowMethod
        public String run(String input) {
            var one = activities.stepOne(input);
            var two = activities.stepTwo(one);
            return activities.stepThree(two);
        }
    }

    /**
     * Runs one activity, parks until a signal arrives, then runs another —
     * the fixture for park/wake, pre-arrived signals and orphan adoption.
     */
    @DurableWorkflow(name = "SignalWorkflow")
    public static class SignalWorkflow {

        /** Signal name this workflow awaits. */
        public static final String SIGNAL = "approval";

        @ActivityStub
        ChainActivities activities;

        private final CountDownLatch parked;

        /** @param parked counted down once the workflow reaches its await point */
        public SignalWorkflow(CountDownLatch parked) {
            this.parked = parked;
        }

        /**
         * @param input the seed value
         * @return the signal payload combined with the activity results
         */
        @WorkflowMethod
        public String run(String input) {
            var one = activities.stepOne(input);
            parked.countDown();
            var decision = WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30));
            return activities.stepTwo(one + ":" + decision);
        }
    }

    /**
     * Collects N signals before proceeding — the fixture for {@code collectSignals}
     * FIFO ordering on a real store.
     */
    @DurableWorkflow(name = "CollectingWorkflow")
    public static class CollectingWorkflow {

        /** Signal name this workflow collects. */
        public static final String SIGNAL = "vote";

        private final int count;
        private final CountDownLatch parked;

        /**
         * @param count  how many signals to collect
         * @param parked counted down once the workflow reaches its await point
         */
        public CollectingWorkflow(int count, CountDownLatch parked) {
            this.count = count;
            this.parked = parked;
        }

        /**
         * @param input unused seed
         * @return the collected payloads joined in receipt order
         */
        @WorkflowMethod
        public String run(String input) {
            parked.countDown();
            List<String> votes = WorkflowContext.current()
                    .collectSignals(SIGNAL, String.class, count, Duration.ofSeconds(30));
            return String.join(",", votes);
        }
    }

    /**
     * Sleeps on a durable timer between two activities — the fixture for timer
     * persistence, poller firing and timer-survives-restart.
     */
    @DurableWorkflow(name = "SleepingWorkflow")
    public static class SleepingWorkflow {

        @ActivityStub
        ChainActivities activities;

        private final Duration nap;

        /** @param nap how long the durable sleep should last */
        public SleepingWorkflow(Duration nap) {
            this.nap = nap;
        }

        /**
         * @param input the seed value
         * @return the result of the post-sleep activity
         */
        @WorkflowMethod
        public String run(String input) {
            var one = activities.stepOne(input);
            WorkflowContext.current().sleep(nap);
            return activities.stepTwo(one);
        }
    }

    /**
     * Fans out into parallel branches — the fixture for sequence-block
     * partitioning, which is unexercised outside core unit tests.
     */
    @DurableWorkflow(name = "ParallelWorkflow")
    public static class ParallelWorkflow {

        @ActivityStub
        ChainActivities activities;

        /**
         * @param input the seed value
         * @return the branch results joined in branch order
         */
        @WorkflowMethod
        public String run(String input) {
            List<Callable<String>> branches = List.of(
                    () -> activities.stepOne(input),
                    () -> activities.stepTwo(input),
                    () -> activities.stepThree(input));
            return String.join(",", WorkflowContext.current().parallel(branches));
        }
    }

    /**
     * A saga whose third step fails, forcing LIFO compensation of the first two
     * — the fixture for COMPENSATING transitions on a real store.
     */
    @DurableWorkflow(name = "SagaWorkflow")
    public static class SagaWorkflow {

        @ActivityStub
        SagaActivities activities;

        /**
         * @param item the item being ordered
         * @return the shipping result when no step fails
         */
        @Saga
        @WorkflowMethod
        public String run(String item) {
            activities.reserve(item);
            activities.charge(item);
            return activities.ship(item);
        }
    }

    /**
     * Invokes an activity that fails a few times before succeeding — the fixture
     * for retry policy against a real store.
     */
    @DurableWorkflow(name = "RetryWorkflow")
    public static class RetryWorkflow {

        @ActivityStub
        FlakyActivities activities;

        /**
         * @param input the seed value
         * @return the eventual successful result
         */
        @WorkflowMethod
        public String run(String input) {
            return activities.attempt(input);
        }
    }
}
