package io.b2mash.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry-point method of a {@link DurableWorkflow}.
 *
 * <p>Each workflow class must have exactly one method annotated with
 * {@code @WorkflowMethod}. This method is invoked by the
 * {@code WorkflowExecutor} when a new workflow starts, and is
 * re-invoked during recovery (with memoized activity results
 * replayed from the event log).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @DurableWorkflow
 * public class OrderFulfilmentWorkflow {
 *
 *     @WorkflowMethod
 *     public OrderResult fulfil(OrderInput input) {
 *         // workflow logic here
 *     }
 * }
 * }</pre>
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li>The method may accept zero or one parameter (the workflow input).</li>
 *   <li>The return type is serialized as the workflow output on completion.</li>
 *   <li>The method must be {@code public} and non-static.</li>
 *   <li>Only one {@code @WorkflowMethod} per workflow class is allowed.</li>
 * </ul>
 *
 * @see DurableWorkflow
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowMethod {
}
