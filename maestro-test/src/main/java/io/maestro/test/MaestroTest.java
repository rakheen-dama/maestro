package io.maestro.test;

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
 *     void testHappyPath(TestWorkflowEnvironment env) {
 *         env.registerActivities(OrderActivities.class, new MockOrderActivities());
 *         var handle = env.startWorkflow(OrderWorkflow.class, new OrderInput("item-1"));
 *         var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 *         assertEquals("COMPLETED", result.status());
 *     }
 * }
 * }</pre>
 *
 * @see TestWorkflowEnvironment
 * @see MaestroTestExtension
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MaestroTestExtension.class)
public @interface MaestroTest {
}
