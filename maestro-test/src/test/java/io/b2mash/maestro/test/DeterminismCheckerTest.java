package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeterminismChecker} — the guardrail itself must be trusted
 * before it can vouch for anything else, so this covers both directions: a
 * deterministic workflow passes, and a nondeterministic one is caught with a
 * message that names the divergence.
 */
@DisplayName("DeterminismChecker")
class DeterminismCheckerTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Steps.class, new RecordingSteps());
        RandomBranchWorkflow.CALLS.set(0);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    @DisplayName("a workflow whose path depends only on its input passes")
    void deterministicWorkflowPasses() {
        assertDoesNotThrow(() ->
                DeterminismChecker.assertDeterministic(env, DeterministicWorkflow.class, "order-1"));
    }

    @Test
    @DisplayName("a workflow that branches on mutable state is caught, and the failure names the divergence")
    void nondeterministicWorkflowIsCaught() {
        var error = assertThrows(AssertionError.class, () ->
                DeterminismChecker.assertDeterministic(env, RandomBranchWorkflow.class, "order-2"));

        assertAll(
                () -> assertTrue(error.getMessage().contains("is not deterministic"),
                        "message must say what failed, got: " + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("First difference at position"),
                        "message must locate the divergence, got: " + error.getMessage()));
    }

    @Test
    @DisplayName("fewer than two runs cannot prove anything and is rejected")
    void singleRunIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                DeterminismChecker.assertDeterministic(
                        env, DeterministicWorkflow.class, "order-3", 1, java.time.Duration.ofSeconds(5)));
    }

    // ── fixtures ───────────────────────────────────────────────────────

    /** Activities used by both fixtures. */
    @Activity(name = "steps")
    public interface Steps {
        String reserve(String order);

        String charge(String order);

        String ship(String order);
    }

    /** Trivial implementation — the checker cares about call order, not results. */
    public static final class RecordingSteps implements Steps {
        @Override
        public String reserve(String order) {
            return "reserved";
        }

        @Override
        public String charge(String order) {
            return "charged";
        }

        @Override
        public String ship(String order) {
            return "shipped";
        }
    }

    /** Same input, same path, every time. */
    @DurableWorkflow(name = "DeterministicWorkflow")
    public static class DeterministicWorkflow {

        @ActivityStub
        Steps steps;

        /**
         * @param order the order id
         * @return the final step's result
         */
        @WorkflowMethod
        public String run(String order) {
            steps.reserve(order);
            steps.charge(order);
            return steps.ship(order);
        }
    }

    /**
     * Branches on static mutable state, so consecutive runs take different
     * paths — the shape of a real nondeterminism bug (a clock read, a random
     * draw, a cached flag) without the flakiness of actually using one.
     */
    @DurableWorkflow(name = "RandomBranchWorkflow")
    public static class RandomBranchWorkflow {

        /** Incremented per run so each run takes a different branch. */
        static final AtomicInteger CALLS = new AtomicInteger();

        @ActivityStub
        Steps steps;

        /**
         * @param order the order id
         * @return the final step's result
         */
        @WorkflowMethod
        public String run(String order) {
            steps.reserve(order);
            if (CALLS.getAndIncrement() % 2 == 0) {
                steps.charge(order);
            }
            return steps.ship(order);
        }
    }
}
