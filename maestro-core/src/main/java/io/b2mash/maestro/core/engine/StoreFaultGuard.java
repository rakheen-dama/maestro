package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The store-boundary reaction to a {@link StoreUnavailableException}: a
 * reflective {@link WorkflowStore} proxy that retries the two idempotent
 * operations a short, bounded number of times and, once a store fault is
 * final, latches the calling run's
 * {@link io.b2mash.maestro.core.context.RunCancellation} as
 * {@code STORE_UNAVAILABLE} so the run stands down instead of recording an
 * infrastructure blip as a workflow failure.
 *
 * <h2>What is retried</h2>
 * <p>Only {@code appendEvent} (both overloads) and {@code getEventBySequence}:
 * up to {@value #MAX_ATTEMPTS} attempts in total, waiting
 * {@link #RETRY_BACKOFF} (200 ms, then 1 s) between them. Both are safe to
 * repeat when the outcome of a failed attempt is unknown: a read has no
 * effect, and an append whose commit was lost in flight is caught on retry by
 * the {@code (workflow_instance_id, sequence_number)} uniqueness, which
 * surfaces it as a {@link io.b2mash.maestro.core.exception.DuplicateEventException}
 * for the caller's existing collision handling. Every other operation is
 * attempted exactly once — a status CAS or signal consumption retried blind
 * could act twice. An interrupt during the wait restores the thread's
 * interrupt flag and ends the retries.
 *
 * <h2>When the run is latched</h2>
 * <p>When the retries are exhausted (or at once, for an operation that is not
 * retried), the guard requests {@code STORE_UNAVAILABLE} on the cancellation
 * of the {@link WorkflowContext} bound to the calling thread, with the detail
 * {@code "<method>: <exception>"}, and rethrows the last exception. Nothing is
 * latched when no context is bound (a background loop, an admin call) or when
 * the call runs inside {@link #advisory(Supplier)}: an advisory read — a park
 * loop's wake recheck, see {@link ParkProbe} — is skipped on failure, so it
 * must neither stop the run nor wait out retries.
 *
 * <h2>Everything else passes through</h2>
 * <p>Any other throwable from the delegate is rethrown unwrapped from the
 * reflective {@link InvocationTargetException}, an {@link Error} checked
 * first — the engine's {@code MaestroControlFlowError}s reach their handlers
 * intact and are never retried or latched.
 *
 * <p><b>Thread safety:</b> stateless; a wrapped store is exactly as
 * thread-safe as its delegate. The advisory flag is a {@link ScopedValue},
 * so it is confined to the thread (and scope) that set it.
 */
final class StoreFaultGuard {

    private static final Logger logger = LoggerFactory.getLogger(StoreFaultGuard.class);

    /** Attempts in total for a retried operation (the first try plus two retries). */
    static final int MAX_ATTEMPTS = 3;

    /** The waits before the second and the third attempt. */
    static final List<Duration> RETRY_BACKOFF = List.of(Duration.ofMillis(200), Duration.ofSeconds(1));

    /** The operations that are safe to repeat after an attempt of unknown outcome. */
    private static final Set<String> RETRIED = Set.of("appendEvent", "getEventBySequence");

    /** Set while an advisory read runs: the guard neither retries nor latches. */
    private static final ScopedValue<Boolean> ADVISORY = ScopedValue.newInstance();

    private StoreFaultGuard() {
    }

    /**
     * Wraps {@code delegate} in the guard, with the production retry waits.
     *
     * @param delegate the store to guard
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate) {
        return wrap(delegate, RETRY_BACKOFF);
    }

    /**
     * Wraps {@code delegate} in the guard with the given retry waits — for
     * tests, which cannot afford the production waits.
     *
     * @param delegate the store to guard
     * @param backoff  the waits before the second and the third attempt
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate, List<Duration> backoff) {
        if (Proxy.isProxyClass(delegate.getClass())
                && Proxy.getInvocationHandler(delegate) instanceof Handler) {
            return delegate;
        }
        if (backoff.size() != MAX_ATTEMPTS - 1) {
            throw new IllegalArgumentException(
                    "expected " + (MAX_ATTEMPTS - 1) + " retry waits, got " + backoff);
        }
        return (WorkflowStore) Proxy.newProxyInstance(
                WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class},
                new Handler(delegate, List.copyOf(backoff)));
    }

    /**
     * Runs an advisory store read: one whose failure the caller skips, so the
     * guard neither retries it nor latches the run. The store exception, if
     * any, still propagates to the caller.
     *
     * @param read the read to run
     * @param <T>  the read's result type
     * @return the read's result
     */
    static <T> T advisory(Supplier<T> read) {
        return ScopedValue.where(ADVISORY, Boolean.TRUE).call(read::get);
    }

    private record Handler(WorkflowStore delegate, List<Duration> backoff) implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
                throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }
            var advisory = ADVISORY.isBound();
            var attempts = !advisory && RETRIED.contains(method.getName()) ? MAX_ATTEMPTS : 1;
            for (int attempt = 1; ; attempt++) {
                try {
                    return invokeDelegate(method, args);
                } catch (StoreUnavailableException e) {
                    if (attempt < attempts && waitBeforeRetry(method, attempt, e)) {
                        continue;
                    }
                    if (!advisory) {
                        latch(method, e);
                    }
                    throw e;
                }
            }
        }

        private @Nullable Object invokeDelegate(Method method, @Nullable Object @Nullable [] args)
                throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                // Error first: a MaestroControlFlowError must reach its handler
                // as itself, never retried, latched or re-wrapped.
                if (cause instanceof Error error) {
                    throw error;
                }
                throw cause != null ? cause : e;
            }
        }

        /** @return {@code true} to retry; {@code false} if interrupted (flag restored) */
        private boolean waitBeforeRetry(Method method, int attempt, StoreUnavailableException e) {
            var wait = backoff.get(attempt - 1);
            logger.debug("Store unavailable on {} (attempt {}/{}), retrying in {}: {}",
                    method.getName(), attempt, MAX_ATTEMPTS, wait, e.toString());
            try {
                Thread.sleep(wait);
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static void latch(Method method, StoreUnavailableException e) {
            var bound = WorkflowContext.scopedValue();
            if (!bound.isBound()) {
                return;
            }
            var ctx = bound.get();
            if (ctx.cancellation().requestStoreUnavailable(method.getName() + ": " + e)) {
                logger.warn("Store unavailable on {} — workflow '{}' stands down until the store "
                        + "recovers: {}", method.getName(), ctx.workflowId(), e.toString());
            }
        }

        private @Nullable Object objectMethod(Object proxy, Method method, @Nullable Object @Nullable [] args) {
            return switch (method.getName()) {
                case "equals" -> args != null && proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "StoreFaultGuard[" + delegate + "]";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }
}
