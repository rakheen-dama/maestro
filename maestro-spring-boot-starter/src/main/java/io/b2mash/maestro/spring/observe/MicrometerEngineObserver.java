package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds {@link EngineObserver} callbacks to Micrometer meters, exactly as
 * catalogued in the observability design doc §2.2.
 *
 * <h2>Meter catalog</h2>
 * <table>
 *   <caption>name / type / tags / source callback</caption>
 *   <tr><th>Meter</th><th>Type</th><th>Tags</th><th>Source</th></tr>
 *   <tr><td>{@code maestro.workflow.started}</td><td>Counter</td><td>workflow</td><td>{@link #workflowStarted}</td></tr>
 *   <tr><td>{@code maestro.workflow.start.deferred}</td><td>Counter</td><td>workflow, reason=lock_held_elsewhere|lock_unavailable</td><td>{@link #workflowStartDeferred}</td></tr>
 *   <tr><td>{@code maestro.workflow.completed}</td><td>Counter</td><td>workflow</td><td>{@link #workflowCompleted}</td></tr>
 *   <tr><td>{@code maestro.workflow.failed}</td><td>Counter</td><td>workflow</td><td>{@link #workflowFailed}</td></tr>
 *   <tr><td>{@code maestro.workflow.compensated}</td><td>Counter</td><td>workflow</td><td>{@link #workflowCompensating}</td></tr>
 *   <tr><td>{@code maestro.workflow.terminated}</td><td>Counter</td><td>workflow</td><td>{@link #workflowTerminated}</td></tr>
 *   <tr><td>{@code maestro.workflow.duration}</td><td>Timer</td><td>workflow, outcome=completed|failed</td><td>{@link #workflowFinished}</td></tr>
 *   <tr><td>{@code maestro.activity.duration}</td><td>Timer</td><td>workflow, activity, outcome</td><td>{@link #activityCompleted}/{@link #activityFailed} (live only)</td></tr>
 *   <tr><td>{@code maestro.activity.result.unreadable}</td><td>Counter</td><td>workflow, activity</td><td>{@link #activityResultUnreadable} (live only)</td></tr>
 *   <tr><td>{@code maestro.signal.consumed}</td><td>Counter</td><td>workflow, signal</td><td>{@link #signalConsumed} (live only)</td></tr>
 *   <tr><td>{@code maestro.signal.duplicates}</td><td>Counter</td><td>workflow, signal</td><td>{@link #signalDuplicateSuppressed}</td></tr>
 *   <tr><td>{@code maestro.timer.fired}</td><td>Counter</td><td>workflow</td><td>{@link #timerFired} (live only)</td></tr>
 *   <tr><td>{@code maestro.recovery.scanned}</td><td>Counter</td><td>—</td><td>{@link #recoveryPass}</td></tr>
 *   <tr><td>{@code maestro.recovery.adopted}</td><td>Counter</td><td>—</td><td>{@link #recoveryPass}</td></tr>
 *   <tr><td>{@code maestro.retention.purged}</td><td>Counter</td><td>kind=instance|event|signal|timer</td><td>{@link #retentionPass}</td></tr>
 *   <tr><td>{@code maestro.retention.sweeps}</td><td>Counter</td><td>—</td><td>{@link #retentionPass}</td></tr>
 *   <tr><td>{@code maestro.lock.renew.failures}</td><td>Counter</td><td>outcome=error|lost</td><td>{@link #instanceLockRenewFailed}/{@link #instanceLockLost}</td></tr>
 *   <tr><td>{@code maestro.loop.errors}</td><td>Counter</td><td>loop</td><td>{@link #backgroundLoopError}; the five engine loops' series are registered at zero on construction</td></tr>
 *   <tr><td>{@code maestro.standdown}</td><td>Counter</td><td>reason</td><td>{@link #standDown}</td></tr>
 *   <tr><td>{@code maestro.run.abandoned}</td><td>Counter</td><td>reason=shutdown|terminated|converged|terminal_write_failed|node_error|store_unavailable</td><td>{@link #runAbandoned}</td></tr>
 * </table>
 *
 * <p>The gauges ({@code maestro.workflows.running}, {@code maestro.workflows.parked},
 * {@code maestro.wake.permits} and {@code maestro.recovery.deferred}) are
 * <b>not</b> registered by this class — they need the
 * {@code WorkflowExecutor} bean, which this observer never sees; they are
 * registered by {@link MaestroEngineGauges} instead, and
 * {@code maestro.taskqueue.slots.used} by {@link MaestroTaskQueueGauges}.
 *
 * <h2>Cardinality</h2>
 * Tag values are always code-bounded: {@code workflowType}, {@code
 * activityName}, and signal names are string literals in workflow/listener
 * code. This class never tags a meter with {@code workflowId}, {@code
 * runId}, or a timer ID.
 *
 * <h2>Replay policy</h2>
 * Every counting/timing callback that carries a {@code replayed} flag returns
 * immediately when it is {@code true} — the engine emits replay traffic for
 * audit observers, but a recovered workflow's replayed steps must never be
 * double-counted (design §1.1, §2.1).
 *
 * <h2>Defensive registration</h2>
 * Every meter is looked up/created with {@code .register(registry)} on each
 * callback — Micrometer deduplicates by (name, tags), so this is cheap and
 * correct for the normal case. A caller supplying the same meter name under a
 * different meter <em>type</em> (e.g. an unrelated library's own {@code
 * maestro.*}-named meter) makes every {@code .register(...)} call for that
 * name throw {@link IllegalArgumentException}; that throw is contained by
 * {@link io.b2mash.maestro.core.observe.CompositeEngineObserver} (Ruling 4),
 * but containment alone would still log a WARN on <em>every single
 * emission</em>. This class additionally warns at most once per distinct
 * meter name, so a genuine conflict is visible without flooding the log.
 *
 * <h2>Thread safety</h2>
 * Stateless beyond the registry reference and a small warn-once set;
 * thread-safe, matching {@link EngineObserver}'s callback contract.
 */
public final class MicrometerEngineObserver implements EngineObserver {

    private static final Logger logger = LoggerFactory.getLogger(MicrometerEngineObserver.class);

    private static final String TAG_WORKFLOW = "workflow";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_SIGNAL = "signal";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_KIND = "kind";
    private static final String TAG_REASON = "reason";
    private static final String TAG_LOOP = "loop";

    private static final String UNKNOWN_WORKFLOW_TYPE = "unknown";

    private static final String LOOP_ERRORS = "maestro.loop.errors";

    /**
     * The five engine loops' fixed names, as {@code EngineObserver#backgroundLoopError}
     * lists them; {@code LoopErrorCounterWiringTest} fails if a loop reports
     * under any other name.
     */
    private static final List<String> ENGINE_LOOPS = List.of(
            "timer-poller", "recovery-poller", "signal-janitor", "retention-sweeper", "instance-lock-renewer");

    private final MeterRegistry registry;
    private final Set<String> warnedMeterNames = ConcurrentHashMap.newKeySet();

    /**
     * @param registry the Micrometer registry meters are registered against
     */
    public MicrometerEngineObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        // Registered at zero up front, one series per engine loop: a rate or
        // increase() query then sees the first Error, instead of a series that
        // first appears already at 1.
        for (var loop : ENGINE_LOOPS) {
            safely(LOOP_ERRORS, () -> Counter.builder(LOOP_ERRORS).tag(TAG_LOOP, loop).register(registry));
        }
    }

    @Override
    public void workflowStarted(WorkflowInfo w) {
        increment("maestro.workflow.started", TAG_WORKFLOW, w.workflowType());
    }

    @Override
    public void workflowStartDeferred(WorkflowInfo w, boolean lockHeldElsewhere) {
        increment("maestro.workflow.start.deferred", TAG_WORKFLOW, w.workflowType(),
                TAG_REASON, lockHeldElsewhere ? "lock_held_elsewhere" : "lock_unavailable");
    }

    @Override
    public void workflowCompleted(WorkflowInfo w) {
        increment("maestro.workflow.completed", TAG_WORKFLOW, w.workflowType());
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        // exceptionType is intentionally not tagged — open-ended cardinality (design §2.2 note).
        increment("maestro.workflow.failed", TAG_WORKFLOW, w.workflowType());
    }

    /**
     * Records the workflow's start-to-terminal wall time under
     * {@code maestro.workflow.duration}, tagged {@code outcome=completed} or
     * {@code outcome=failed}. The engine fires this only with those two
     * statuses; any other status records nothing, so the {@code outcome} tag
     * value set stays closed at two. One sample per terminal transition: a
     * manually retried workflow records once per outcome it reached.
     */
    @Override
    public void workflowFinished(WorkflowInfo w, WorkflowStatus status, Duration elapsed) {
        String outcome;
        switch (status) {
            case COMPLETED -> outcome = "completed";
            case FAILED -> outcome = "failed";
            default -> {
                return;
            }
        }
        record("maestro.workflow.duration", elapsed, TAG_WORKFLOW, w.workflowType(), TAG_OUTCOME, outcome);
    }

    @Override
    public void workflowCompensating(WorkflowInfo w) {
        increment("maestro.workflow.compensated", TAG_WORKFLOW, w.workflowType());
    }

    @Override
    public void workflowTerminated(WorkflowInfo w) {
        increment("maestro.workflow.terminated", TAG_WORKFLOW, w.workflowType());
    }

    @Override
    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
        if (replayed) {
            return;
        }
        record("maestro.activity.duration", duration,
                TAG_WORKFLOW, a.workflowType(), TAG_ACTIVITY, a.activityName(), TAG_OUTCOME, "completed");
    }

    @Override
    public void activityFailed(ActivityInfo a, Duration duration, String exceptionType, boolean replayed) {
        if (replayed) {
            return;
        }
        record("maestro.activity.duration", duration,
                TAG_WORKFLOW, a.workflowType(), TAG_ACTIVITY, a.activityName(), TAG_OUTCOME, "failed");
    }

    /**
     * Counts a live activity result that was recorded but either cannot be
     * read back as its declared type (WP1 D9; a recovery of the run will stand
     * it down as {@code unknown_event_payload}) or reads back as another class
     * than the object the activity returned ({@code exceptionType} is
     * {@link EngineObserver#READ_BACK_TYPE_MISMATCH}; a replay hands workflow
     * code the read-back class). Either way the run continued with the
     * activity's own object. One counter for both: the cause is not a tag. The
     * engine fires this live only, so there is no replay flag.
     */
    @Override
    public void activityResultUnreadable(ActivityInfo a, String exceptionType) {
        // exceptionType is intentionally not tagged — open-ended cardinality, as for workflowFailed.
        increment("maestro.activity.result.unreadable", TAG_WORKFLOW, a.workflowType(), TAG_ACTIVITY, a.activityName());
    }

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        if (replayed) {
            return;
        }
        var workflowType = s.workflowType() != null ? s.workflowType() : UNKNOWN_WORKFLOW_TYPE;
        increment("maestro.signal.consumed", TAG_WORKFLOW, workflowType, TAG_SIGNAL, s.signalName());
    }

    /**
     * Counts a suppressed duplicate delivery.
     *
     * <p>Unlike {@link #signalConsumed}, whose signal name is the literal an
     * {@code awaitSignal} call names, this callback runs on the <em>delivery</em>
     * side: the name comes off the wire. For a pre-delivered (orphan) signal —
     * no instance yet, so no workflow type — nothing bounds it, and an external
     * producer could mint one tag value per name it sends. So the {@code signal}
     * tag carries the name only when the workflow type is known (the instance
     * exists), and the fixed placeholder {@value #UNKNOWN_WORKFLOW_TYPE}
     * otherwise.
     */
    @Override
    public void signalDuplicateSuppressed(SignalInfo s) {
        var workflowType = s.workflowType();
        if (workflowType == null) {
            increment("maestro.signal.duplicates", TAG_WORKFLOW, UNKNOWN_WORKFLOW_TYPE,
                    TAG_SIGNAL, UNKNOWN_WORKFLOW_TYPE);
            return;
        }
        increment("maestro.signal.duplicates", TAG_WORKFLOW, workflowType, TAG_SIGNAL, s.signalName());
    }

    @Override
    public void timerFired(TimerInfo t, boolean replayed) {
        if (replayed) {
            return;
        }
        increment("maestro.timer.fired", TAG_WORKFLOW, t.workflowType());
    }

    @Override
    public void recoveryPass(int scanned, int adopted) {
        safely("maestro.recovery.scanned", () ->
                Counter.builder("maestro.recovery.scanned").register(registry).increment(scanned));
        safely("maestro.recovery.adopted", () ->
                Counter.builder("maestro.recovery.adopted").register(registry).increment(adopted));
    }

    @Override
    public void retentionPass(PurgeResult purged) {
        safely("maestro.retention.sweeps", () ->
                Counter.builder("maestro.retention.sweeps").register(registry).increment());
        purged("instance", purged.instances());
        purged("event", purged.events());
        purged("signal", purged.signals());
        purged("timer", purged.timers());
    }

    private void purged(String kind, int count) {
        safely("maestro.retention.purged", () ->
                Counter.builder("maestro.retention.purged").tag(TAG_KIND, kind).register(registry).increment(count));
    }

    @Override
    public void instanceLockRenewFailed(String workflowId) {
        increment("maestro.lock.renew.failures", TAG_OUTCOME, "error");
    }

    @Override
    public void instanceLockLost(String workflowId) {
        increment("maestro.lock.renew.failures", TAG_OUTCOME, "lost");
    }

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        increment("maestro.standdown", TAG_REASON, reasonTag(reason));
    }

    /**
     * Counts an {@code Error} a background loop contained, tagged with the
     * loop's fixed name only (five values, bounded by code). The error type
     * is deliberately not a tag: it is open-ended, and the loop's {@code ERROR}
     * log names it.
     */
    @Override
    public void backgroundLoopError(String loop, String errorType) {
        increment(LOOP_ERRORS, TAG_LOOP, loop);
    }

    private static String reasonTag(StandDownReason reason) {
        return switch (reason) {
            case UNKNOWN_EVENT_TYPE -> "unknown_event_type";
            case UNKNOWN_EVENT_PAYLOAD -> "unknown_event_payload";
            case NONDETERMINISTIC_HISTORY -> "nondeterministic_history";
            case STALE_RUN -> "stale_run";
            case LEASE_FENCED -> "lease_fenced";
        };
    }

    /**
     * Counts an abandoned local run under its own meter, tagged by reason only.
     *
     * <p>A terminate moves {@code maestro.workflow.terminated} once (from
     * {@link #workflowTerminated}, on the node that handled the terminate) and
     * this counter ({@code reason=terminated}) at most once, from the
     * workflow's own thread on the node running it — not at all if no node
     * holds a live run. Never fold one into the other.
     */
    @Override
    public void runAbandoned(WorkflowInfo w, AbandonReason reason) {
        increment("maestro.run.abandoned", TAG_REASON, abandonTag(reason));
    }

    private static String abandonTag(AbandonReason reason) {
        return switch (reason) {
            case SHUTDOWN -> "shutdown";
            case TERMINATED -> "terminated";
            case CONVERGED -> "converged";
            case TERMINAL_WRITE_FAILED -> "terminal_write_failed";
            case NODE_ERROR -> "node_error";
            case STORE_UNAVAILABLE -> "store_unavailable";
        };
    }

    // ── Registration helpers ────────────────────────────────────────────

    private void increment(String name, String... tags) {
        safely(name, () -> Counter.builder(name).tags(tags).register(registry).increment());
    }

    private void record(String name, Duration duration, String... tags) {
        safely(name, () -> Timer.builder(name).tags(tags).register(registry).record(duration));
    }

    /**
     * Runs a meter registration/emission, warning at most once per distinct
     * meter {@code name} if it throws (see the class Javadoc's "Defensive
     * registration" section) rather than on every single callback.
     */
    private void safely(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (warnedMeterNames.add(name)) {
                logger.warn("Failed to register/emit meter '{}' — likely a type conflict with an "
                        + "existing meter of the same name; further failures for this meter are "
                        + "suppressed", name, e);
            }
        }
    }
}
