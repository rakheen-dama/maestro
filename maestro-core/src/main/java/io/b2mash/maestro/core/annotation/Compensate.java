package io.b2mash.maestro.core.annotation;

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
 * <p>The rules are tried in that order, so a signature both fit gets the
 * <b>return value</b>: {@code undo(String)} for
 * {@code @Compensate("undo") String reserve(String orderId)} receives the
 * reservation, never the order id. To receive the arguments, give the
 * compensation a parameter the return type is not assignable to (or take the
 * id from the result). Types are compared as the proxied interface binds
 * them: a method inherited from a generic parent ({@code T reserve(String)}
 * in {@code Reserving<T>}, proxied as {@code Reservations extends
 * Reserving<String>}) returns {@code String} here, not the erased
 * {@code Object} (Issue 94), and the compensation method is looked up on the
 * proxied interface.
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
