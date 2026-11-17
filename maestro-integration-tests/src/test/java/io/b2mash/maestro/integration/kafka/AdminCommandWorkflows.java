package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    // ── WP3 (3.3): a caught failure, a fallback, then an uncaught failure ──

    /** The activities of {@link FallbackWorkflow} and {@link AckThenRethrowWorkflow}. */
    @Activity
    public interface FallbackActivities {

        /**
         * @param in the seed
         * @return a quote; fails while the outage lasts
         */
        String primary(String in);

        /**
         * @param in the seed
         * @return a cached quote; never fails
         */
        String fallback(String in);

        /**
         * @param quote the quote
         * @return the finished quote; fails while the outage lasts
         */
        String finish(String quote);
    }

    /**
     * {@code primary} and {@code finish} fail until {@link #fix()} ends the
     * outage; {@code fallback} always succeeds. Each method counts its calls.
     *
     * <p>Registered by component scan ({@code KafkaSignalTestApplication}'s
     * package), so, like {@link FlakyActivitiesImpl}, it exists in every P1
     * Kafka context and {@link FallbackWorkflow} can be wired in each. Only
     * {@code AdminCommandKafkaIT} drives it, resetting it in
     * {@code @BeforeEach} since the bean outlives any one test.
     *
     * <h2>Thread Safety</h2>
     * <p>All state is atomic; safe for concurrent calls.
     */
    @Component
    public static class FallbackActivitiesImpl implements FallbackActivities {

        private final AtomicBoolean outageOver = new AtomicBoolean(false);
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final AtomicInteger finishCalls = new AtomicInteger();

        @Override
        public String primary(String in) {
            primaryCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("pricing service down");
            }
            return in + "-primary";
        }

        @Override
        public String fallback(String in) {
            fallbackCalls.incrementAndGet();
            return in + "-cached";
        }

        @Override
        public String finish(String quote) {
            finishCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("ledger down");
            }
            return "done:" + quote;
        }

        /** Ends the outage: every subsequent {@code primary} and {@code finish} succeeds. */
        public void fix() {
            outageOver.set(true);
        }

        /** Clears state between tests — the bean is a context-wide singleton. */
        public void reset() {
            outageOver.set(false);
            primaryCalls.set(0);
            fallbackCalls.set(0);
            finishCalls.set(0);
        }

        /** @return how many times {@link #primary(String)} has run */
        public int primaryCalls() {
            return primaryCalls.get();
        }

        /** @return how many times {@link #fallback(String)} has run */
        public int fallbackCalls() {
            return fallbackCalls.get();
        }

        /** @return how many times {@link #finish(String)} has run */
        public int finishCalls() {
            return finishCalls.get();
        }
    }

    /**
     * The audit's 3.3 shape: a caught {@code primary} failure takes the
     * {@code fallback}, then {@code finish} fails uncaught. History before a
     * Retry: {@code 1 ACTIVITY_FAILED primary | 2 ACTIVITY_COMPLETED fallback |
     * 3 ACTIVITY_FAILED finish | 4 WORKFLOW_FAILED}. One attempt per activity,
     * so each failure is recorded at once.
     */
    @DurableWorkflow(name = "AdminFallbackWorkflow")
    public static class FallbackWorkflow {

        @ActivityStub(startToCloseTimeout = "PT5S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private FallbackActivities activities;

        /**
         * @param input the seed
         * @return the finished quote
         */
        @WorkflowMethod
        public String run(String input) {
            String quote;
            try {
                quote = activities.primary(input);
            } catch (RuntimeException e) {
                quote = activities.fallback(input);
            }
            return activities.finish(quote);
        }
    }

    /** Signal {@link AckThenRethrowWorkflow} awaits while handling its failure. */
    static final String ACK_SIGNAL = "ack";

    /**
     * MD-1's shape: the failure's handling consumes a signal before the
     * failure is rethrown. History before a Retry: {@code 1 ACTIVITY_FAILED
     * primary | 2 TIMER_SCHEDULED ack | 3 SIGNAL_RECEIVED ack |
     * 4 WORKFLOW_FAILED}. A Retry would have to give the signal back, so it is
     * refused with {@code SIGNAL_CONSUMED_AFTER_FAILURE}.
     */
    @DurableWorkflow(name = "AdminAckThenRethrowWorkflow")
    public static class AckThenRethrowWorkflow {

        @ActivityStub(startToCloseTimeout = "PT5S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private FallbackActivities activities;

        /**
         * @param input the seed
         * @return the quote, once {@code primary} succeeds
         */
        @WorkflowMethod
        public String run(String input) {
            try {
                return activities.primary(input);
            } catch (RuntimeException e) {
                WorkflowContext.current().awaitSignal(ACK_SIGNAL, String.class, Duration.ofSeconds(30));
                throw e;
            }
        }
    }
}
