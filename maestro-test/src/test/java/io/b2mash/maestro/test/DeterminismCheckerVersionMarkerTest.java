package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies design §5.1's claim that {@link DeterminismChecker} treats version
 * markers as decisions <em>with no change to the checker</em>.
 *
 * <p>The claim rests on two facts, both asserted here rather than assumed: the
 * marker is a real event in the log (so it is fingerprinted at all), and its
 * step name carries the changeId (so resolving a different change at the same
 * slot is a divergence the checker can see).
 */
@DisplayName("DeterminismChecker and VERSION_MARKER")
class DeterminismCheckerVersionMarkerTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Steps.class, new FixedSteps());
        DriftingVersionWorkflow.RUNS.set(0);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    @DisplayName("a version marker is recorded as a decision in the event log")
    void versionMarkerIsRecordedAsADecision() throws Exception {
        var handle = env.startWorkflow("versioned-1", VersionedWorkflow.class, "order-1");
        handle.awaitCompletion(Duration.ofSeconds(5));

        var markers = handle.getEvents().stream()
                .filter(e -> e.eventType() == EventType.VERSION_MARKER)
                .toList();

        assertAll(
                () -> assertEquals(1, markers.size(),
                        "the run must record one marker, got: " + handle.getEvents()),
                () -> assertEquals("$maestro:version:shipping-v2", markers.getFirst().stepName(),
                        "the step name must carry the changeId — that is what puts the "
                                + "decision into the checker's fingerprint"),
                () -> assertTrue(markers.getFirst().sequenceNumber() > 0));
    }

    @Test
    @DisplayName("a workflow whose version decision is stable passes the check")
    void stableVersionDecisionPasses() {
        assertDoesNotThrow(() ->
                DeterminismChecker.assertDeterministic(env, VersionedWorkflow.class, "order-2"));
    }

    @Test
    @DisplayName("a workflow that resolves a different changeId per run is caught, and the marker names the divergence")
    void driftingVersionDecisionIsCaught() {
        var error = assertThrows(AssertionError.class, () ->
                DeterminismChecker.assertDeterministic(env, DriftingVersionWorkflow.class, "order-3"));

        assertAll(
                () -> assertTrue(error.getMessage().contains("is not deterministic"),
                        "message must say what failed, got: " + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("VERSION_MARKER"),
                        "the divergence must be reported at the version marker, got: "
                                + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("$maestro:version:"),
                        "the marker's step name must appear in the fingerprint, got: "
                                + error.getMessage()));
    }

    // ── fixtures ───────────────────────────────────────────────────────

    /** Activities used by the fixtures. */
    @Activity(name = "steps")
    public interface Steps {
        /**
         * @param order the order id
         * @return a fixed result
         */
        String reserve(String order);

        /**
         * @param order the order id
         * @return a fixed result
         */
        String ship(String order);
    }

    /** Trivial implementation — the checker cares about call order, not results. */
    public static final class FixedSteps implements Steps {
        @Override
        public String reserve(String order) {
            return "reserved";
        }

        @Override
        public String ship(String order) {
            return "shipped";
        }
    }

    /** Same changeId, same slot, every run. */
    @DurableWorkflow(name = "VersionedWorkflow")
    public static class VersionedWorkflow {

        @ActivityStub
        Steps steps;

        /**
         * @param order the order id
         * @return the final step's result
         */
        @WorkflowMethod
        public String run(String order) {
            steps.reserve(order);
            var version = WorkflowContext.current().version("shipping-v2", -1, 2);
            return steps.ship(order + ":v" + version);
        }
    }

    /**
     * Resolves a different changeId on alternate runs — the shape of a real
     * bug (a version call reached through a nondeterministic branch). The
     * marker's step name differs between runs, so the checker must catch it.
     */
    @DurableWorkflow(name = "DriftingVersionWorkflow")
    public static class DriftingVersionWorkflow {

        /** Incremented per run so each run resolves a different change. */
        static final AtomicInteger RUNS = new AtomicInteger();

        @ActivityStub
        Steps steps;

        /**
         * @param order the order id
         * @return the final step's result
         */
        @WorkflowMethod
        public String run(String order) {
            steps.reserve(order);
            var changeId = RUNS.getAndIncrement() % 2 == 0 ? "change-a" : "change-b";
            WorkflowContext.current().version(changeId, -1, 1);
            return steps.ship(order);
        }
    }
}
