package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 36: {@link TestWorkflowEnvironment} takes opt-in concurrency limits,
 * so a test can see what only a bounded node shows — here {@code docs/pitfalls.md}
 * rule 11, an activity that starts a workflow on its own task queue and waits
 * for it. At limits (1, 1) the parent's run holds the queue's only slot while
 * its activity waits, so the child never starts and the wait times out.
 */
class TestWorkflowEnvironmentLimitsTest {

    private static final Duration CHILD_WAIT = Duration.ofSeconds(1);

    private TestWorkflowEnvironment env;
    private final CompletableFuture<Object> outcome = new CompletableFuture<>();

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void aSameQueueChildWaitedOnInsideAnActivityTimesOutAtLimitsOneOne() throws Exception {
        env = TestWorkflowEnvironment.builder().defaultConcurrency(1, 1).build();
        env.registerActivities(Spawner.class, new SpawnerImpl(env, CHILD_WAIT, outcome, ChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        assertInstanceOf(TimeoutException.class, outcome.get(10, SECONDS));
    }

    @Test
    void theSameChildCompletesWithNoLimits() throws Exception {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Spawner.class, new SpawnerImpl(env, CHILD_WAIT, outcome, ChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        assertEquals("child-done:order-1", outcome.get(10, SECONDS));
    }

    @Test
    void theSameChildCompletesWithLimitsZeroZero() throws Exception {
        env = TestWorkflowEnvironment.builder().defaultConcurrency(0, 0).build();
        env.registerActivities(Spawner.class, new SpawnerImpl(env, CHILD_WAIT, outcome, ChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        assertEquals("child-done:order-1", outcome.get(10, SECONDS));
    }

    /** Rule 11's "Right" fix: the child runs on its own queue, which has its own slot. */
    @Test
    void aChildOnItsOwnQueueCompletesAtLimitsOneOne() throws Exception {
        env = TestWorkflowEnvironment.builder().defaultConcurrency(1, 1).build();
        env.registerActivities(Spawner.class,
                new SpawnerImpl(env, CHILD_WAIT, outcome, OwnQueueChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        assertEquals("child-done:order-1", outcome.get(10, SECONDS));
    }

    /** A queue's own limit applies to that queue: (1, 1) on the parent's queue reproduces the timeout. */
    @Test
    void aTaskQueueLimitAppliesToThatQueue() throws Exception {
        env = TestWorkflowEnvironment.builder().taskQueueConcurrency("default", 1, 1).build();
        env.registerActivities(Spawner.class, new SpawnerImpl(env, CHILD_WAIT, outcome, ChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        assertInstanceOf(TimeoutException.class, outcome.get(10, SECONDS));
    }

    /** The activity limit binds too: a live activity holds one of the queue's activity slots. */
    @Test
    void aLiveActivityHoldsAnActivitySlot() throws Exception {
        env = TestWorkflowEnvironment.builder().defaultConcurrency(2, 2).build();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        env.registerActivities(Spawner.class, id -> {
            entered.countDown();
            try {
                release.await(10, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "held:" + id;
        });
        try {
            env.startWorkflow(ParentWorkflow.class, "order-1");
            assertTrue(entered.await(10, SECONDS), "the activity started");
            assertEquals(OptionalInt.of(1),
                    env.currentExecutor().taskQueueGovernor().availableActivitySlots("default"),
                    "one of the queue's two activity slots is held");
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,1", "-1,1", "1,-1"})
    void halfOrNegativeDefaultsFail(int w, int a) {
        var e = assertThrows(IllegalArgumentException.class,
                () -> TestWorkflowEnvironment.builder().defaultConcurrency(w, a).build());
        assertAll(
                () -> assertTrue(e.getMessage().contains("defaultConcurrency"), e.getMessage()),
                () -> assertTrue(e.getMessage().contains(w + ""), e.getMessage()),
                () -> assertTrue(e.getMessage().contains(a + ""), e.getMessage()));
    }

    @Test
    void aQueueNamedTwiceFails() {
        var e = assertThrows(IllegalArgumentException.class, () -> TestWorkflowEnvironment.builder()
                .taskQueueConcurrency("q", 1, 1)
                .taskQueueConcurrency("q", 2, 2)
                .build());
        assertTrue(e.getMessage().contains("'q'"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"q,0,1", "q,1,0", "' ',1,1"})
    void aQueueEntryNeedsANameAndBothLimits(String name, int w, int a) {
        assertThrows(IllegalArgumentException.class,
                () -> TestWorkflowEnvironment.builder().taskQueueConcurrency(name, w, a).build());
    }

    // ── rule 11 fixtures (shared with MaestroTestLimitsTest) ──────────

    /** Starts a child workflow and waits for its result, inside an activity. */
    @Activity(name = "spawner")
    public interface Spawner {
        /** @param id the order id @return the child's result */
        String spawn(String id);
    }

    /**
     * Completes {@code outcome} with the child's result or with the
     * {@link TimeoutException} its wait threw, and rethrows the latter.
     */
    static final class SpawnerImpl implements Spawner {
        private final TestWorkflowEnvironment env;
        private final Duration wait;
        private final CompletableFuture<Object> outcome;
        private final Class<?> child;

        SpawnerImpl(TestWorkflowEnvironment env, Duration wait, CompletableFuture<Object> outcome, Class<?> child) {
            this.env = env;
            this.wait = wait;
            this.outcome = outcome;
            this.child = child;
        }

        @Override
        public String spawn(String id) {
            try {
                var result = env.startWorkflow(child, id).getResult(String.class, wait);
                outcome.complete(result);
                return result;
            } catch (TimeoutException e) {
                outcome.complete(e);
                throw new IllegalStateException("the child did not finish within " + wait, e);
            }
        }
    }

    /** One {@link Spawner} call, never retried. */
    @DurableWorkflow(name = "LimitsParentWorkflow")
    public static class ParentWorkflow {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        Spawner spawner;

        /** @param id the order id @return the child's result */
        @WorkflowMethod
        public String run(String id) {
            return spawner.spawn(id);
        }
    }

    /** A child on the parent's queue. */
    @DurableWorkflow(name = "LimitsChildWorkflow")
    public static class ChildWorkflow {
        /** @param id the order id @return a marker naming it */
        @WorkflowMethod
        public String run(String id) {
            return "child-done:" + id;
        }
    }

    /** The same child on its own queue. */
    @DurableWorkflow(name = "LimitsOwnQueueChildWorkflow", taskQueue = "children")
    public static class OwnQueueChildWorkflow {
        /** @param id the order id @return a marker naming it */
        @WorkflowMethod
        public String run(String id) {
            return "child-done:" + id;
        }
    }
}
