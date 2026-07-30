package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Workflow and activity fixtures for {@link AdminCommandKafkaIT} — the Kafka
 * end-to-end suite for {@code $maestro:retry} / {@code $maestro:terminate}.
 *
 * <p>Lives alongside {@link KafkaTestWorkflows} for the same reason spelled
 * out in that class's Javadoc: this package is classpath-scanned by
 * {@code DurableWorkflowBeanRegistrar} in <em>every</em> P1 Kafka suite's
 * Spring context, not only {@code AdminCommandKafkaIT}'s. So
 * {@link FlakyWorkflow} — and therefore a bean implementing
 * {@link FlakyActivities} — must exist in every context or every other
 * suite's {@code ActivityStubBeanPostProcessor} wiring fails at startup. The
 * implementation bean is registered once, globally, in
 * {@link KafkaSignalTestApplication}; only {@code AdminCommandKafkaIT}
 * actually drives it.
 */
final class AdminCommandWorkflows {

    private AdminCommandWorkflows() {
    }

    /** An activity that fails until fixed — the retry command's target. */
    @Activity
    public interface FlakyActivities {

        /**
         * @param input the seed
         * @return the seed with a marker appended, once fixed
         */
        String risky(String input);
    }

    /**
     * Fails every call until {@link #fix()} is called — the test seam a real
     * operator's "the downstream dependency is back up" corresponds to.
     *
     * <p>A singleton bean shared by every Kafka suite's Spring context (see
     * class Javadoc); only {@code AdminCommandKafkaIT} exercises it, resetting
     * its state in {@code @BeforeEach} since the bean outlives any one test.
     */
    public static class FlakyActivitiesImpl implements FlakyActivities {

        private final AtomicBoolean fixed = new AtomicBoolean(false);
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String risky(String input) {
            attempts.incrementAndGet();
            if (!fixed.get()) {
                throw new IllegalStateException("downstream dependency unavailable");
            }
            return input + "-ok";
        }

        /** Makes every subsequent call succeed. */
        public void fix() {
            fixed.set(true);
        }

        /** Clears state between tests — the bean is a context-wide singleton. */
        public void reset() {
            fixed.set(false);
            attempts.set(0);
        }

        /** @return how many times {@link #risky(String)} has been invoked */
        public int attempts() {
            return attempts.get();
        }
    }

    /**
     * Calls the flaky activity once — fails (and exhausts its two-attempt
     * retry budget almost instantly) until the activity is fixed, then
     * completes. The retry command's target: start it while broken, let it
     * reach {@code FAILED}, fix the fault, then retry.
     */
    @DurableWorkflow(name = "AdminCommandFlakyWorkflow")
    public static class FlakyWorkflow {

        @ActivityStub(startToCloseTimeout = "PT5S",
                retryPolicy = @RetryPolicy(maxAttempts = 2, initialInterval = "PT0.02S", maxInterval = "PT0.02S"))
        private FlakyActivities activities;

        /**
         * @param input the seed
         * @return the activity's result
         */
        @WorkflowMethod
        public String run(String input) {
            return activities.risky(input);
        }
    }
}
