package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;

/**
 * Carries a raw W3C {@code traceparent} across one thread's call stack, from a
 * transport listener that extracted it off the wire down to the engine code
 * that persists a signal row (observability design doc §4.3(a)).
 *
 * <h2>Why a bare {@code String} in {@code maestro-core}</h2>
 * {@code maestro-core} must never depend on Spring, Micrometer or
 * OpenTelemetry, so it cannot hold a {@code TraceContext} or a {@code Span}.
 * It does not need to: the value is <b>opaque</b> — core neither parses nor
 * branches on it, it only stores and returns it. The tracing adapters (starter,
 * {@code maestro-messaging-kafka}) own every interpretation of the string.
 *
 * <h2>Why a {@code ThreadLocal} and not a {@code ScopedValue}</h2>
 * The producing frame (a Kafka listener's record wrapper) and the consuming
 * frame ({@code SignalManager.deliverSignal}) are separated by the
 * {@code WorkflowMessaging} SPI's {@code Consumer<SignalMessage>} handler,
 * whose shape must not change to thread a context object through. Delivery is
 * synchronous on the listener thread, so a thread-confined value is exactly the
 * right lifetime. Values do not escape to workflow virtual threads: the durable
 * hop from listener to workflow is the signal row's {@code trace_context}
 * column, not this holder.
 *
 * <h2>Discipline</h2>
 * Always prefer {@link #runWith(String, Runnable)}, which restores the previous
 * value on every exit path including exceptional ones. A bare {@link #set}
 * without a matching {@link #clear} leaks the value onto a pooled listener
 * thread, where the <em>next</em> unrelated signal would inherit it.
 *
 * <p><b>Thread safety:</b> thread-confined by construction; every method is
 * safe to call from any thread and affects only the calling thread.
 */
public final class TraceContextHolder {

    private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

    private TraceContextHolder() {
        // static holder
    }

    /**
     * Sets (or, with {@code null}, clears) the calling thread's trace context.
     *
     * @param traceparent the raw W3C {@code traceparent}, or {@code null} to clear
     */
    public static void set(@Nullable String traceparent) {
        if (traceparent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(traceparent);
        }
    }

    /**
     * @return the calling thread's raw W3C {@code traceparent}, or {@code null}
     *         when nothing set one
     */
    public static @Nullable String current() {
        return CURRENT.get();
    }

    /** Clears the calling thread's trace context. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code action} with {@code traceparent} current, restoring whatever
     * was current before — including when the action throws.
     *
     * @param traceparent the raw W3C {@code traceparent}, or {@code null} to run
     *                    the action with no trace context
     * @param action      the action to run
     */
    public static void runWith(@Nullable String traceparent, Runnable action) {
        var previous = CURRENT.get();
        set(traceparent);
        try {
            action.run();
        } finally {
            set(previous);
        }
    }
}
