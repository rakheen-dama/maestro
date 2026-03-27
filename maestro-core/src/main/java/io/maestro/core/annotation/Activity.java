package io.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an activity interface whose methods can be
 * memoized by the Maestro workflow engine.
 *
 * <p>Activity interfaces define the operations that a workflow can invoke
 * durably. Each method call is intercepted by the activity proxy, which
 * checks for a stored result before executing — enabling crash-recoverable
 * replay.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Activity
 * public interface InventoryActivities {
 *     ReservationConfirmation reserve(List<OrderItem> items);
 *     void releaseReservation(String reservationId);
 * }
 * }</pre>
 *
 * <p>The activity implementation is a plain class (or Spring bean) that
 * implements this interface. The proxy is injected into workflow classes
 * via {@link ActivityStub}.
 *
 * @see ActivityStub
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Activity {

    /**
     * Optional name for this activity group. If empty, the interface's
     * simple name is used (e.g., {@code "InventoryActivities"}).
     *
     * <p>The name is used as the prefix in step names recorded in the
     * memoization log (e.g., {@code "InventoryActivities.reserve"}).
     *
     * @return the activity group name, or empty to use the interface simple name
     */
    String name() default "";
}
