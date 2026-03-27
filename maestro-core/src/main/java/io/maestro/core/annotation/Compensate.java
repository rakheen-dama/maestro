package io.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a compensation method for saga rollback on an activity method.
 *
 * <p>When a workflow annotated with {@link Saga} fails, the engine
 * automatically invokes compensation methods in reverse order (LIFO)
 * for each successfully completed activity that has this annotation.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Activity
 * public interface InventoryActivities {
 *
 *     @Compensate("releaseReservation")
 *     ReservationConfirmation reserve(List<Item> items);
 *
 *     void releaseReservation(ReservationConfirmation reservation);
 * }
 * }</pre>
 *
 * <h2>Argument Resolution</h2>
 * <p>The compensation method's arguments are resolved by convention:
 * <ol>
 *   <li>If the compensation method takes <b>no parameters</b>, it is
 *       called with no arguments.</li>
 *   <li>If it takes a <b>single parameter</b> assignable from the
 *       activity's return type, the return value is passed.</li>
 *   <li>If its parameter types <b>match the activity's parameters</b>
 *       exactly, the original arguments are passed.</li>
 * </ol>
 *
 * <p>Incompatible signatures are detected at proxy creation time
 * (fail-fast validation at startup).
 *
 * <h2>Memoization</h2>
 * <p>Compensation calls go through the activity proxy and are therefore
 * memoized, retriable, and replayable — just like normal activity calls.
 *
 * @see Saga
 * @see Activity
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Compensate {

    /**
     * The name of the compensation method on the same activity interface.
     *
     * @return the compensation method name
     */
    String value();
}
