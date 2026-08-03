package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test fixture: records every {@link EngineObserver} callback invocation in
 * arrival order, with typed views per callback family.
 *
 * <p>Thread-safe — callbacks arrive concurrently from workflow virtual
 * threads, poller threads and the lock renewer.
 */
public final class RecordingEngineObserver implements EngineObserver {

    /** One recorded callback: its name plus the arguments it carried. */
    public record Invocation(String callback, List<Object> args) {}

    public record ActivityCompletion(ActivityInfo info, Duration duration, boolean replayed) {}
    public record ActivityFailure(ActivityInfo info, Duration duration,
                                  String exceptionType, boolean replayed) {}
    public record SignalConsumption(SignalInfo info, boolean replayed) {}
    public record TimerCallback(TimerInfo info, boolean replayed) {}
    public record ParkEvent(WorkflowInfo info, ParkKind kind) {}
    public record RecoveryPassCall(int scanned, int adopted) {}
    public record StandDownCall(StandDownReason reason, String workflowId, @Nullable String detail) {}
    public record AbandonCall(WorkflowInfo info, AbandonReason reason) {}
    public record WorkflowFailure(WorkflowInfo info, String exceptionType) {}

    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    private final List<WorkflowInfo> started = new CopyOnWriteArrayList<>();
    private final List<WorkflowInfo> resumed = new CopyOnWriteArrayList<>();
    private final List<WorkflowInfo> completed = new CopyOnWriteArrayList<>();
    private final List<WorkflowFailure> failed = new CopyOnWriteArrayList<>();
    private final List<WorkflowInfo> compensating = new CopyOnWriteArrayList<>();
    private final List<WorkflowInfo> terminated = new CopyOnWriteArrayList<>();
    private final List<ParkEvent> parked = new CopyOnWriteArrayList<>();
    private final List<ParkEvent> unparked = new CopyOnWriteArrayList<>();
    private final List<ActivityInfo> activityStarted = new CopyOnWriteArrayList<>();
    private final List<ActivityCompletion> activityCompleted = new CopyOnWriteArrayList<>();
    private final List<ActivityFailure> activityFailed = new CopyOnWriteArrayList<>();
    private final List<SignalInfo> signalPersisted = new CopyOnWriteArrayList<>();
    private final List<SignalConsumption> signalConsumed = new CopyOnWriteArrayList<>();
    private final List<TimerCallback> timerScheduled = new CopyOnWriteArrayList<>();
    private final List<TimerCallback> timerFired = new CopyOnWriteArrayList<>();
    private final List<TimerCallback> timerCancelled = new CopyOnWriteArrayList<>();
    private final List<String> lockAcquired = new CopyOnWriteArrayList<>();
    private final List<String> lockRenewFailed = new CopyOnWriteArrayList<>();
    private final List<String> lockLost = new CopyOnWriteArrayList<>();
    private final List<RecoveryPassCall> recoveryPasses = new CopyOnWriteArrayList<>();
    private final List<StandDownCall> standDowns = new CopyOnWriteArrayList<>();
    private final List<AbandonCall> abandoned = new CopyOnWriteArrayList<>();

    // ── EngineObserver callbacks ──────────────────────────────────────

    @Override
    public void workflowStarted(WorkflowInfo w) {
        record("workflowStarted", w);
        started.add(w);
    }

    @Override
    public void workflowResumed(WorkflowInfo w) {
        record("workflowResumed", w);
        resumed.add(w);
    }

    @Override
    public void workflowCompleted(WorkflowInfo w) {
        record("workflowCompleted", w);
        completed.add(w);
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        record("workflowFailed", w, exceptionType);
        failed.add(new WorkflowFailure(w, exceptionType));
    }

    @Override
    public void workflowCompensating(WorkflowInfo w) {
        record("workflowCompensating", w);
        compensating.add(w);
    }

    @Override
    public void workflowTerminated(WorkflowInfo w) {
        record("workflowTerminated", w);
        terminated.add(w);
    }

    @Override
    public void workflowParked(WorkflowInfo w, ParkKind kind) {
        record("workflowParked", w, kind);
        parked.add(new ParkEvent(w, kind));
    }

    @Override
    public void workflowUnparked(WorkflowInfo w, ParkKind kind) {
        record("workflowUnparked", w, kind);
        unparked.add(new ParkEvent(w, kind));
    }

    @Override
    public void activityStarted(ActivityInfo a) {
        record("activityStarted", a);
        activityStarted.add(a);
    }

    @Override
    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
        record("activityCompleted", a, duration, replayed);
        activityCompleted.add(new ActivityCompletion(a, duration, replayed));
    }

    @Override
    public void activityFailed(ActivityInfo a, Duration duration,
                               String exceptionType, boolean replayed) {
        record("activityFailed", a, duration, exceptionType, replayed);
        activityFailed.add(new ActivityFailure(a, duration, exceptionType, replayed));
    }

    @Override
    public void signalPersisted(SignalInfo s) {
        record("signalPersisted", s);
        signalPersisted.add(s);
    }

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        record("signalConsumed", s, replayed);
        signalConsumed.add(new SignalConsumption(s, replayed));
    }

    @Override
    public void timerScheduled(TimerInfo t, boolean replayed) {
        record("timerScheduled", t, replayed);
        timerScheduled.add(new TimerCallback(t, replayed));
    }

    @Override
    public void timerFired(TimerInfo t, boolean replayed) {
        record("timerFired", t, replayed);
        timerFired.add(new TimerCallback(t, replayed));
    }

    @Override
    public void timerCancelled(TimerInfo t, boolean replayed) {
        record("timerCancelled", t, replayed);
        timerCancelled.add(new TimerCallback(t, replayed));
    }

    @Override
    public void instanceLockAcquired(String workflowId) {
        record("instanceLockAcquired", workflowId);
        lockAcquired.add(workflowId);
    }

    @Override
    public void instanceLockRenewFailed(String workflowId) {
        record("instanceLockRenewFailed", workflowId);
        lockRenewFailed.add(workflowId);
    }

    @Override
    public void instanceLockLost(String workflowId) {
        record("instanceLockLost", workflowId);
        lockLost.add(workflowId);
    }

    @Override
    public void recoveryPass(int scanned, int adopted) {
        record("recoveryPass", scanned, adopted);
        recoveryPasses.add(new RecoveryPassCall(scanned, adopted));
    }

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        record("standDown", reason, workflowId, String.valueOf(detail));
        standDowns.add(new StandDownCall(reason, workflowId, detail));
    }

    @Override
    public void runAbandoned(WorkflowInfo w, AbandonReason reason) {
        record("runAbandoned", w, reason);
        abandoned.add(new AbandonCall(w, reason));
    }

    // ── Typed views ───────────────────────────────────────────────────

    public List<Invocation> invocations() { return List.copyOf(invocations); }
    public List<String> callbackNames() { return invocations.stream().map(Invocation::callback).toList(); }

    public List<WorkflowInfo> started() { return List.copyOf(started); }
    public List<WorkflowInfo> resumed() { return List.copyOf(resumed); }
    public List<WorkflowInfo> completed() { return List.copyOf(completed); }
    public List<WorkflowFailure> failed() { return List.copyOf(failed); }
    public List<WorkflowInfo> compensating() { return List.copyOf(compensating); }
    public List<WorkflowInfo> terminated() { return List.copyOf(terminated); }
    public List<ParkEvent> parked() { return List.copyOf(parked); }
    public List<ParkEvent> unparked() { return List.copyOf(unparked); }
    public List<ActivityInfo> activityStarted() { return List.copyOf(activityStarted); }
    public List<ActivityCompletion> activityCompleted() { return List.copyOf(activityCompleted); }
    public List<ActivityFailure> activityFailed() { return List.copyOf(activityFailed); }
    public List<SignalInfo> signalPersisted() { return List.copyOf(signalPersisted); }
    public List<SignalConsumption> signalConsumed() { return List.copyOf(signalConsumed); }
    public List<TimerCallback> timerScheduled() { return List.copyOf(timerScheduled); }
    public List<TimerCallback> timerFired() { return List.copyOf(timerFired); }
    public List<TimerCallback> timerCancelled() { return List.copyOf(timerCancelled); }
    public List<String> lockAcquired() { return List.copyOf(lockAcquired); }
    public List<String> lockRenewFailed() { return List.copyOf(lockRenewFailed); }
    public List<String> lockLost() { return List.copyOf(lockLost); }
    public List<RecoveryPassCall> recoveryPasses() { return List.copyOf(recoveryPasses); }
    public List<StandDownCall> standDowns() { return List.copyOf(standDowns); }
    public List<AbandonCall> abandoned() { return List.copyOf(abandoned); }

    /** Completions of one named activity filtered by the replayed flag. */
    public List<ActivityCompletion> completionsOf(String activityName, boolean replayed) {
        return activityCompleted.stream()
                .filter(c -> c.info().activityName().equals(activityName) && c.replayed() == replayed)
                .toList();
    }

    private void record(String callback, Object... args) {
        invocations.add(new Invocation(callback, List.of(args)));
    }
}
