package io.b2mash.maestro.core.annotation;

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
 * {@link io.b2mash.maestro.core.context.WorkflowContext} API for durable operations
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
 * @see io.b2mash.maestro.core.context.WorkflowContext
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableWorkflow {

    /**
     * Optional name for this workflow type. If empty, the class's
     * simple name is used (e.g., {@code "OrderFulfilmentWorkflow"}).
     *
     * <p>The name is persisted with every instance and is what recovery uses
     * to find this workflow's implementation again.
     *
     * @return the workflow type name, or empty to use the class simple name
     */
    String name() default "";

    /**
     * The task queue this workflow's runs are counted against. Defaults to
     * {@code "default"}.
     *
     * <p>A task queue is a concurrency bucket, not a route: it names the
     * per-node limits configured under {@code maestro.worker.task-queues[]}
     * ({@code concurrency} for workflow runs, {@code activity-concurrency} for
     * live activity executions), and a queue that is not configured there is
     * unbounded. It does not decide <em>where</em> a workflow runs — a run
     * executes in-process on the node that starts it, or on the node that
     * recovers it after its owner stops.
     *
     * @return the task queue name
     */
    String taskQueue() default "default";
}
