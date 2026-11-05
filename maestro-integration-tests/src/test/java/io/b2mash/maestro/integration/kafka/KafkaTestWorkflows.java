package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;

/**
 * Workflow fixtures for the P1 Kafka suites.
 *
 * <p>These live in the application's package so that
 * {@code DurableWorkflowBeanRegistrar} registers them as beans in every P1
 * context — that scan is itself part of the path under test. They are therefore
 * shared by all suites, which is why each has a distinct
 * {@code @DurableWorkflow} name: {@code WorkflowRegistrar} rejects duplicates.
 *
 * <p>No latches: a Spring-managed workflow bean is a singleton shared by every
 * suite, so observation happens through the persisted instance row instead.
 *
 * <h2>Thread Safety</h2>
 * <p>All fixtures are stateless and safe for concurrent execution on the
 * engine's virtual threads.
 */
public final class KafkaTestWorkflows {

    /** Signal name every Kafka-routed fixture awaits. */
    static final String APPROVAL_SIGNAL = "approval";

    /**
     * How long the fixtures wait for their signal. Long enough that a slow
     * broker cannot fail a test, short enough that a leftover parked workflow
     * from a failing test does not outlive the suite.
     */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    private KafkaTestWorkflows() {
    }

    /**
     * Parks on {@code approval} and echoes the payload it receives — the target
     * of every signal-ingestion path in P1.
     */
    @DurableWorkflow(name = "KafkaApprovalWorkflow")
    public static class ApprovalWorkflow {

        /**
         * @param input the seed value
         * @return the seed joined with the signal payload
         */
        @WorkflowMethod
        public String run(String input) {
            var decision = WorkflowContext.current()
                    .awaitSignal(APPROVAL_SIGNAL, String.class, AWAIT_TIMEOUT);
            return input + ":" + decision;
        }
    }

    /**
     * Collects two {@code approval} signals ({@code collectSignals(name, 2)})
     * and joins their payloads — a quorum: two signals with identical payloads
     * but distinct message ids are two votes, never one.
     */
    @DurableWorkflow(name = "KafkaQuorumWorkflow")
    public static class QuorumWorkflow {

        /**
         * @param input the seed value
         * @return the seed joined with both payloads
         */
        @WorkflowMethod
        public String run(String input) {
            var votes = WorkflowContext.current()
                    .collectSignals(APPROVAL_SIGNAL, String.class, 2, AWAIT_TIMEOUT);
            return input + ":" + String.join(",", votes);
        }
    }

    /**
     * Completes immediately — the fixture for lifecycle events that need a
     * clean start/complete pair with no signal in the way.
     */
    @DurableWorkflow(name = "KafkaImmediateWorkflow")
    public static class ImmediateWorkflow {

        /**
         * @param input the seed value
         * @return the seed, unchanged
         */
        @WorkflowMethod
        public String run(String input) {
            return input;
        }
    }

    /**
     * Always throws — the fixture for the {@code WORKFLOW_FAILED} lifecycle
     * event.
     */
    @DurableWorkflow(name = "KafkaExplodingWorkflow")
    public static class ExplodingWorkflow {

        /** Message carried by the failure this fixture provokes. */
        static final String BOOM = "deliberate failure for the lifecycle suite";

        /**
         * @param input unused seed
         * @return never — always throws
         */
        @WorkflowMethod
        public String run(String input) {
            throw new IllegalStateException(BOOM);
        }
    }

    /**
     * Ten sequential activity calls — twenty {@code ACTIVITY_*} lifecycle
     * events plus the workflow's own pair — the fixture
     * {@code KafkaLifecycleEventLatencyIT} drives against a missing admin-events
     * topic. Before lifecycle publishing left the workflow thread, each of
     * those publishes blocked for the producer's {@code max.block.ms}.
     */
    @DurableWorkflow(name = "KafkaTenStepWorkflow")
    public static class TenStepWorkflow {

        /** How many activity calls the workflow makes. */
        public static final int STEPS = 10;

        @ActivityStub(startToCloseTimeout = "PT10S")
        private TenStepActivities activities;

        /**
         * @param input the seed value
         * @return the seed with every step number appended
         */
        @WorkflowMethod
        public String run(String input) {
            var carry = input;
            for (int step = 1; step <= STEPS; step++) {
                carry = activities.step(carry, step);
            }
            return carry;
        }
    }

    /** The activity {@link TenStepWorkflow} calls ten times. */
    public interface TenStepActivities {

        /**
         * @param carry the value so far
         * @param step  the 1-based step number
         * @return {@code carry} with the step appended
         */
        String step(String carry, int step);
    }

    /**
     * Trivial, stateless {@link TenStepActivities}; declared as a bean in
     * {@link KafkaSignalTestApplication} so every P1 context can wire the
     * scanned {@link TenStepWorkflow} (the Spring starter resolves activity
     * beans at discovery), and registered directly on the engine harness by
     * the latency suite.
     */
    public static class TenStepActivitiesImpl implements TenStepActivities {
        @Override
        public String step(String carry, int step) {
            return carry + ":" + step;
        }
    }
}
