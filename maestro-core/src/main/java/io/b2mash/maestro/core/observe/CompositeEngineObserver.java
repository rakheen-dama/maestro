package io.b2mash.maestro.core.observe;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fans one {@link EngineObserver} callback out to an ordered list of
 * delegates, containing each delegate.
 *
 * <h2>Containment contract (Issue 81)</h2>
 * <p>A delegate that throws is contained: the remaining delegates still run,
 * and the engine path that made the call carries on as if the callback had
 * returned — one misbehaving observer must never disturb the engine or its
 * sibling observers. Contained is every {@link Throwable} except two kinds,
 * which propagate unchanged:
 * <ul>
 *   <li>a {@link MaestroControlFlowError} — the engine's control-flow signals
 *       ({@code ExecutorShutdownException}, {@code WorkflowTerminatedException},
 *       {@code UnknownWorkflowHistoryException}) must never be swallowed, and
 *       observers must never throw them;</li>
 *   <li>a {@link VirtualMachineError} ({@code OutOfMemoryError},
 *       {@code StackOverflowError}, {@code InternalError}) — the JVM's
 *       distress, not the observer's; it propagates as if the engine had hit
 *       it (a background loop then contains it as it would any loop
 *       {@code Error}).</li>
 * </ul>
 * Everything else — a {@link RuntimeException}, a {@link LinkageError} from an
 * adapter built against another library version, an {@link AssertionError} —
 * is the observer's fault. The one exception is {@link #backgroundLoopError},
 * which contains a {@code VirtualMachineError} too: it fires while a loop is
 * already containing an {@code Error}, often that same resource exhaustion.
 *
 * <h2>Logging and counting</h2>
 * <p>The first containment for a (callback, delegate) pair is logged at
 * {@code WARN} with the stack trace; repeats are logged at {@code DEBUG} until
 * a call of that callback to that delegate succeeds again
 * ({@code backgroundLoopError} keys this per loop as well). Every containment
 * increments {@link #containedErrorCount()}, which the engine exposes as
 * {@code WorkflowExecutor.observerErrorCount()} — counted here, not through an
 * observer, because the observer that failed may be the metrics one.
 *
 * <h2>Where it is applied</h2>
 * <p>{@link #of} builds a composite for the starter's observer list
 * (coordinator Ruling 4), and every engine type that takes an
 * {@code EngineObserver} applies {@link #contain} to it, so containment holds
 * for every emission site, including observers wired by hand.
 *
 * <h2>Thread safety</h2>
 * <p>Thread-safe. The delegate list is immutable; the only mutable state is
 * the concurrent set of (callback, delegate) pairs currently throwing, kept to
 * rate-limit the warning, and the atomic containment count. Thread safety of
 * the callbacks themselves is each delegate's obligation per the
 * {@link EngineObserver} contract.
 */
public final class CompositeEngineObserver implements EngineObserver {

    private static final Logger logger = LoggerFactory.getLogger(CompositeEngineObserver.class);

    private final List<EngineObserver> delegates;

    /** (callback, delegate, loop) whose last call threw: repeats are logged at DEBUG. */
    private final Set<Failing> failing = ConcurrentHashMap.newKeySet();

    /** Every containment since construction. */
    private final AtomicLong containedErrors = new AtomicLong();

    /**
     * One (callback, delegate) pair whose last call threw; {@code loop} is the
     * loop name for {@link #backgroundLoopError}, {@code null} otherwise.
     */
    private record Failing(String callback, int delegateIndex, @Nullable String loop) {
    }

    private CompositeEngineObserver(List<EngineObserver> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Wraps a delegate list in a composite, or returns
     * {@link EngineObserver#NOOP} when the list is empty.
     *
     * <p><b>A single delegate is still wrapped</b> (coordinator Ruling 4,
     * amending design §1.2, which originally collapsed to the delegate
     * itself). One observer is the common deployment — a lone metrics adapter,
     * a lone tracing adapter — and it is exactly the case the collapse left
     * with zero containment. The cost is one virtual call per emission on
     * paths that already do database I/O.
     *
     * @param observers the delegates, in invocation order
     * @return {@code NOOP} for an empty list, otherwise a containing composite
     */
    public static EngineObserver of(List<EngineObserver> observers) {
        return observers.isEmpty() ? EngineObserver.NOOP : new CompositeEngineObserver(observers);
    }

    /**
     * The containing form of {@code observer}: {@code observer} itself if it
     * is already a composite, otherwise a composite of that one delegate.
     * Every engine type that takes an {@code EngineObserver} calls this once,
     * in its constructor (Issue 81).
     *
     * @param observer the observer to contain; never {@code null}
     * @return a composite that contains {@code observer}
     * @throws NullPointerException if {@code observer} is {@code null}
     */
    public static CompositeEngineObserver contain(EngineObserver observer) {
        Objects.requireNonNull(observer, "observer");
        return observer instanceof CompositeEngineObserver composite
                ? composite : new CompositeEngineObserver(List.of(observer));
    }

    /**
     * How many delegate failures this composite has contained since it was
     * built, across every callback. Safe to read from any thread.
     *
     * @return the number of contained delegate failures
     */
    public long containedErrorCount() {
        return containedErrors.get();
    }

    /**
     * Runs one callback against every delegate in order, containing every
     * {@code Throwable} per delegate but a control-flow signal and a
     * {@code VirtualMachineError} — see the class Javadoc.
     */
    private void fanOut(String callback, java.util.function.Consumer<EngineObserver> invocation) {
        for (int i = 0; i < delegates.size(); i++) {
            var delegate = delegates.get(i);
            try {
                invocation.accept(delegate);
                succeeded(callback, i, null);
            } catch (Throwable t) {
                rethrowIfNotTheObserversFault(t, false);
                contained(callback, i, null, delegate, t, false);
            }
        }
    }

    /**
     * Rethrows {@code t} when it is a control-flow signal, or — unless
     * {@code containVirtualMachineErrors} — a {@code VirtualMachineError}.
     */
    private static void rethrowIfNotTheObserversFault(Throwable t, boolean containVirtualMachineErrors) {
        if (t instanceof MaestroControlFlowError controlFlow) {
            throw controlFlow;
        }
        if (!containVirtualMachineErrors && t instanceof VirtualMachineError vm) {
            throw vm;
        }
    }

    /** Ends a quiet period for the pair; one {@code isEmpty()} check when nothing is failing. */
    private void succeeded(String callback, int delegateIndex, @Nullable String loop) {
        if (!failing.isEmpty()) {
            failing.remove(new Failing(callback, delegateIndex, loop));
        }
    }

    /** Counts and logs one containment: WARN with the stack trace for the first of a streak, DEBUG after. */
    private void contained(String callback, int delegateIndex, @Nullable String loop, EngineObserver delegate,
                           Throwable t, boolean containVirtualMachineErrors) {
        containedErrors.incrementAndGet();
        try {
            var forLoop = loop == null ? "" : " for loop '" + loop + "'";
            if (failing.add(new Failing(callback, delegateIndex, loop))) {
                logger.warn("EngineObserver delegate {} threw from {}{} — contained; further failures of it from "
                                + "{}{} are logged at DEBUG until a call to it succeeds again",
                        delegate.getClass().getName(), callback, forLoop, callback, forLoop, t);
            } else {
                logger.debug("EngineObserver delegate {} threw again from {}{}: {}",
                        delegate.getClass().getName(), callback, forLoop, t.toString());
            }
        } catch (Throwable logFailure) {
            // The callback's own policy (PR #74 review): an ordinary callback
            // propagates a VirtualMachineError raised while reporting, as it
            // would one from the delegate; backgroundLoopError contains it.
            rethrowIfNotTheObserversFault(logFailure, containVirtualMachineErrors);
            // A failure that cannot be logged is still contained and counted.
        }
    }

    @Override
    public void workflowStarted(WorkflowInfo w) {
        fanOut("workflowStarted", d -> d.workflowStarted(w));
    }

    @Override
    public void workflowResumed(WorkflowInfo w) {
        fanOut("workflowResumed", d -> d.workflowResumed(w));
    }

    @Override
    public void workflowStartDeferred(WorkflowInfo w, boolean lockHeldElsewhere) {
        fanOut("workflowStartDeferred", d -> d.workflowStartDeferred(w, lockHeldElsewhere));
    }

    @Override
    public void workflowCompleted(WorkflowInfo w) {
        fanOut("workflowCompleted", d -> d.workflowCompleted(w));
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        fanOut("workflowFailed", d -> d.workflowFailed(w, exceptionType));
    }

    @Override
    public void workflowFinished(WorkflowInfo w, WorkflowStatus status, Duration elapsed) {
        fanOut("workflowFinished", d -> d.workflowFinished(w, status, elapsed));
    }

    @Override
    public void workflowCompensating(WorkflowInfo w) {
        fanOut("workflowCompensating", d -> d.workflowCompensating(w));
    }

    @Override
    public void workflowTerminated(WorkflowInfo w) {
        fanOut("workflowTerminated", d -> d.workflowTerminated(w));
    }

    @Override
    public void workflowParked(WorkflowInfo w, ParkKind kind) {
        fanOut("workflowParked", d -> d.workflowParked(w, kind));
    }

    @Override
    public void workflowUnparked(WorkflowInfo w, ParkKind kind) {
        fanOut("workflowUnparked", d -> d.workflowUnparked(w, kind));
    }

    @Override
    public void activityStarted(ActivityInfo a) {
        fanOut("activityStarted", d -> d.activityStarted(a));
    }

    /**
     * Applies every delegate's wrapper so that the <b>first delegate's wrapper
     * is outermost</b> and the last's innermost: when the attempt runs, the
     * first delegate's code runs first on the way in and last on the way out —
     * the same order in which every other callback fans out. The chain is
     * therefore built in reverse delegate order (the last delegate wraps the
     * attempt itself). A delegate that throws while wrapping (anything the
     * class Javadoc contains), or returns {@code null}, is contained: its
     * wrapper is skipped and the chain built so far is kept. What a wrapper does once the attempt
     * runs cannot be contained — see {@link EngineObserver#wrapActivityAttempt}.
     */
    @Override
    public <T> Callable<T> wrapActivityAttempt(ActivityInfo a, Callable<T> attempt) {
        var wrapped = attempt;
        for (int i = delegates.size() - 1; i >= 0; i--) {
            var delegate = delegates.get(i);
            try {
                var next = delegate.wrapActivityAttempt(a, wrapped);
                if (next != null) {
                    wrapped = next;
                }
                succeeded("wrapActivityAttempt", i, null);
            } catch (Throwable t) {
                rethrowIfNotTheObserversFault(t, false);
                contained("wrapActivityAttempt", i, null, delegate, t, false);
            }
        }
        return wrapped;
    }

    @Override
    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
        fanOut("activityCompleted", d -> d.activityCompleted(a, duration, replayed));
    }

    @Override
    public void activityFailed(ActivityInfo a, Duration duration,
                               String exceptionType, boolean replayed) {
        fanOut("activityFailed", d -> d.activityFailed(a, duration, exceptionType, replayed));
    }

    @Override
    public void activityResultUnreadable(ActivityInfo a, String exceptionType) {
        fanOut("activityResultUnreadable", d -> d.activityResultUnreadable(a, exceptionType));
    }

    @Override
    public void signalPersisted(SignalInfo s) {
        fanOut("signalPersisted", d -> d.signalPersisted(s));
    }

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        fanOut("signalConsumed", d -> d.signalConsumed(s, replayed));
    }

    @Override
    public void signalDuplicateSuppressed(SignalInfo s) {
        fanOut("signalDuplicateSuppressed", d -> d.signalDuplicateSuppressed(s));
    }

    @Override
    public void timerScheduled(TimerInfo t, boolean replayed) {
        fanOut("timerScheduled", d -> d.timerScheduled(t, replayed));
    }

    @Override
    public void timerFired(TimerInfo t, boolean replayed) {
        fanOut("timerFired", d -> d.timerFired(t, replayed));
    }

    @Override
    public void timerCancelled(TimerInfo t, boolean replayed) {
        fanOut("timerCancelled", d -> d.timerCancelled(t, replayed));
    }

    @Override
    public void instanceLockAcquired(String workflowId) {
        fanOut("instanceLockAcquired", d -> d.instanceLockAcquired(workflowId));
    }

    @Override
    public void instanceLockRenewFailed(String workflowId) {
        fanOut("instanceLockRenewFailed", d -> d.instanceLockRenewFailed(workflowId));
    }

    @Override
    public void instanceLockLost(String workflowId) {
        fanOut("instanceLockLost", d -> d.instanceLockLost(workflowId));
    }

    @Override
    public void recoveryPass(int scanned, int adopted) {
        fanOut("recoveryPass", d -> d.recoveryPass(scanned, adopted));
    }

    @Override
    public void retentionPass(io.b2mash.maestro.core.spi.PurgeResult purged) {
        fanOut("retentionPass", d -> d.retentionPass(purged));
    }

    @Override
    public void backgroundLoopError(String loop, String errorType) {
        // Wider than fanOut: this callback fires while a loop is already
        // containing an Error, so a delegate's own Error — a VirtualMachineError
        // included, often the same resource exhaustion — must not skip the
        // delegates after it. A loop that keeps failing calls this on every
        // pass, so the quiet period is kept per loop.
        for (int i = 0; i < delegates.size(); i++) {
            var delegate = delegates.get(i);
            try {
                delegate.backgroundLoopError(loop, errorType);
                succeeded("backgroundLoopError", i, loop);
            } catch (Throwable t) {
                rethrowIfNotTheObserversFault(t, true);
                contained("backgroundLoopError", i, loop, delegate, t, true);
            }
        }
    }

    @Override
    public void storeRetried(String operation) {
        fanOut("storeRetried", d -> d.storeRetried(operation));
    }

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        fanOut("standDown", d -> d.standDown(reason, workflowId, detail));
    }

    @Override
    public void runAbandoned(WorkflowInfo w, AbandonReason reason) {
        fanOut("runAbandoned", d -> d.runAbandoned(w, reason));
    }
}
