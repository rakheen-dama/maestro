package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The mechanics a live read-back shares — an activity result in
 * {@link ActivityInvocationHandler}, a recorded clock value in
 * {@link DefaultWorkflowOperations#currentTime()}: read the stored node back,
 * and fall back to the value the live run already holds if the mapper cannot.
 * Each caller does its own reporting.
 *
 * <p><b>Thread safety:</b> stateless.
 */
final class ReadBack {

    private ReadBack() {
    }

    /**
     * {@code read}'s value, or {@code live} if {@code read} throws a
     * {@link SerializationException}; {@code onUnreadable} then receives the
     * deserialization root cause (the exception's cause, else the exception).
     *
     * <p>Only a {@link SerializationException} is caught: an {@link Error} —
     * the engine's control-flow signals included — and every other exception
     * propagate. {@code read} must deserialize only: no store call, so a store
     * fault can never be mistaken for an unreadable payload.
     *
     * @param live         the value to fall back to — the activity's own
     *                     object, the clock value
     * @param read         deserializes the stored node; no store call
     * @param onUnreadable the caller's reporting, run only on the fallback
     * @param <T>          the value's type
     * @return the read-back, or {@code live} if it cannot be read back
     */
    static <T> @Nullable T orLive(@Nullable T live, Supplier<? extends @Nullable T> read,
                                  Consumer<Throwable> onUnreadable) {
        try {
            return read.get();
        } catch (SerializationException unreadable) {
            onUnreadable.accept(unreadable.getCause() != null ? unreadable.getCause() : unreadable);
            return live;
        }
    }
}
