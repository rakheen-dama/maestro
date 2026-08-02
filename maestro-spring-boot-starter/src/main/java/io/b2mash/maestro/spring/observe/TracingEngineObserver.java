package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.ParkKind;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Binds {@link EngineObserver} callbacks to Micrometer Tracing spans, building
 * the span topology of the observability design doc §3.2 and restoring the
 * remote parent carried durably on a signal row (§4.3).
 *
 * <h2>Span topology</h2>
 * <table>
 *   <caption>span / name / opened at / closed at / parent</caption>
 *   <tr><th>Span</th><th>Name</th><th>Opened at</th><th>Closed at</th><th>Parent</th></tr>
 *   <tr>
 *     <td>Workflow run segment</td><td>{@code maestro.workflow.run}</td>
 *     <td>the first live callback on a workflow thread that has no open segment</td>
 *     <td>{@link #workflowParked} / {@link #workflowCompleted} /
 *         {@link #workflowFailed} / {@link #workflowTerminated} / {@link #standDown}</td>
 *     <td>the remote context from a signal's durable trace context if there is
 *         one; else the previous segment of this thread (the live park→unpark
 *         chain); else a new root</td>
 *   </tr>
 *   <tr>
 *     <td>Activity</td><td>{@code maestro.activity}</td>
 *     <td>{@link #activityStarted} (live only)</td>
 *     <td>{@link #activityCompleted} / {@link #activityFailed} (live only)</td>
 *     <td>the open segment</td>
 *   </tr>
 * </table>
 *
 * <p>Span events on the open segment (live only):
 * {@code maestro.signal.consumed}, {@code maestro.signal.persisted},
 * {@code maestro.timer.fired}, {@code maestro.timer.cancelled}.
 *
 * <h2>Segments open lazily, on the workflow thread</h2>
 * The design doc's §3.2 table names {@code workflowStarted} /
 * {@code workflowResumed} as segment-opening callbacks. Both are emitted by
 * {@code WorkflowExecutor} on the <em>launching</em> thread — the caller of
 * {@code startWorkflow}, or the recovery thread — not on the workflow's virtual
 * thread, which is created immediately afterwards. Opening a span there would
 * put a {@code maestro.workflow.run} span and an unclosed scope on an unrelated
 * (often HTTP request) thread while leaving the workflow thread with no segment
 * at all, so every activity span would be a detached root.
 *
 * <p>This adapter therefore opens the segment <b>lazily</b>: the first live
 * callback that genuinely runs on the workflow thread and needs a segment opens
 * it. {@code workflowStarted} and {@code workflowResumed} create nothing. The
 * observable topology is unchanged except that a segment begins at the run's
 * first observable step rather than a few microseconds earlier — and for a
 * recovered run that is arguably more honest, because everything between the
 * resume and the first live step is silent replay by design.
 *
 * <h2>Order tolerance — why the engine's two orderings both work</h2>
 * {@code SignalManager} emits {@code signalConsumed} <em>before</em>
 * {@code workflowUnparked}; {@code DefaultWorkflowOperations} emits
 * {@code timerFired} <em>after</em> it. Both are correct for their own reasons,
 * and neither needed changing: segment opening is idempotent
 * ({@code ensureSegment}), so whichever of the pair arrives first opens the
 * segment and the other finds it already open. The signal-consumed event
 * therefore lands inside a segment (the nesting requirement) <em>and</em> the
 * segment adopts the signal's remote parent (the restoration requirement).
 *
 * <h2>No spans during replay</h2>
 * Every callback carrying {@code replayed} returns immediately when it is
 * {@code true}, and {@code activityStarted} is emitted only on the live path by
 * construction. A recovered workflow replaying N memoized steps produces zero
 * spans for them.
 *
 * <h2>Cardinality</h2>
 * Span attributes are per-trace, not per-time-series, so {@code
 * maestro.workflow.id} / {@code maestro.run.id} are recorded here deliberately
 * — the same values must never become <em>meter tags</em>, and this class
 * registers no meters.
 *
 * <h2>Thread safety</h2>
 * Span state is held in a {@link ThreadLocal} and never shared. That is sound
 * because a workflow's virtual thread survives a live park (the thread that
 * parks is the thread that resumes) and each parallel branch runs on its own
 * thread with its own callbacks. The state is cleared on every segment-closing
 * callback; {@link #clearThreadState()} is available for embedders that reuse
 * threads.
 */
public final class TracingEngineObserver implements EngineObserver {

    private static final Logger logger = LoggerFactory.getLogger(TracingEngineObserver.class);

    private static final String SEGMENT_SPAN = "maestro.workflow.run";
    private static final String ACTIVITY_SPAN = "maestro.activity";

    private static final String EVENT_SIGNAL_CONSUMED = "maestro.signal.consumed";
    private static final String EVENT_SIGNAL_PERSISTED = "maestro.signal.persisted";
    private static final String EVENT_TIMER_FIRED = "maestro.timer.fired";
    private static final String EVENT_TIMER_CANCELLED = "maestro.timer.cancelled";

    private static final String ATTR_WORKFLOW_ID = "maestro.workflow.id";
    private static final String ATTR_RUN_ID = "maestro.run.id";
    private static final String ATTR_WORKFLOW_TYPE = "maestro.workflow.type";
    private static final String ATTR_SERVICE_NAME = "maestro.service.name";
    private static final String ATTR_ACTIVITY_NAME = "maestro.activity.name";
    private static final String ATTR_SEQUENCE = "maestro.sequence";
    private static final String ATTR_SIGNAL_NAME = "maestro.signal.name";
    private static final String ATTR_TIMER_ID = "maestro.timer.id";
    private static final String ATTR_ERROR_TYPE = "maestro.error.type";
    private static final String ATTR_STANDDOWN_REASON = "maestro.standdown.reason";

    private static final String TRACEPARENT = "traceparent";

    /**
     * W3C Trace Context {@code traceparent} grammar. A value that does not match
     * is treated as absent — the resumed segment simply starts a new trace. This
     * is the only place a stored trace context is ever interpreted; the store and
     * the engine treat it as opaque bytes (RULING 2).
     */
    private static final Pattern TRACEPARENT_GRAMMAR =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);

    private final Tracer tracer;
    private final Propagator propagator;
    private final ThreadLocal<RunState> state = ThreadLocal.withInitial(RunState::new);
    private final Set<String> warnedCallbacks = ConcurrentHashMap.newKeySet();

    /** The node's service name — a per-engine constant; see rememberServiceName. */
    private volatile @Nullable String serviceName;

    /**
     * @param tracer     the Micrometer tracer, supplied by Boot's tracing
     *                   auto-configuration from whichever bridge the application ships
     * @param propagator the Micrometer propagator, used to turn a stored
     *                   {@code traceparent} back into a remote parent
     */
    public TracingEngineObserver(Tracer tracer, Propagator propagator) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
    }

    // ── Workflow lifecycle ────────────────────────────────────────────

    @Override
    public void workflowStarted(WorkflowInfo w) {
        // No span: emitted on the launching thread, not the workflow thread.
        // See the class Javadoc. It does carry the node's service name, which
        // no other observation record does — remember it for segment spans.
        rememberServiceName(w);
    }

    @Override
    public void workflowResumed(WorkflowInfo w) {
        rememberServiceName(w);
    }

    @Override
    public void workflowCompleted(WorkflowInfo w) {
        rememberServiceName(w);
        safely("workflowCompleted", () -> closeRun(null));
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        rememberServiceName(w);
        safely("workflowFailed", () -> closeRun(exceptionType));
    }

    @Override
    public void workflowTerminated(WorkflowInfo w) {
        rememberServiceName(w);
        safely("workflowTerminated", () -> closeRun(null));
    }

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        safely("standDown", () -> {
            var s = state.get();
            if (s.segment != null) {
                s.segment.tag(ATTR_STANDDOWN_REASON, reason.name());
            }
            closeRun(null);
        });
    }

    // ── Run-segment boundaries ────────────────────────────────────────

    @Override
    public void workflowParked(WorkflowInfo w, ParkKind kind) {
        rememberServiceName(w);
        safely("workflowParked", this::closeSegment);
    }

    @Override
    public void workflowUnparked(WorkflowInfo w, ParkKind kind) {
        rememberServiceName(w);
        // Idempotent: signalConsumed may already have opened the resumed
        // segment (with the remote parent) a moment earlier.
        safely("workflowUnparked",
                () -> ensureSegment(w.workflowId(), w.workflowType(), w.serviceName(), null));

    }

    /**
     * Records the node's service name. {@code WorkflowInfo} is the only
     * observation record that carries it, yet a segment is routinely opened
     * from an {@code ActivityInfo}/{@code SignalInfo}/{@code TimerInfo}
     * callback. The value is a per-engine constant ({@code
     * WorkflowExecutor}'s own {@code serviceName}), so remembering the last one
     * seen is exact, not an approximation.
     */
    private void rememberServiceName(WorkflowInfo w) {
        this.serviceName = w.serviceName();
    }

    // ── Activities ────────────────────────────────────────────────────

    @Override
    public void activityStarted(ActivityInfo a) {
        safely("activityStarted", () -> {
            var s = state.get();
            ensureSegment(a.workflowId(), a.workflowType(), serviceName, null);
            // Defensive: an unclosed previous activity would nest wrongly.
            endActivity(s, null);
            var segment = s.segment;
            var builder = tracer.spanBuilder().name(ACTIVITY_SPAN);
            if (segment != null) {
                builder.setParent(segment.context());
            }
            var span = builder.start();
            span.tag(ATTR_WORKFLOW_ID, a.workflowId());
            span.tag(ATTR_WORKFLOW_TYPE, a.workflowType());
            span.tag(ATTR_ACTIVITY_NAME, a.activityName());
            span.tag(ATTR_SEQUENCE, a.sequenceNumber());
            tagRunId(span);
            s.activity = span;
            s.activityScope = tracer.withSpan(span);
        });
    }

    @Override
    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
        if (replayed) {
            return;
        }
        safely("activityCompleted", () -> endActivity(state.get(), null));
    }

    @Override
    public void activityFailed(ActivityInfo a, Duration duration,
                               String exceptionType, boolean replayed) {
        if (replayed) {
            return;
        }
        safely("activityFailed", () -> endActivity(state.get(), exceptionType));
    }

    // ── Signals ───────────────────────────────────────────────────────

    @Override
    public void signalPersisted(SignalInfo s) {
        // A delivery thread is not a run segment: record the event when one
        // happens to be open, never open one.
        safely("signalPersisted", () -> {
            var segment = state.get().segment;
            if (segment != null) {
                segment.event(EVENT_SIGNAL_PERSISTED);
            }
        });
    }

    @Override
    public void signalConsumed(SignalInfo s, boolean replayed) {
        if (replayed) {
            return;
        }
        safely("signalConsumed", () -> {
            ensureSegment(s.workflowId(), s.workflowType(), serviceName, s.traceContext());
            var segment = state.get().segment;
            if (segment != null) {
                segment.event(EVENT_SIGNAL_CONSUMED);
                segment.tag(ATTR_SIGNAL_NAME, s.signalName());
            }
        });
    }

    // ── Timers ────────────────────────────────────────────────────────

    @Override
    public void timerFired(TimerInfo t, boolean replayed) {
        if (replayed) {
            return;
        }
        safely("timerFired", () -> timerEvent(t, EVENT_TIMER_FIRED));
    }

    @Override
    public void timerCancelled(TimerInfo t, boolean replayed) {
        if (replayed) {
            return;
        }
        safely("timerCancelled", () -> timerEvent(t, EVENT_TIMER_CANCELLED));
    }

    // ── Thread hygiene ────────────────────────────────────────────────

    /**
     * Closes and discards this thread's span state without recording an
     * outcome. Provided for embedders (and tests) that reuse a thread across
     * workflow runs; the engine's own terminal callbacks already do this.
     */
    public void clearThreadState() {
        safely("clearThreadState", () -> closeRun(null));
    }

    // ── Internals ─────────────────────────────────────────────────────

    /**
     * Opens the run segment for this thread if none is open, or re-parents a
     * still-rootless segment when a remote context finally arrives (design
     * §4.3 step 2). Idempotent, which is what makes the adapter tolerant of the
     * engine's two different consume/unpark orderings.
     */
    private void ensureSegment(String workflowId, @Nullable String workflowType,
                               @Nullable String serviceName, @Nullable String traceContext) {
        var s = state.get();
        var remote = remoteParent(traceContext);

        if (s.segment != null) {
            if (remote == null || !s.segmentIsRoot) {
                return;
            }
            // A recovered run opened a rootless segment before its awaited
            // signal (and its remote parent) was known. Close it and continue
            // the run under the publisher's trace.
            closeSegment();
        }

        var builder = remote != null ? remote : tracer.spanBuilder();
        builder.name(SEGMENT_SPAN);
        boolean root;
        if (remote != null) {
            root = false;
            if (s.previousSegment != null) {
                // The cross-service parent wins, but the local park chain is
                // preserved as a link rather than silently dropped.
                builder.addLink(new Link(s.previousSegment));
            }
        } else if (s.previousSegment != null) {
            builder.setParent(s.previousSegment);
            root = false;
        } else {
            builder.setNoParent();
            root = true;
        }

        var span = builder.start();
        span.tag(ATTR_WORKFLOW_ID, workflowId);
        if (workflowType != null) {
            span.tag(ATTR_WORKFLOW_TYPE, workflowType);
        }
        if (serviceName != null) {
            span.tag(ATTR_SERVICE_NAME, serviceName);
        }
        tagRunId(span);
        s.segment = span;
        s.segmentScope = tracer.withSpan(span);
        s.segmentIsRoot = root;
    }

    /**
     * Turns a stored {@code traceparent} into a span builder already parented to
     * the remote context, or {@code null} when there is nothing usable. A
     * missing or malformed value degrades to {@code null} — a fresh root span —
     * and never to an exception (RULING 2).
     */
    private Span.@Nullable Builder remoteParent(@Nullable String traceparent) {
        if (traceparent == null
                || !TRACEPARENT_GRAMMAR.matcher(traceparent).matches()
                || traceparent.regionMatches(3, ZERO_TRACE_ID, 0, 32)
                || traceparent.regionMatches(36, ZERO_SPAN_ID, 0, 16)) {
            return null;
        }
        return propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
    }

    private void timerEvent(TimerInfo t, String eventName) {
        ensureSegment(t.workflowId(), t.workflowType(), serviceName, null);
        var segment = state.get().segment;
        if (segment != null) {
            segment.event(eventName);
            segment.tag(ATTR_TIMER_ID, t.timerId());
        }
    }

    private void endActivity(RunState s, @Nullable String exceptionType) {
        var span = s.activity;
        var scope = s.activityScope;
        s.activity = null;
        s.activityScope = null;
        if (scope != null) {
            scope.close();
        }
        if (span != null) {
            if (exceptionType != null) {
                span.tag(ATTR_ERROR_TYPE, exceptionType);
            }
            span.end();
        }
    }

    /** Closes the open segment, remembering it as the parent of the next one. */
    private void closeSegment() {
        var s = state.get();
        endActivity(s, null);
        var span = s.segment;
        var scope = s.segmentScope;
        s.segment = null;
        s.segmentScope = null;
        s.segmentIsRoot = false;
        if (scope != null) {
            scope.close();
        }
        if (span != null) {
            s.previousSegment = span.context();
            span.end();
        }
    }

    /** Closes the segment and forgets the whole run — nothing chains past it. */
    private void closeRun(@Nullable String exceptionType) {
        var s = state.get();
        if (exceptionType != null && s.segment != null) {
            s.segment.tag(ATTR_ERROR_TYPE, exceptionType);
        }
        closeSegment();
        s.previousSegment = null;
        state.remove();
    }

    private void tagRunId(Span span) {
        var runId = MDC.get(WorkflowMDC.KEY_RUN_ID);
        if (runId != null) {
            span.tag(ATTR_RUN_ID, runId);
        }
    }

    /**
     * Runs a callback body, containing any {@link RuntimeException} it raises and
     * warning at most once per callback name.
     *
     * <p>{@code CompositeEngineObserver} already contains a throwing observer
     * (RULING 4), but relying on that alone would leave a half-opened span or a
     * dangling scope on a live workflow thread and log on every emission. {@code
     * Error}s — the engine's control-flow signals — deliberately propagate.
     */
    private void safely(String callback, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            if (warnedCallbacks.add(callback)) {
                logger.warn("Maestro tracing observer failed in '{}' — spans for this callback "
                        + "will be incomplete; workflow execution is unaffected. "
                        + "This is logged once per callback.", callback, e);
            }
        }
    }

    /** Per-thread span state for one workflow run segment. */
    private static final class RunState {
        private @Nullable Span segment;
        private Tracer.@Nullable SpanInScope segmentScope;
        private @Nullable TraceContext previousSegment;
        private boolean segmentIsRoot;
        private @Nullable Span activity;
        private Tracer.@Nullable SpanInScope activityScope;
    }
}
