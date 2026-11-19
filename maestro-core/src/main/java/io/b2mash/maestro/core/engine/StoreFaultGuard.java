package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
 * <p>Only {@code appendEvent} (both overloads), {@code appendEventReturningPayload}
 * (the same write, returning the stored payload) and {@code getEventBySequence}:
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
 * <h2>Absorbed faults are visible (Issue 86)</h2>
 * <p>Every in-place retry calls {@link EngineObserver#storeRetried} with the
 * operation's bounded snake_case name ({@code append_event} for both
 * {@code appendEvent} overloads, {@code append_event_returning_payload},
 * {@code get_event_by_sequence}), which the starter counts on
 * {@code maestro.store.retries{operation}}. Retries are grouped in
 * <em>episodes</em>, node-wide — one guarded store per executor — because what
 * is reported is the database's flakiness, not one run's bad luck: an episode
 * starts with the first retry after {@link #EPISODE_QUIET} (60 s) without one,
 * and ends implicitly once that long passes without a retry. The first retry
 * of an episode logs one {@code WARN} with the {@link #diagnosis}; the others
 * log at {@code DEBUG}. Exhausted retries keep their once-per-run latch
 * {@code WARN}. Advisory reads are never retried, so never counted.
 *
 * <h2>When the run is latched</h2>
 * <p>When the retries are exhausted (or at once, for an operation that is not
 * retried), the guard requests {@code STORE_UNAVAILABLE} on the cancellation
 * of the {@link WorkflowContext} bound to the calling thread, with the detail
 * {@code "<method>: <exception> — <diagnosis>"}, logs it once at {@code WARN}
 * (the full throwable at {@code DEBUG}), and rethrows the last exception. The
 * diagnosis is what tells one fault from another — a pool timeout, a schema
 * error, a deterministic rejection: {@code SQLSTATE=<state> <driver message>}
 * of the first {@link SQLException} in the cause chain ({@code SQLSTATE=none}
 * when the driver set none), or the root cause's class and message when the
 * chain holds no {@code SQLException}.
 *
 * <p>Nothing is latched when no context is bound (a background loop, an admin
 * call), when the call runs inside {@link #advisory(Supplier)} — an advisory
 * read, such as a park loop's wake recheck (see {@link ParkProbe}), is skipped
 * on failure, so it must neither stop the run nor wait out retries — or when
 * it runs inside {@link #passThrough(Callable)}.
 *
 * <h2>Calls an activity body makes are not the run's</h2>
 * <p>The activity proxy re-binds the run's {@code WorkflowContext} on the
 * thread that runs an activity attempt, so the latch would otherwise reach a
 * store call the activity body makes on its own account — a
 * {@code MaestroClient} start or signal of <em>another</em> workflow, which
 * reaches this executor's guarded store. That fault belongs to the activity:
 * it must fail the attempt under the activity's own {@code RetryPolicy}, not
 * abandon the calling run. The proxy therefore runs the activity body — and
 * only the body: never its own memo lookup, outcome append or lock calls —
 * inside {@link #passThrough(Callable)}, where the guard neither retries,
 * latches nor refuses.
 *
 * <h2>A latched run cannot append</h2>
 * <p>Once the run bound to the calling thread is latched
 * {@code STORE_UNAVAILABLE}, every append it <em>starts</em> — any method whose
 * name starts with {@code appendEvent}: both {@code appendEvent} overloads,
 * {@code appendEventReturningPayload} and {@code appendEventAndConsumeSignal} — is refused with a
 * {@link StoreUnavailableException} <em>without</em> reaching the store, even
 * if the store answers again; the refusal is neither retried nor re-latched.
 * Workflow code may have caught the original exception and carried on down a
 * path the durable history never chose (a fallback, a {@code randomUUID()} for
 * it); an event it appended there would sit in a sequence slot that recovery
 * — re-running the primary — later fills differently, and the workflow would
 * fail its replay check on every recovery. A <em>stale</em> run needs no such
 * rule: the lease fence and the {@code (workflow_instance_id, sequence_number)}
 * index already refuse its writes. A store-unavailable run owns its lease and
 * its slots, so nothing else stops its appends once the store heals. The
 * check is made once, on entry: an append already in its retry loop when
 * another thread of the same run (a parallel branch) latches the run is not
 * stopped, so its next attempt can still land; the run then stops at its next
 * stop point. Reads and non-append writes are not refused: the stop points
 * raise the latch before any side effect or status change, and a read records
 * nothing.
 *
 * <h2>Everything else passes through</h2>
 * <p>Any other throwable from the delegate is rethrown unwrapped from the
 * reflective {@link InvocationTargetException}, an {@link Error} checked
 * first — the engine's {@code MaestroControlFlowError}s reach their handlers
 * intact and are never retried or latched.
 *
 * <p><b>Thread safety:</b> one atomic — the time of the last in-place retry,
 * for the episode; otherwise a wrapped store is exactly as thread-safe as its
 * delegate. The advisory and pass-through flags are
 * {@link ScopedValue}s, so each is confined to the thread (and scope) that
 * set it — the activity proxy binds the pass-through on the attempt thread
 * itself.
 */
final class StoreFaultGuard {

    private static final Logger logger = LoggerFactory.getLogger(StoreFaultGuard.class);

    /** Attempts in total for a retried operation (the first try plus two retries). */
    static final int MAX_ATTEMPTS = 3;

    /** The waits before the second and the third attempt. */
    static final List<Duration> RETRY_BACKOFF = List.of(Duration.ofMillis(200), Duration.ofSeconds(1));

    /** How long without an in-place retry ends an episode (class Javadoc). */
    static final Duration EPISODE_QUIET = Duration.ofSeconds(60);

    /** The operations that are safe to repeat after an attempt of unknown outcome. */
    private static final Set<String> RETRIED =
            Set.of("appendEvent", "appendEventReturningPayload", "getEventBySequence");

    /** Set while an advisory read runs: the guard neither retries nor latches. */
    private static final ScopedValue<Boolean> ADVISORY = ScopedValue.newInstance();

    /** Set while an activity body runs: the guard neither retries, latches nor refuses. */
    private static final ScopedValue<Boolean> PASS_THROUGH = ScopedValue.newInstance();

    private StoreFaultGuard() {
    }

    /**
     * Wraps {@code delegate} in the guard, with the production retry waits and
     * no observer.
     *
     * @param delegate the store to guard
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate) {
        return wrap(delegate, EngineObserver.NOOP);
    }

    /**
     * Wraps {@code delegate} in the guard, with the production retry waits.
     *
     * @param delegate the store to guard
     * @param observer receives {@link EngineObserver#storeRetried} per in-place retry
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate, EngineObserver observer) {
        return wrap(delegate, RETRY_BACKOFF, observer, System::nanoTime);
    }

    /**
     * Wraps {@code delegate} in the guard with the given retry waits and no
     * observer — for tests, which cannot afford the production waits.
     *
     * @param delegate the store to guard
     * @param backoff  the waits before the second and the third attempt
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate, List<Duration> backoff) {
        return wrap(delegate, backoff, EngineObserver.NOOP, System::nanoTime);
    }

    /**
     * Wraps {@code delegate} in the guard with the given retry waits, observer
     * and monotonic clock (the episode's; a test seam).
     *
     * @param delegate the store to guard
     * @param backoff  the waits before the second and the third attempt
     * @param observer receives {@link EngineObserver#storeRetried} per in-place retry
     * @param nanoTime monotonic clock in nanoseconds, as {@link System#nanoTime()}
     * @return the guarded store; {@code delegate} itself if it is already guarded
     */
    static WorkflowStore wrap(WorkflowStore delegate, List<Duration> backoff, EngineObserver observer,
                              LongSupplier nanoTime) {
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
                new Handler(delegate, List.copyOf(backoff), new Episodes(CompositeEngineObserver.contain(observer),
                        Objects.requireNonNull(nanoTime, "nanoTime"))));
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

    /**
     * Runs an activity body, on the thread that runs it, with the guard out of
     * the way: a store call the body makes on its own account — through a
     * {@code MaestroClient}, against the engine's guarded store — is neither
     * retried, nor latched on the run bound to this thread, nor refused if
     * that run is already latched (class Javadoc). Whatever {@code body}
     * throws propagates as is: nothing is wrapped.
     *
     * @param body the activity body
     * @param <T>  its result type
     * @return the body's result
     * @throws Exception whatever the body throws
     */
    static <T> T passThrough(Callable<T> body) throws Exception {
        return ScopedValue.where(PASS_THROUGH, Boolean.TRUE).call(body::call);
    }

    /**
     * What tells one store fault from another, for the latch detail and the
     * logs: {@code SQLSTATE=<state> <driver message>} of the first
     * {@link SQLException} in {@code fault}'s cause chain ({@code none} for a
     * missing state, as a pool acquisition timeout has), or
     * {@code root cause <class>: <message>} when the chain holds no
     * {@code SQLException}.
     *
     * @param fault the store fault
     * @return the diagnosis — never {@code null}
     */
    static String diagnosis(Throwable fault) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var root = fault;
        for (Throwable t = fault; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                var state = sql.getSQLState();
                return "SQLSTATE=" + (state != null ? state : "none") + " " + sql.getMessage();
            }
            root = t;
        }
        return "root cause " + root;
    }

    /**
     * Counts every in-place retry and decides which one opens an episode
     * (class Javadoc). One per guarded store; thread-safe.
     */
    private static final class Episodes {

        private static final long NONE = Long.MIN_VALUE;

        private final EngineObserver observer;
        private final LongSupplier nanoTime;
        private final AtomicLong lastRetryNanos = new AtomicLong(NONE);

        Episodes(EngineObserver observer, LongSupplier nanoTime) {
            this.observer = observer;
            this.nanoTime = nanoTime;
        }

        /** Records one retry; {@code true} when it opens an episode. */
        boolean retried(Method method) {
            observer.storeRetried(operation(method));
            // A CAS that never moves the timestamp backwards (PR #74 review): a
            // retry whose sample is older than the last recorded one — it paused
            // between sampling and recording — is inside that retry's episode,
            // and must not replace it, or the next retry would see a spurious
            // 60 s gap and WARN again.
            while (true) {
                var now = nanoTime.getAsLong();
                var previous = lastRetryNanos.get();
                if (previous != NONE && now - previous <= 0) {
                    return false;
                }
                if (lastRetryNanos.compareAndSet(previous, now)) {
                    return previous == NONE || now - previous >= EPISODE_QUIET.toNanos();
                }
                // Another retry recorded first: sample again against it.
            }
        }

        /** The bounded, snake_case tag value of a retried operation. */
        private static String operation(Method method) {
            return switch (method.getName()) {
                case "appendEvent" -> "append_event";
                case "appendEventReturningPayload" -> "append_event_returning_payload";
                case "getEventBySequence" -> "get_event_by_sequence";
                default -> method.getName();
            };
        }
    }

    /** Runs one logging call, containing anything but a control-flow {@code Error} it throws. */
    private static void quietly(Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            if (t instanceof io.b2mash.maestro.core.exception.MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            // Nothing to report with; the call it described goes on.
        }
    }

    private record Handler(WorkflowStore delegate, List<Duration> backoff, Episodes episodes)
            implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
                throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }
            if (PASS_THROUGH.isBound()) {
                return invokeDelegate(method, args);
            }
            var advisory = ADVISORY.isBound();
            if (!advisory && method.getName().startsWith("appendEvent")) {
                refuseIfLatched(method);
            }
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
            // Logged quietly (PR #74 review audit): a logger that throws, or a
            // fault whose rendering throws, must not skip the retry it reports.
            if (episodes.retried(method)) {
                quietly(() -> logger.warn("Store unavailable on {} — retrying in place (attempt {}/{}): {}; further "
                                + "in-place retries are counted on maestro.store.retries and logged at DEBUG until {} s "
                                + "pass without one",
                        method.getName(), attempt, MAX_ATTEMPTS, diagnosis(e), EPISODE_QUIET.toSeconds()));
            } else {
                quietly(() -> logger.debug("Store unavailable on {} (attempt {}/{}), retrying in {}: {}",
                        method.getName(), attempt, MAX_ATTEMPTS, wait, e.toString()));
            }
            try {
                Thread.sleep(wait);
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /** Refuses an append of a run already latched {@code STORE_UNAVAILABLE} (class Javadoc). */
        private static void refuseIfLatched(Method method) {
            var bound = WorkflowContext.scopedValue();
            if (bound.isBound()
                    && bound.get().cancellation().cause() == RunCancellation.Cause.STORE_UNAVAILABLE) {
                throw new StoreUnavailableException(
                        "Workflow '%s' is standing down after a store fault — append refused: %s"
                                .formatted(bound.get().workflowId(), method.getName()),
                        new IllegalStateException(String.valueOf(bound.get().cancellation().reason())));
            }
        }

        private static void latch(Method method, StoreUnavailableException e) {
            var bound = WorkflowContext.scopedValue();
            if (!bound.isBound()) {
                return;
            }
            var ctx = bound.get();
            var fault = e + " — " + diagnosis(e);
            if (ctx.cancellation().requestStoreUnavailable(method.getName() + ": " + fault)) {
                // Quietly: a logger that throws must not replace the store fault
                // the caller is about to receive (PR #74 review audit).
                quietly(() -> logger.warn("Store unavailable on {} — workflow '{}' stands down until the store "
                        + "recovers: {}", method.getName(), ctx.workflowId(), fault));
            }
            quietly(() -> logger.debug("Store fault on {} for workflow '{}'", method.getName(), ctx.workflowId(), e));
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
