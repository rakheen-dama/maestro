package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
 *   <tr><td>{@code maestro.workflow.completed}</td><td>Counter</td><td>workflow</td><td>{@link #workflowCompleted}</td></tr>
 *   <tr><td>{@code maestro.workflow.failed}</td><td>Counter</td><td>workflow</td><td>{@link #workflowFailed}</td></tr>
 *   <tr><td>{@code maestro.workflow.compensated}</td><td>Counter</td><td>workflow</td><td>{@link #workflowCompensating}</td></tr>
 *   <tr><td>{@code maestro.workflow.terminated}</td><td>Counter</td><td>workflow</td><td>{@link #workflowTerminated}</td></tr>
 *   <tr><td>{@code maestro.activity.duration}</td><td>Timer</td><td>workflow, activity, outcome</td><td>{@link #activityCompleted}/{@link #activityFailed} (live only)</td></tr>
 *   <tr><td>{@code maestro.signal.consumed}</td><td>Counter</td><td>workflow, signal</td><td>{@link #signalConsumed} (live only)</td></tr>
 *   <tr><td>{@code maestro.timer.fired}</td><td>Counter</td><td>workflow</td><td>{@link #timerFired} (live only)</td></tr>
 *   <tr><td>{@code maestro.recovery.scanned}</td><td>Counter</td><td>—</td><td>{@link #recoveryPass}</td></tr>
 *   <tr><td>{@code maestro.recovery.adopted}</td><td>Counter</td><td>—</td><td>{@link #recoveryPass}</td></tr>
 *   <tr><td>{@code maestro.lock.renew.failures}</td><td>Counter</td><td>outcome=error|lost</td><td>{@link #instanceLockRenewFailed}/{@link #instanceLockLost}</td></tr>
 *   <tr><td>{@code maestro.standdown}</td><td>Counter</td><td>reason</td><td>{@link #standDown}</td></tr>
 * </table>
 *
 * <p>{@code maestro.workflows.running} and {@code maestro.workflows.parked}
 * (Gauges) are <b>not</b> registered by this class — they need the
 * {@code WorkflowExecutor} bean, which this observer never sees; they are
 * registered by {@link MaestroEngineGauges} instead.
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
    private static final String TAG_REASON = "reason";

    private static final String UNKNOWN_WORKFLOW_TYPE = "unknown";

    private final MeterRegistry registry;
    private final Set<String> warnedMeterNames = ConcurrentHashMap.newKeySet();

    /**
     * @param registry the Micrometer registry meters are registered against
     */
    public MicrometerEngineObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void workflowStarted(WorkflowInfo w) {
        increment("maestro.workflow.started", TAG_WORKFLOW, w.workflowType());
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

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        if (replayed) {
            return;
        }
        var workflowType = s.workflowType() != null ? s.workflowType() : UNKNOWN_WORKFLOW_TYPE;
        increment("maestro.signal.consumed", TAG_WORKFLOW, workflowType, TAG_SIGNAL, s.signalName());
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

    private static String reasonTag(StandDownReason reason) {
        return switch (reason) {
            case UNKNOWN_EVENT_TYPE -> "unknown_event_type";
            case UNKNOWN_EVENT_PAYLOAD -> "unknown_event_payload";
            case STALE_RUN -> "stale_run";
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
