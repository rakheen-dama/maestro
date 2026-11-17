package io.b2mash.maestro.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JUnit 5 annotation that sets up a {@link TestWorkflowEnvironment}
 * for each test.
 *
 * <p>Annotate your test class with {@code @MaestroTest} to automatically
 * create a fresh environment before each test and shut it down after.
 * The environment is injected as a test method parameter.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @MaestroTest
 * class OrderWorkflowTest {
 *
 *     @Test
 *     void testHappyPath(TestWorkflowEnvironment env) throws Exception {
 *         env.registerActivities(OrderActivities.class, new MockOrderActivities());
 *         var handle = env.startWorkflow(OrderWorkflow.class, new OrderInput("item-1"));
 *         var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 *         assertEquals("item-1", result.orderId());
 *     }
 *
 *     @Test
 *     void survivesACrash(TestWorkflowEnvironment env) throws Exception {
 *         env.registerActivities(OrderActivities.class, new MockOrderActivities());
 *         var crash = env.crashAfter(1);
 *         var handle = env.startWorkflow(OrderWorkflow.class, new OrderInput("item-1"));
 *         crash.recover();
 *         handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 *         assertEquals(1, handle.replayedSteps().size());
 *     }
 * }
 * }</pre>
 *
 * <p>The extension shuts the environment down after each test, including every
 * node retired by {@link TestWorkflowEnvironment#recover()}.
 *
 * <h2>Concurrency limits</h2>
 * <p>By default the environment's task queues are unbounded. Set
 * {@link #defaultConcurrency()} and {@link #defaultActivityConcurrency()} to
 * bound every queue per node as production does
 * ({@code @MaestroTest(defaultConcurrency = 20, defaultActivityConcurrency = 20)}
 * is production's default); they are validated like
 * {@link TestWorkflowEnvironment.Builder#defaultConcurrency(int, int)}. A
 * {@code @Nested} test class without its own {@code @MaestroTest} uses the
 * nearest enclosing class's, as JUnit runs it: a nested class inherited from
 * an unannotated base takes the annotated subclass's limits.
 *
 * @see TestWorkflowEnvironment
 * @see MaestroTestExtension
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MaestroTestExtension.class)
public @interface MaestroTest {

    /**
     * Workflow runs per task queue that may execute at once on the node, like
     * {@code maestro.worker.default-concurrency}. {@code 0} together with a
     * {@code 0} {@link #defaultActivityConcurrency()} (the default) leaves
     * every queue unbounded.
     *
     * @return the workflow limit per task queue, or {@code 0}
     */
    int defaultConcurrency() default 0;

    /**
     * Live activity executions per task queue that may run at once on the
     * node, like {@code maestro.worker.default-activity-concurrency}. {@code 0}
     * together with a {@code 0} {@link #defaultConcurrency()} (the default)
     * leaves every queue unbounded.
     *
     * @return the activity limit per task queue, or {@code 0}
     */
    int defaultActivityConcurrency() default 0;
}
