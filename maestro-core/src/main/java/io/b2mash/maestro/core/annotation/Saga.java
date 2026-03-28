package io.b2mash.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables automatic saga compensation on a {@link WorkflowMethod}.
 *
 * <p>When a workflow method annotated with {@code @Saga} fails, the
 * engine automatically runs registered compensations to undo completed
 * activities. Compensations are registered either declaratively via
 * {@link Compensate} on activity methods or manually via
 * {@link io.b2mash.maestro.core.context.WorkflowContext#addCompensation(Runnable)}
 * or {@link io.b2mash.maestro.core.context.WorkflowContext#addCompensation(String, Runnable)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @WorkflowMethod
 * @Saga(parallelCompensation = false)
 * public OrderResult fulfil(OrderInput input) {
 *     var reservation = inventory.reserve(input.items());
 *     // If later steps fail, inventory.releaseReservation() runs automatically
 *     var payment = payment.charge(input.paymentMethod(), reservation.total());
 *     return new OrderResult(reservation, payment);
 * }
 * }</pre>
 *
 * <h2>Compensation Ordering</h2>
 * <p>By default, compensations execute in <b>reverse registration order</b>
 * (LIFO — most recent first). Set {@link #parallelCompensation()} to
 * {@code true} to execute all compensations concurrently.
 *
 * @see Compensate
 * @see WorkflowMethod
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Saga {

    /**
     * If {@code true}, compensations run in parallel instead of
     * sequential reverse order.
     *
     * <p>Default is {@code false} (sequential LIFO).
     *
     * @return whether compensations should run in parallel
     */
    boolean parallelCompensation() default false;
}
