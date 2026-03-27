package io.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a durable workflow whose execution is crash-recoverable.
 *
 * <p>Workflow classes contain the orchestration logic that calls activities
 * (via {@link ActivityStub}-annotated fields) and uses the
 * {@link io.maestro.core.context.WorkflowContext} API for durable operations
 * like sleeping, awaiting signals, and parallel execution.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @DurableWorkflow
 * public class OrderFulfilmentWorkflow {
 *
 *     @ActivityStub
 *     private InventoryActivities inventory;
 *
 *     @WorkflowMethod
 *     public OrderResult fulfil(OrderInput input) {
 *         var reservation = inventory.reserve(input.items());
 *         var workflow = WorkflowContext.current();
 *         var payment = workflow.awaitSignal("payment.result",
 *                 PaymentResult.class, Duration.ofHours(1));
 *         return new OrderResult(reservation, payment);
 *     }
 * }
 * }</pre>
 *
 * <h2>Determinism Constraint</h2>
 * <p>Code between activity calls must be deterministic — no
 * {@code Math.random()}, {@code LocalDateTime.now()}, or
 * {@code UUID.randomUUID()}. Use {@code workflow.currentTime()},
 * {@code workflow.randomUUID()} instead.
 *
 * @see WorkflowMethod
 * @see ActivityStub
 * @see io.maestro.core.context.WorkflowContext
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableWorkflow {

    /**
     * Optional name for this workflow type. If empty, the class's
     * simple name is used (e.g., {@code "OrderFulfilmentWorkflow"}).
     *
     * <p>The name is used to correlate workflow instances with their
     * implementation during recovery and task routing.
     *
     * @return the workflow type name, or empty to use the class simple name
     */
    String name() default "";

    /**
     * The task queue this workflow listens on. Defaults to {@code "default"}.
     *
     * <p>Task queues allow partitioning workflows across different worker
     * groups. Only workers subscribed to a matching queue will execute
     * workflows of this type.
     *
     * @return the task queue name
     */
    String taskQueue() default "default";
}
