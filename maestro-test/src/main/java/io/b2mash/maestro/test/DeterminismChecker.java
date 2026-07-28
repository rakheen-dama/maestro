package io.b2mash.maestro.test;

import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Checks that a workflow's decision sequence is reproducible.
 *
 * <p>Maestro's durability rests on an assumption the engine cannot enforce:
 * code between activity calls is deterministic. Break it — branch on
 * {@code Math.random()}, on {@code Instant.now()}, on a mutable field, on map
 * iteration order — and recovery replays a different path than the one the
 * event log records. Nothing detects that today; divergence is silent.
 *
 * <p>This checker runs the same workflow several times from a clean state and
 * compares the resulting event logs. A workflow whose decisions depend on
 * anything but its input produces different step sequences across runs, and the
 * comparison fails with the first point of divergence.
 *
 * <h2>What this does and does not catch</h2>
 * <p>It catches nondeterminism that varies <em>between runs</em>: randomness,
 * wall-clock reads, static mutable state, iteration-order dependence. It does
 * not catch nondeterminism that is stable within a JVM but differs after a
 * restart or a code change — for example branching on a value that happens to
 * be constant for the lifetime of the process. Treat a pass as evidence, not
 * proof.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var env = TestWorkflowEnvironment.create();
 * env.registerActivities(OrderActivities.class, new FakeOrderActivities());
 * DeterminismChecker.assertDeterministic(env, OrderWorkflow.class, input);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the environment it drives is not, so use one environment per
 * check on one thread.
 */
public final class DeterminismChecker {

    private static final int DEFAULT_RUNS = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private DeterminismChecker() {
    }

    /**
     * Runs the workflow three times and asserts every run took the same path.
     *
     * @param env           the environment to run in
     * @param workflowClass the workflow under test
     * @param input         the input, identical for every run
     * @throws AssertionError if two runs diverge, or if a run does not complete
     */
    public static void assertDeterministic(
            TestWorkflowEnvironment env, Class<?> workflowClass, @Nullable Object input) {
        assertDeterministic(env, workflowClass, input, DEFAULT_RUNS, DEFAULT_TIMEOUT);
    }

    /**
     * Runs the workflow the given number of times and asserts every run took the
     * same path.
     *
     * @param env           the environment to run in
     * @param workflowClass the workflow under test
     * @param input         the input, identical for every run
     * @param runs          how many times to run it; must be at least two
     * @param timeout       how long each run may take
     * @throws AssertionError           if two runs diverge, or a run does not complete
     * @throws IllegalArgumentException if {@code runs} is less than two
     */
    public static void assertDeterministic(
            TestWorkflowEnvironment env, Class<?> workflowClass, @Nullable Object input,
            int runs, Duration timeout) {

        if (runs < 2) {
            throw new IllegalArgumentException(
                    "A determinism check needs at least two runs to compare, got " + runs);
        }

        var baseline = fingerprint(env, workflowClass, input, 0, timeout);
        for (var run = 1; run < runs; run++) {
            var candidate = fingerprint(env, workflowClass, input, run, timeout);
            var divergence = firstDivergence(baseline, candidate);
            if (divergence != null) {
                throw new AssertionError(
                        "%s is not deterministic: run 1 and run %d diverge.%n%s%n%nRun 1:   %s%nRun %d:   %s"
                                .formatted(workflowClass.getSimpleName(), run + 1, divergence,
                                        baseline, run + 1, candidate));
            }
        }
    }

    /**
     * Runs the workflow once and returns its decision sequence.
     *
     * @param env           the environment to run in
     * @param workflowClass the workflow under test
     * @param input         the workflow input
     * @param run           the run index, used to build a unique workflow ID
     * @param timeout       how long the run may take
     * @return one {@code sequence:eventType:stepName} entry per recorded event
     */
    private static List<String> fingerprint(
            TestWorkflowEnvironment env, Class<?> workflowClass, @Nullable Object input,
            int run, Duration timeout) {

        var workflowId = "determinism-%s-%d".formatted(workflowClass.getSimpleName(), run);
        var handle = env.startWorkflow(workflowId, workflowClass, input);
        try {
            handle.awaitCompletion(timeout);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Run %d of %s did not complete within %s — a determinism check needs "
                            .formatted(run + 1, workflowClass.getSimpleName(), timeout)
                            + "workflows that finish without external input", e);
        }
        return decisions(handle.getEvents());
    }

    /**
     * @param events the recorded event log
     * @return the decision sequence, one entry per event
     */
    private static List<String> decisions(List<WorkflowEvent> events) {
        var decisions = new ArrayList<String>(events.size());
        for (var event : events) {
            decisions.add("%d:%s:%s".formatted(
                    event.sequenceNumber(), event.eventType(),
                    event.stepName() == null ? "-" : event.stepName()));
        }
        return decisions;
    }

    /**
     * @param baseline  the first run's decisions
     * @param candidate a later run's decisions
     * @return a description of the first difference, or {@code null} if identical
     */
    private static @Nullable String firstDivergence(List<String> baseline, List<String> candidate) {
        var shared = Math.min(baseline.size(), candidate.size());
        for (var i = 0; i < shared; i++) {
            if (!baseline.get(i).equals(candidate.get(i))) {
                return "First difference at position %d: expected '%s' but the later run recorded '%s'"
                        .formatted(i, baseline.get(i), candidate.get(i));
            }
        }
        if (baseline.size() != candidate.size()) {
            return "Runs recorded different numbers of events: %d then %d"
                    .formatted(baseline.size(), candidate.size());
        }
        return null;
    }
}
