package io.b2mash.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a field in a workflow class should be injected with a
 * memoizing activity proxy.
 *
 * <p>The proxy intercepts every method call on the activity interface,
 * checking the memoization log (Postgres) before executing. On replay
 * (crash recovery), stored results are returned instantly without
 * re-executing the activity.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @DurableWorkflow(name = "order-fulfilment", taskQueue = "orders")
 * public class OrderFulfilmentWorkflow {
 *
 *     @ActivityStub(startToCloseTimeout = "PT30S",
 *                   retryPolicy = @RetryPolicy(maxAttempts = 3))
 *     private InventoryActivities inventory;
 *
 *     @ActivityStub(startToCloseTimeout = "PT10S",
 *                   retryPolicy = @RetryPolicy(maxAttempts = 1))
 *     private PaymentActivities payment;
 *
 *     // ...
 * }
 * }</pre>
 *
 * @see Activity
 * @see RetryPolicy
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActivityStub {

    /**
     * Maximum time from when an activity starts to when it must complete,
     * as an ISO 8601 duration.
     *
     * <p>Used as the TTL hint for the distributed lock acquired during
     * live execution. Hard enforcement of this timeout is a future enhancement.
     *
     * @return the start-to-close timeout duration
     */
    String startToCloseTimeout() default "PT30S";

    /**
     * Retry policy for failed activity invocations.
     *
     * @return the retry policy configuration
     */
    RetryPolicy retryPolicy() default @RetryPolicy;
}
