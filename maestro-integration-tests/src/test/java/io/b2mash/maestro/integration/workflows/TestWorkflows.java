package io.b2mash.maestro.integration.workflows;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.TimerCancelledException;
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
     * {@link ChainWorkflow} carrying a constructor-supplied tag that a
     * {@code @QueryMethod} reports — so a query's answer names the exact
     * object (and therefore the node's factory) that served it.
     */
    @DurableWorkflow(name = "TaggedChainWorkflow")
    public static class TaggedChainWorkflow {

        private final String nodeTag;

        @ActivityStub
        ChainActivities activities;

        /**
         * @param nodeTag the tag {@link #tag()} reports, typically the building node's name
         */
        public TaggedChainWorkflow(String nodeTag) {
            this.nodeTag = nodeTag;
        }

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

        /**
         * @return the tag this object was constructed with
         */
        @QueryMethod
        public String tag() {
            return nodeTag;
        }
    }

    /**
     * {@link ChainWorkflow} with a {@code startToCloseTimeout} far above any
     * test bound — for fixtures that hold an activity attempt on a gate while
     * the test drives another node. Under the {@code PT30S} default a gate held
     * past 30 s would time the attempt out and change what is being tested.
     */
    @DurableWorkflow(name = "LongTimeoutChainWorkflow")
    public static class LongTimeoutChainWorkflow {

        @ActivityStub(startToCloseTimeout = "PT10M")
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

    /** Runs one activity, then throws a JVM Error — the fixture for C8. */
    @DurableWorkflow(name = "ErrorThrowingWorkflow")
    public static class ErrorThrowingWorkflow {

        @ActivityStub
        ChainActivities activities;

        /**
         * @param input the seed value
         * @return never returns normally
         */
        @WorkflowMethod
        public String run(String input) {
            activities.stepOne(input);
            throw new AssertionError("boom");
        }
    }

    /**
     * Runs one activity, then throws a {@link NoClassDefFoundError} — the
     * fixture for R20: a bad deploy (a class missing from this node's
     * classpath) is a node error, not a workflow failure.
     */
    @DurableWorkflow(name = "NodeErrorWorkflow")
    public static class NodeErrorWorkflow {

        @ActivityStub
        ChainActivities activities;

        /**
         * @param input the seed value
         * @return never returns normally
         */
        @WorkflowMethod
        public String run(String input) {
            activities.stepOne(input);
            throw new NoClassDefFoundError("com/acme/RemovedDependency");
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
     * Awaits one signal as its <em>first</em> step, with a caller-chosen
     * timeout — the fixture for the durable {@code awaitSignal} deadline (C12 /
     * F4) on a real store.
     *
     * <p>Because the await is the first sequenced call, its layout is fixed:
     * the {@code TIMER_SCHEDULED} memo is at seq 1, the outcome
     * ({@code SIGNAL_RECEIVED} or {@code SIGNAL_TIMEOUT}) at seq 2, and the
     * deadline row's timer id is {@code "await-signal-1"}. A timeout is caught
     * and returned as {@code "timed-out"}, so either outcome <em>completes</em>
     * the workflow and the event log alone says which one won.
     */
    @DurableWorkflow(name = "PatientSignalWorkflow")
    public static class PatientSignalWorkflow {

        /** Signal name this workflow awaits. */
        public static final String SIGNAL = "approval";

        private final Duration timeout;

        /** @param timeout the await's timeout — keep it far above any test bound */
        public PatientSignalWorkflow(Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * @param input unused seed
         * @return the signal payload, or {@code "timed-out"} if the deadline won
         */
        @WorkflowMethod
        public String run(String input) {
            try {
                return WorkflowContext.current().awaitSignal(SIGNAL, String.class, timeout);
            } catch (SignalTimeoutException e) {
                return "timed-out";
            }
        }
    }

    /**
     * Branches on {@code causedBy(ActivityTimeoutException.class)} — the C11
     * catch contract — then parks: the fixture for pinning that the replayed
     * branch equals the live one (plan 03 final review I2). The only attempt
     * times out (0.3 s against the fixture's slow first attempt); a timeout
     * returns {@code "timeout"}, anything else is rethrown (and would FAIL
     * the workflow).
     */
    @DurableWorkflow(name = "TimeoutBranchWorkflow")
    public static class TimeoutBranchWorkflow {

        @ActivityStub(startToCloseTimeout = "PT0.3S",
                retryPolicy = @io.b2mash.maestro.core.annotation.RetryPolicy(maxAttempts = 1))
        FlakyActivities activities;

        private final List<String> observations;
        private final CountDownLatch parked;

        /**
         * @param observations receives the branch taken, once per run (live, then replay)
         * @param parked       counted down once the workflow reaches its await point
         */
        public TimeoutBranchWorkflow(List<String> observations, CountDownLatch parked) {
            this.observations = observations;
            this.parked = parked;
        }

        /**
         * @param input the seed value
         * @return {@code "timeout"} when the activity timed out, else its result
         */
        @WorkflowMethod
        public String run(String input) {
            String outcome;
            try {
                outcome = activities.attempt(input);
            } catch (ActivityExecutionException e) {
                if (!e.causedBy(io.b2mash.maestro.core.exception.ActivityTimeoutException.class)) {
                    throw e;
                }
                outcome = "timeout";
            }
            observations.add(outcome);
            parked.countDown();
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(60));
            return outcome;
        }
    }

    /**
     * Catches an activity failure, records what the cause looks like, then parks:
     * the fixture for comparing the live and replayed view of a failure (C15).
     */
    @DurableWorkflow(name = "FailureReplayWorkflow")
    public static class FailureReplayWorkflow {

        @ActivityStub(retryPolicy = @io.b2mash.maestro.core.annotation.RetryPolicy(maxAttempts = 1))
        CountingActivities.FailingActivities failing;

        private final List<String> observations;
        private final CountDownLatch parked;

        /**
         * @param observations receives one entry per run (live, then replay)
         * @param parked       counted down once the workflow reaches its await point
         */
        public FailureReplayWorkflow(List<String> observations, CountDownLatch parked) {
            this.observations = observations;
            this.parked = parked;
        }

        /**
         * @param input the seed value
         * @return the observed cause description
         */
        @WorkflowMethod
        public String run(String input) {
            String seen;
            try {
                failing.explode(input);
                seen = "no-failure";
            } catch (ActivityExecutionException e) {
                seen = e.getCause().getClass().getName() + "|" + e.getCause().getMessage()
                        + (e.getCause() instanceof ReconstructedActivityFailure r
                        ? "|" + r.originalClassName() : "");
            }
            observations.add(seen);
            parked.countDown();
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
            return seen;
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
     * Sleeps like {@link SleepingWorkflow} but catches a cancelled timer and
     * takes a fallback branch instead of failing — the fixture for
     * {@link TimerCancelledException} determinism against a real Postgres
     * store (Issue 13).
     */
    @DurableWorkflow(name = "CancellableSleepWorkflow")
    public static class CancellableSleepWorkflow {

        @ActivityStub
        ChainActivities activities;

        private final Duration nap;

        /** @param nap how long the durable sleep should last */
        public CancellableSleepWorkflow(Duration nap) {
            this.nap = nap;
        }

        /**
         * @param input the seed value
         * @return the result of the post-sleep activity, tagged with whether
         *         the sleep fired normally or was cancelled
         */
        @WorkflowMethod
        public String run(String input) {
            var one = activities.stepOne(input);
            String outcome;
            try {
                WorkflowContext.current().sleep(nap);
                outcome = one + "-fired";
            } catch (TimerCancelledException e) {
                outcome = one + "-cancelled";
            }
            return activities.stepTwo(outcome);
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

    /**
     * {@link RetryWorkflow} with a backoff longer than any test bound — for
     * terminate/shutdown-during-backoff pins.
     */
    @DurableWorkflow(name = "SlowRetryWorkflow")
    public static class SlowRetryWorkflow {

        @ActivityStub(retryPolicy = @io.b2mash.maestro.core.annotation.RetryPolicy(
                maxAttempts = 5, initialInterval = "PT30S", maxInterval = "PT30S"))
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

    /**
     * {@link RetryWorkflow} with a short {@code startToCloseTimeout} and one
     * retry — a timed-out first attempt is retried (C11).
     */
    @DurableWorkflow(name = "TimeoutRetryWorkflow")
    public static class TimeoutRetryWorkflow {

        @ActivityStub(startToCloseTimeout = "PT0.3S",
                retryPolicy = @io.b2mash.maestro.core.annotation.RetryPolicy(
                        maxAttempts = 2, initialInterval = "PT0.1S", maxInterval = "PT0.1S"))
        FlakyActivities activities;

        /**
         * @param input the seed value
         * @return the result of the attempt that finished within the timeout
         */
        @WorkflowMethod
        public String run(String input) {
            return activities.attempt(input);
        }
    }

    /**
     * {@link RetryWorkflow} with a short {@code startToCloseTimeout} and no
     * retry — a timed-out attempt fails the activity (C11).
     */
    @DurableWorkflow(name = "TimeoutNoRetryWorkflow")
    public static class TimeoutNoRetryWorkflow {

        @ActivityStub(startToCloseTimeout = "PT0.3S",
                retryPolicy = @io.b2mash.maestro.core.annotation.RetryPolicy(maxAttempts = 1))
        FlakyActivities activities;

        /**
         * @param input the seed value
         * @return never returns normally against a slow first attempt
         */
        @WorkflowMethod
        public String run(String input) {
            return activities.attempt(input);
        }
    }
}
