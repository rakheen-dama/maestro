package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Fans one {@link EngineObserver} callback out to an ordered list of
 * delegates.
 *
 * <h2>Containment contract</h2>
 * <p>A delegate throwing a {@link RuntimeException} is contained: the failure
 * is logged at WARN and the remaining delegates still run — one misbehaving
 * observer must never disturb the engine or its sibling observers.
 * {@link Error}s are deliberately <b>not</b> caught: the composite must never
 * swallow the engine's control-flow signals
 * ({@code ExecutorShutdownException}, {@code WorkflowTerminatedException}) —
 * and observers must never throw them.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable and thread-safe; thread safety of the callbacks themselves is
 * each delegate's obligation per the {@link EngineObserver} contract.
 */
public final class CompositeEngineObserver implements EngineObserver {

    private static final Logger logger = LoggerFactory.getLogger(CompositeEngineObserver.class);

    private final List<EngineObserver> delegates;

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
     * with zero containment: a third-party adapter throwing a
     * {@link RuntimeException} then propagated straight into engine control
     * flow, where it could be read as a workflow failure, a lock-backend
     * failure, or an aborted recovery pass. Wrapping always makes containment
     * structural at every emission site, present and future, instead of
     * depending on which call sites someone remembered to harden. The cost is
     * one virtual call per emission on paths that already do database I/O.
     *
     * <p>{@link Error} still propagates uncontained — see the class Javadoc.
     *
     * @param observers the delegates, in invocation order
     * @return {@code NOOP} for an empty list, otherwise a containing composite
     */
    public static EngineObserver of(List<EngineObserver> observers) {
        return observers.isEmpty() ? EngineObserver.NOOP : new CompositeEngineObserver(observers);
    }

    /**
     * Runs one callback against every delegate in order, containing
     * {@link RuntimeException} per delegate. {@link Error}s deliberately
     * propagate — see the class Javadoc.
     */
    private void fanOut(String callback, java.util.function.Consumer<EngineObserver> invocation) {
        for (var delegate : delegates) {
            try {
                invocation.accept(delegate);
            } catch (RuntimeException e) {
                logger.warn("EngineObserver delegate {} threw from {} — contained",
                        delegate.getClass().getName(), callback, e);
            }
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
    public void workflowCompleted(WorkflowInfo w) {
        fanOut("workflowCompleted", d -> d.workflowCompleted(w));
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        fanOut("workflowFailed", d -> d.workflowFailed(w, exceptionType));
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
    public void signalPersisted(SignalInfo s) {
        fanOut("signalPersisted", d -> d.signalPersisted(s));
    }

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        fanOut("signalConsumed", d -> d.signalConsumed(s, replayed));
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
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        fanOut("standDown", d -> d.standDown(reason, workflowId, detail));
    }
}
