package io.b2mash.maestro.test;

import io.b2mash.maestro.test.TestWorkflowEnvironmentLimitsTest.ChildWorkflow;
import io.b2mash.maestro.test.TestWorkflowEnvironmentLimitsTest.ParentWorkflow;
import io.b2mash.maestro.test.TestWorkflowEnvironmentLimitsTest.Spawner;
import io.b2mash.maestro.test.TestWorkflowEnvironmentLimitsTest.SpawnerImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Issue 36: {@code @MaestroTest}'s limit attributes reach the environment it
 * injects, and a {@code @Nested} class without its own annotation inherits
 * the enclosing class's. The (1, 1) case of
 * {@link TestWorkflowEnvironmentLimitsTest}: a same-queue child waited on
 * inside an activity times out.
 */
@MaestroTest(defaultConcurrency = 1, defaultActivityConcurrency = 1)
class MaestroTestLimitsTest {

    @Test
    void theAnnotationsLimitsApply(TestWorkflowEnvironment env) throws Exception {
        assertInstanceOf(TimeoutException.class, sameQueueChildOutcome(env));
    }

    @Nested
    class Inner {
        @Test
        void aNestedClassInheritsTheEnclosingLimits(TestWorkflowEnvironment env) throws Exception {
            assertInstanceOf(TimeoutException.class, sameQueueChildOutcome(env));
        }
    }

    private static Object sameQueueChildOutcome(TestWorkflowEnvironment env) throws Exception {
        var outcome = new CompletableFuture<Object>();
        env.registerActivities(Spawner.class, new SpawnerImpl(env, Duration.ofSeconds(1), outcome, ChildWorkflow.class));
        env.startWorkflow(ParentWorkflow.class, "order-1");
        return outcome.get(10, SECONDS);
    }
}
