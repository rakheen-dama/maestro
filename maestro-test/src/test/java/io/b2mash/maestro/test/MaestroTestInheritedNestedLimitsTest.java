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
 * A {@code @Nested} class declared in an unannotated base class and inherited
 * by an annotated subclass takes the subclass's limits: the extension looks
 * the annotation up on the classes enclosing the test at run time (this
 * class), not on the classes that declare it ({@link InheritedNestedLimitsBase}).
 */
@MaestroTest(defaultConcurrency = 1, defaultActivityConcurrency = 1)
class MaestroTestInheritedNestedLimitsTest extends InheritedNestedLimitsBase {
}

/** Declares the nested tests; carries no {@code @MaestroTest} of its own. */
abstract class InheritedNestedLimitsBase {

    @Nested
    class Inherited {
        @Test
        void anInheritedNestedClassTakesTheSubclasssLimits(TestWorkflowEnvironment env) throws Exception {
            var outcome = new CompletableFuture<Object>();
            env.registerActivities(Spawner.class,
                    new SpawnerImpl(env, Duration.ofSeconds(1), outcome, ChildWorkflow.class));
            env.startWorkflow(ParentWorkflow.class, "order-1");
            assertInstanceOf(TimeoutException.class, outcome.get(10, SECONDS),
                    "at (1, 1) the same-queue child cannot start while its parent waits");
        }
    }
}
