package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.ParkKind;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit pins for {@link TracingEngineObserver} — the span topology of
 * observability design doc §3.2 and the remote-parent restoration of §4.3,
 * asserted against a real OpenTelemetry SDK.
 */
@DisplayName("TracingEngineObserver builds the design §3.2 span topology")
class TracingEngineObserverTest {

    private static final String TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final String REMOTE_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String REMOTE_SPAN_ID = "b7ad6b7169203331";

    private static final String SEGMENT = "maestro.workflow.run";
    private static final String ACTIVITY = "maestro.activity";

    private static final WorkflowInfo WORKFLOW =
            new WorkflowInfo("order-1", "OrderWorkflow", "order-service");
    private static final ActivityInfo CHARGE =
            new ActivityInfo("order-1", "OrderWorkflow", "payment.charge", 3);
    private static final SignalInfo APPROVAL =
            new SignalInfo("order-1", "OrderWorkflow", "approval", null);
    private static final TimerInfo TIMER =
            new TimerInfo("order-1", "OrderWorkflow", "escalation");

    private OtelTracingFixture otel;
    private TracingEngineObserver observer;
    private String runId;

    @BeforeEach
    void setUp() {
        otel = new OtelTracingFixture();
        observer = new TracingEngineObserver(otel.tracer(), otel.propagator());
        runId = UUID.randomUUID().toString();
        MDC.put(WorkflowMDC.KEY_RUN_ID, runId);
    }

    @AfterEach
    void tearDown() {
        observer.clearThreadState();
        MDC.clear();
        otel.close();
    }

    // ── Replay: the load-bearing invariant ─────────────────────────────

    @Test
    @DisplayName("no spans at all for replayed callbacks — a replayed step is silent")
    void replayedCallbacksProduceNoSpans() {
        observer.activityCompleted(CHARGE, Duration.ofMillis(5), true);
        observer.activityFailed(CHARGE, Duration.ofMillis(5), "java.lang.IllegalStateException", true);
        observer.signalConsumed(new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), true);
        observer.timerScheduled(TIMER, true);
        observer.timerFired(TIMER, true);
        observer.timerCancelled(TIMER, true);

        assertEquals(0, otel.finishedSpans().size(),
                "replayed callbacks must never create or close a span");
        assertNull(otel.tracer().currentSpan(), "replayed callbacks must not open a scope either");
    }

    @Test
    @DisplayName("a replayed callback with a traceContext still creates nothing — replay never re-parents")
    void replayedSignalWithTraceContextIsSilent() {
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), true);
        observer.workflowCompleted(WORKFLOW);

        assertEquals(0, otel.finishedSpans().size());
    }

    // ── Segment + activity topology ───────────────────────────────────

    @Test
    @DisplayName("an activity span is a child of the run-segment span, and both carry the §3.2 attributes")
    void activitySpanIsChildOfSegment() {
        // workflowStarted opens no span (it runs on the launching thread) but it
        // is the only callback that carries the node's service name.
        observer.workflowStarted(WORKFLOW);
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(12), false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        var activities = otel.spansNamed(ACTIVITY);
        assertEquals(1, segments.size(), "exactly one run segment");
        assertEquals(1, activities.size(), "exactly one activity span");

        var segment = segments.getFirst();
        var activity = activities.getFirst();
        assertEquals(segment.getSpanId(), activity.getParentSpanId(),
                "the activity span must be a child of the open segment");
        assertEquals(segment.getTraceId(), activity.getTraceId());

        assertEquals("order-1", OtelTracingFixture.attribute(segment, "maestro.workflow.id"));
        assertEquals("OrderWorkflow", OtelTracingFixture.attribute(segment, "maestro.workflow.type"));
        assertEquals("order-service", OtelTracingFixture.attribute(segment, "maestro.service.name"));
        assertEquals(runId, OtelTracingFixture.attribute(segment, "maestro.run.id"),
                "run id comes from the MDC WorkflowMDC.populate guarantees on workflow threads");

        assertEquals("payment.charge", OtelTracingFixture.attribute(activity, "maestro.activity.name"));
        assertEquals(3L, activity.getAttributes().get(AttributeKey.longKey("maestro.sequence")));
    }

    @Test
    @DisplayName("a failed activity span records the error and still closes")
    void failedActivityClosesWithError() {
        observer.activityStarted(CHARGE);
        observer.activityFailed(CHARGE, Duration.ofMillis(4),
                "io.b2mash.maestro.core.exception.ActivityExecutionException", false);
        observer.workflowFailed(WORKFLOW, "io.b2mash.maestro.core.exception.ActivityExecutionException");

        var activities = otel.spansNamed(ACTIVITY);
        assertEquals(1, activities.size());
        assertEquals("io.b2mash.maestro.core.exception.ActivityExecutionException",
                OtelTracingFixture.attribute(activities.getFirst(), "maestro.error.type"));
    }

    @Test
    @DisplayName("workflowStarted and workflowResumed open no span — they run on the launching thread")
    void launchCallbacksOpenNoSpan() {
        observer.workflowStarted(WORKFLOW);
        observer.workflowResumed(WORKFLOW);

        assertNull(otel.tracer().currentSpan(),
                "no scope may be left open on the caller's thread");
        assertEquals(0, otel.finishedSpans().size());
    }

    // ── Park / unpark chain ───────────────────────────────────────────

    @Test
    @DisplayName("a park closes the segment; the next unpark opens a segment chained to it")
    void parkClosesSegmentAndUnparkChains() {
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(2), false);
        observer.workflowParked(WORKFLOW, ParkKind.TIMER);

        assertEquals(1, otel.spansNamed(SEGMENT).size(), "the segment closes at the park");
        assertNull(otel.tracer().currentSpan(), "the scope must be closed while parked");

        observer.workflowUnparked(WORKFLOW, ParkKind.TIMER);
        observer.timerFired(TIMER, false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(2, segments.size());
        assertEquals(segments.get(0).getSpanId(), segments.get(1).getParentSpanId(),
                "the resumed segment chains to the segment that parked");
        assertEquals(segments.get(0).getTraceId(), segments.get(1).getTraceId());
    }

    // ── Span events ───────────────────────────────────────────────────

    @Test
    @DisplayName("timer fire and cancel are span events on the open segment")
    void timerEventsRecordedOnSegment() {
        observer.workflowUnparked(WORKFLOW, ParkKind.TIMER);
        observer.timerFired(TIMER, false);
        observer.timerCancelled(TIMER, false);
        observer.workflowCompleted(WORKFLOW);

        var segment = otel.spansNamed(SEGMENT).getFirst();
        var eventNames = segment.getEvents().stream().map(e -> e.getName()).toList();
        assertTrue(eventNames.contains("maestro.timer.fired"), () -> "events were " + eventNames);
        assertTrue(eventNames.contains("maestro.timer.cancelled"), () -> "events were " + eventNames);

        // Micrometer Tracing's Span.event(String) carries no attributes, so the
        // design's "event attribute maestro.timer.id" is recorded as a span tag.
        assertEquals("escalation", OtelTracingFixture.attribute(segment, "maestro.timer.id"));
    }

    @Test
    @DisplayName("the signal-consumed event lands inside the segment, even though the engine "
            + "emits signalConsumed before workflowUnparked")
    void signalConsumedEventIsNestedInTheSegment() {
        // Exactly SignalManager's emission order for a live park→wake.
        observer.workflowParked(WORKFLOW, ParkKind.SIGNAL);
        observer.signalConsumed(APPROVAL, false);
        observer.workflowUnparked(WORKFLOW, ParkKind.SIGNAL);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(1, segments.size(),
                "the unpark must not open a second segment on top of the one signalConsumed opened");
        var events = segments.getFirst().getEvents().stream().map(e -> e.getName()).toList();
        assertTrue(events.contains("maestro.signal.consumed"), () -> "events were " + events);
    }

    @Test
    @DisplayName("signalPersisted records an event only when a segment is already open — "
            + "a delivery thread is not a run segment")
    void signalPersistedNeverOpensASegment() {
        observer.signalPersisted(APPROVAL);

        assertEquals(0, otel.finishedSpans().size());
        assertNull(otel.tracer().currentSpan());

        observer.activityStarted(CHARGE);
        observer.signalPersisted(APPROVAL);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.workflowCompleted(WORKFLOW);

        var events = otel.spansNamed(SEGMENT).getFirst().getEvents().stream()
                .map(e -> e.getName()).toList();
        assertTrue(events.contains("maestro.signal.persisted"), () -> "events were " + events);
    }

    // ── Remote parent restoration (§4.3) ──────────────────────────────

    @Test
    @DisplayName("a signal carrying a durable traceContext gives the resumed segment the remote parent")
    void durableTraceContextRestoresTheRemoteParent() {
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), false);
        observer.workflowUnparked(WORKFLOW, ParkKind.SIGNAL);
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.workflowCompleted(WORKFLOW);

        var segment = otel.spansNamed(SEGMENT).getFirst();
        assertEquals(REMOTE_TRACE_ID, segment.getTraceId(),
                "the resumed segment must join the publisher's trace");
        assertEquals(REMOTE_SPAN_ID, segment.getParentSpanId(),
                "the resumed segment's parent must be the publisher's span");

        var activity = otel.spansNamed(ACTIVITY).getFirst();
        assertEquals(REMOTE_TRACE_ID, activity.getTraceId(),
                "work done after the resume stays in the same trace");
    }

    @Test
    @DisplayName("a recovered run's rootless segment is re-parented when the signal's remote "
            + "context finally arrives")
    void rootlessSegmentIsReparentedOnRemoteContext() {
        // A recovered run: no park happened on this node, so the segment was
        // opened as a root by the first live step before the awaited (already
        // arrived) signal was consumed.
        observer.workflowResumed(WORKFLOW);
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(2, segments.size(),
                "the rootless segment closes and the run continues under the remote trace");
        assertEquals(REMOTE_TRACE_ID, segments.get(1).getTraceId());
        assertEquals(REMOTE_SPAN_ID, segments.get(1).getParentSpanId());
    }

    @Test
    @DisplayName("a segment that already has the remote parent is not re-parented again")
    void alreadyRemotelyParentedSegmentIsNotReopened() {
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), false);
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), false);
        observer.workflowCompleted(WORKFLOW);

        assertEquals(1, otel.spansNamed(SEGMENT).size());
    }

    @Test
    @DisplayName("a null traceContext degrades to a fresh root segment, never an error")
    void nullTraceContextDegradesToRoot() {
        observer.signalConsumed(APPROVAL, false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(1, segments.size());
        assertFalse(segments.getFirst().getParentSpanContext().isValid(),
                "no remote context means a root segment");
    }

    @Test
    @DisplayName("a malformed traceContext degrades to a fresh root segment, never an error")
    void malformedTraceContextDegradesToRoot() {
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", "not-a-traceparent"), false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(1, segments.size());
        assertFalse(segments.getFirst().getParentSpanContext().isValid());
    }

    @Test
    @DisplayName("a remote context wins over the local park chain, and the previous segment "
            + "survives as a span link")
    void remoteParentWinsOverLocalChainAndLinksIt() {
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.workflowParked(WORKFLOW, ParkKind.SIGNAL);
        var first = otel.spansNamed(SEGMENT).getFirst();

        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "approval", TRACEPARENT), false);
        observer.workflowUnparked(WORKFLOW, ParkKind.SIGNAL);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(2, segments.size());
        var resumed = segments.get(1);
        assertEquals(REMOTE_TRACE_ID, resumed.getTraceId(),
                "the cross-service link is the one that must survive");
        assertNotEquals(first.getTraceId(), resumed.getTraceId());
        assertTrue(resumed.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(first.getSpanId())),
                "the local park chain must survive as a link, not be silently dropped");
    }

    @Test
    @DisplayName("RULING 7: a non-root segment is re-parented too — a workflow that already parked "
            + "once, then consumes an already-delivered signal on the no-park fast path")
    void nonRootSegmentIsReparentedOnRemoteContext() {
        // 1. live work, then a park — segment 1 (root) closes.
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.workflowParked(WORKFLOW, ParkKind.SIGNAL);
        // 2. resume on signal S1, which carried no trace context. Segment 2 is
        //    chained to segment 1 and is therefore NOT root.
        observer.signalConsumed(new SignalInfo("order-1", "OrderWorkflow", "s1", null), false);
        observer.workflowUnparked(WORKFLOW, ParkKind.SIGNAL);
        var chained = otel.spansNamed(SEGMENT);
        assertEquals(1, chained.size(), "only segment 1 has closed so far");

        // 3. awaitSignal finds S2 already delivered and consumes it WITHOUT
        //    parking (SignalManager's fast path) — so the open segment is
        //    non-root when the remote context arrives.
        observer.signalConsumed(
                new SignalInfo("order-1", "OrderWorkflow", "s2", TRACEPARENT), false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(3, segments.size());
        var resumed = segments.get(2);
        assertEquals(REMOTE_TRACE_ID, resumed.getTraceId(),
                "a non-root open segment must still join the publisher's trace — restricting "
                        + "re-parenting to rootless segments dropped the remote context entirely");
        assertEquals(REMOTE_SPAN_ID, resumed.getParentSpanId());
        assertTrue(resumed.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(segments.get(1).getSpanId())),
                "the local chain must survive as a link");
    }

    @Test
    @DisplayName("a non-00 traceparent version is treated as absent, matching the pinned wire grammar")
    void nonZeroVersionTraceparentIsTreatedAsAbsent() {
        observer.signalConsumed(new SignalInfo("order-1", "OrderWorkflow", "approval",
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), false);
        observer.workflowCompleted(WORKFLOW);

        var segments = otel.spansNamed(SEGMENT);
        assertEquals(1, segments.size());
        assertFalse(segments.getFirst().getParentSpanContext().isValid(),
                "the adapter's grammar must agree with design §4.1 and the Kafka contract test, "
                        + "which both pin version 00");
    }

    /**
     * Fix round 3. The branch latch was keyed off a sequence number the MAIN
     * line legitimately reaches: after a join,
     * {@code DefaultWorkflowOperations} sets the parent sequence to
     * {@code parentSeq * 1000 + (branchCount + 1) * 1000}, so every main line
     * that follows any {@code parallel()} runs at seq ≥ 2000. The main workflow
     * thread therefore latched as a branch and stopped opening segments for the
     * rest of the run — its later work fell out of the trace entirely.
     *
     * <p>This is the reviewer's probe shape: a workflow that forks, then keeps
     * parking on the main thread with post-join sequence numbers.
     */
    @Test
    @DisplayName("a main thread that forks keeps its segments across the join and later parks — "
            + "post-join sequence numbers must not classify it as a branch")
    void mainThreadKeepsSegmentsAcrossJoinAndLaterParks() {
        // Pre-fork main-line work.
        observer.activityStarted(CHARGE);
        observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
        observer.workflowParked(WORKFLOW, ParkKind.TIMER);
        observer.workflowUnparked(WORKFLOW, ParkKind.TIMER);

        // Post-join main-line work: parallel() advanced the main sequence past
        // the branch spaces, so these are ordinary main-thread activities that
        // merely happen to sit at a high sequence number.
        var postJoin = new ActivityInfo("order-1", "OrderWorkflow", "payment.settle", 4001);
        observer.activityStarted(postJoin);
        observer.activityCompleted(postJoin, Duration.ofMillis(1), false);
        observer.workflowParked(WORKFLOW, ParkKind.TIMER);
        observer.workflowUnparked(WORKFLOW, ParkKind.TIMER);

        var last = new ActivityInfo("order-1", "OrderWorkflow", "payment.confirm", 4002);
        observer.activityStarted(last);
        observer.activityCompleted(last, Duration.ofMillis(1), false);
        observer.workflowCompleted(WORKFLOW);

        var traceIds = otel.finishedSpans().stream().map(sp -> sp.getTraceId()).distinct().toList();
        assertEquals(1, traceIds.size(),
                () -> "the whole run must stay in one trace; a main thread misclassified as a "
                        + "branch stops opening segments and its later work starts a new trace. "
                        + "Trace ids were " + traceIds);

        var detachedActivities = otel.spansNamed(ACTIVITY).stream()
                .filter(sp -> !sp.getParentSpanContext().isValid())
                .toList();
        assertTrue(detachedActivities.isEmpty(),
                () -> "no main-line activity may export as a detached root; detached: "
                        + detachedActivities.stream()
                        .map(sp -> OtelTracingFixture.attribute(sp, "maestro.activity.name")).toList());

        assertEquals(3, otel.spansNamed(SEGMENT).size(),
                "one run segment per live stretch between parks");
    }

    // ── Run abandonment (design §11, RULING 5) ────────────────────────

    @Test
    @DisplayName("runAbandoned closes the open segment and clears the thread, for every reason")
    void runAbandonedClosesTheSegment() {
        for (var reason : AbandonReason.values()) {
            otel.reset();
            observer.activityStarted(CHARGE);
            observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);

            observer.runAbandoned(WORKFLOW, reason);

            assertNull(otel.tracer().currentSpan(),
                    () -> "no scope may survive runAbandoned(" + reason + ")");
            assertEquals(1, otel.spansNamed(SEGMENT).size(),
                    () -> "the segment must be exported after runAbandoned(" + reason + ") — "
                            + "before RULING 5 nothing closed it and it was never exported at all");
            assertEquals(reason.name(),
                    OtelTracingFixture.attribute(otel.spansNamed(SEGMENT).getFirst(),
                            "maestro.abandon.reason"));

            // The thread must be clean: the next run starts its own trace.
            observer.activityStarted(CHARGE);
            observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
            observer.workflowCompleted(WORKFLOW);
            var segments = otel.spansNamed(SEGMENT);
            assertEquals(2, segments.size());
            assertNotEquals(segments.get(0).getTraceId(), segments.get(1).getTraceId());
        }
    }

    // ── Terminal callbacks clear the thread's state ───────────────────

    @Test
    @DisplayName("every terminal callback closes the open segment and clears the thread")
    void terminalCallbacksCloseAndClear() {
        for (Runnable terminal : new Runnable[]{
                () -> observer.workflowCompleted(WORKFLOW),
                () -> observer.workflowFailed(WORKFLOW, "java.lang.IllegalStateException"),
                () -> observer.workflowTerminated(WORKFLOW),
                () -> observer.standDown(StandDownReason.STALE_RUN, "order-1", "collision at 4")}) {
            otel.reset();
            observer.activityStarted(CHARGE);
            observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
            terminal.run();

            assertNull(otel.tracer().currentSpan(), "no scope may survive a terminal callback");
            assertEquals(1, otel.spansNamed(SEGMENT).size());

            // The next run on this thread must be a fresh trace, not a child.
            observer.activityStarted(CHARGE);
            observer.activityCompleted(CHARGE, Duration.ofMillis(1), false);
            observer.workflowCompleted(WORKFLOW);
            var segments = otel.spansNamed(SEGMENT);
            assertEquals(2, segments.size());
            assertNotEquals(segments.get(0).getTraceId(), segments.get(1).getTraceId(),
                    "a terminal callback must not leave the previous segment as a parent");
        }
    }
}
