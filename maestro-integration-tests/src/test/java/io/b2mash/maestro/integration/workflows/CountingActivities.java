package io.b2mash.maestro.integration.workflows;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Activity fixtures whose invocations are counted.
 *
 * <p>Counting is what makes memoization directly assertable: after a crash and
 * replay, a completed step must be served from the event log, so its counter
 * must not advance. A test that only checks the final output cannot tell
 * replay from re-execution.
 */
public final class CountingActivities {

    private CountingActivities() {
    }

    /** Activity interface for the straight-line chain fixtures. */
    @Activity(name = "chain")
    public interface ChainActivities {
        String stepOne(String input);

        String stepTwo(String input);

        String stepThree(String input);
    }

    /** Activity interface for saga fixtures — each step has a compensator. */
    @Activity(name = "saga")
    public interface SagaActivities {
        @Compensate("releaseReservation")
        String reserve(String item);

        void releaseReservation(String item);

        @Compensate("refund")
        String charge(String item);

        void refund(String item);

        String ship(String item);
    }

    /** Activity interface for retry fixtures. */
    @Activity(name = "flaky")
    public interface FlakyActivities {
        String attempt(String input);
    }

    /**
     * Records every invocation so tests can assert exact call counts per step.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent invocation — parallel-branch fixtures call it from
     * several virtual threads at once.
     */
    public static final class Recorder {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();

        /**
         * Records one invocation of a named step.
         *
         * @param step the step name
         * @return the invocation count for that step, after recording
         */
        public int record(String step) {
            order.add(step);
            return counts.computeIfAbsent(step, k -> new AtomicInteger()).incrementAndGet();
        }

        /**
         * @param step the step name
         * @return how many times the step ran
         */
        public int count(String step) {
            var counter = counts.get(step);
            return counter == null ? 0 : counter.get();
        }

        /** @return every recorded invocation, in order */
        public List<String> invocations() {
            return List.copyOf(order);
        }

        /** Clears all recorded state. */
        public void reset() {
            counts.clear();
            order.clear();
        }
    }

    /** Chain activities that record each step. */
    public static final class RecordingChainActivities implements ChainActivities {
        private final Recorder recorder;

        /** @param recorder the shared recorder */
        public RecordingChainActivities(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public String stepOne(String input) {
            recorder.record("stepOne");
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            recorder.record("stepTwo");
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            recorder.record("stepThree");
            return input + "-three";
        }
    }

    /**
     * Saga activities that record each step and each compensation, and can be
     * told to fail at a chosen step.
     */
    public static final class RecordingSagaActivities implements SagaActivities {
        private final Recorder recorder;
        private final String failAtStep;

        /**
         * @param recorder   the shared recorder
         * @param failAtStep step name that should throw, or {@code null} to never fail
         */
        public RecordingSagaActivities(Recorder recorder, String failAtStep) {
            this.recorder = recorder;
            this.failAtStep = failAtStep;
        }

        @Override
        public String reserve(String item) {
            recorder.record("reserve");
            failIfRequested("reserve");
            return "reserved:" + item;
        }

        @Override
        public void releaseReservation(String item) {
            recorder.record("releaseReservation");
        }

        @Override
        public String charge(String item) {
            recorder.record("charge");
            failIfRequested("charge");
            return "charged:" + item;
        }

        @Override
        public void refund(String item) {
            recorder.record("refund");
        }

        @Override
        public String ship(String item) {
            recorder.record("ship");
            failIfRequested("ship");
            return "shipped:" + item;
        }

        private void failIfRequested(String step) {
            if (step.equals(failAtStep)) {
                throw new IllegalStateException("Injected failure at step " + step);
            }
        }
    }

    /**
     * A flaky activity that fails a fixed number of times before succeeding —
     * the fixture for retry-policy assertions.
     */
    public static final class FailNTimesActivities implements FlakyActivities {
        private final Recorder recorder;
        private final int failures;

        /**
         * @param recorder the shared recorder
         * @param failures how many invocations should throw before one succeeds
         */
        public FailNTimesActivities(Recorder recorder, int failures) {
            this.recorder = recorder;
            this.failures = failures;
        }

        @Override
        public String attempt(String input) {
            var attempt = recorder.record("attempt");
            if (attempt <= failures) {
                throw new IllegalStateException("Injected failure, attempt " + attempt);
            }
            return input + "-ok-after-" + attempt;
        }
    }
}
