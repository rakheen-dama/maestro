package io.b2mash.maestro.core.observe;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CompositeEngineObserver}: collapsing rules of {@code of(List)},
 * ordered fan-out across every callback, per-delegate
 * {@link RuntimeException} containment, and {@link Error} propagation (the
 * engine's control-flow signals must never be swallowed).
 */
@DisplayName("CompositeEngineObserver semantics")
class CompositeEngineObserverTest {

    private static final WorkflowInfo WF = new WorkflowInfo("wf-1", "TestWorkflow", "svc");
    private static final ActivityInfo ACT = new ActivityInfo("wf-1", "TestWorkflow", "Grp.step", 1);
    private static final SignalInfo SIG = new SignalInfo("wf-1", "TestWorkflow", "go", null);
    private static final TimerInfo TIMER = new TimerInfo("wf-1", "TestWorkflow", "sleep-1");

    // ── of(List) collapsing ───────────────────────────────────────────

    @Test
    @DisplayName("of(empty) collapses to NOOP")
    void ofEmptyIsNoop() {
        assertSame(EngineObserver.NOOP, CompositeEngineObserver.of(List.of()));
    }

    @Test
    @DisplayName("of(single) returns the sole delegate itself")
    void ofSingleReturnsDelegate() {
        var only = new RecordingEngineObserver();
        assertSame(only, CompositeEngineObserver.of(List.of(only)));
    }

    @Test
    @DisplayName("of(multiple) builds a composite")
    void ofMultipleBuildsComposite() {
        var composite = CompositeEngineObserver.of(
                List.of(new RecordingEngineObserver(), new RecordingEngineObserver()));
        assertTrue(composite instanceof CompositeEngineObserver,
                "two delegates must produce a composite, got " + composite.getClass());
    }

    // ── Fan-out ───────────────────────────────────────────────────────

    @Test
    @DisplayName("every callback fans out to every delegate")
    void everyCallbackFansOut() {
        var first = new RecordingEngineObserver();
        var second = new RecordingEngineObserver();
        var composite = CompositeEngineObserver.of(List.of(first, second));

        invokeEveryCallback(composite);

        for (var recorder : List.of(first, second)) {
            assertEquals(1, recorder.started().size(), "workflowStarted");
            assertEquals(1, recorder.resumed().size(), "workflowResumed");
            assertEquals(1, recorder.completed().size(), "workflowCompleted");
            assertEquals(1, recorder.failed().size(), "workflowFailed");
            assertEquals(1, recorder.compensating().size(), "workflowCompensating");
            assertEquals(1, recorder.terminated().size(), "workflowTerminated");
            assertEquals(1, recorder.parked().size(), "workflowParked");
            assertEquals(1, recorder.unparked().size(), "workflowUnparked");
            assertEquals(1, recorder.activityStarted().size(), "activityStarted");
            assertEquals(1, recorder.activityCompleted().size(), "activityCompleted");
            assertEquals(1, recorder.activityFailed().size(), "activityFailed");
            assertEquals(1, recorder.signalPersisted().size(), "signalPersisted");
            assertEquals(1, recorder.signalConsumed().size(), "signalConsumed");
            assertEquals(1, recorder.timerScheduled().size(), "timerScheduled");
            assertEquals(1, recorder.timerFired().size(), "timerFired");
            assertEquals(1, recorder.timerCancelled().size(), "timerCancelled");
            assertEquals(1, recorder.lockAcquired().size(), "instanceLockAcquired");
            assertEquals(1, recorder.lockRenewFailed().size(), "instanceLockRenewFailed");
            assertEquals(1, recorder.lockLost().size(), "instanceLockLost");
            assertEquals(1, recorder.recoveryPasses().size(), "recoveryPass");
            assertEquals(1, recorder.standDowns().size(), "standDown");
        }
    }

    @Test
    @DisplayName("delegates are invoked in list order")
    void delegatesInvokedInOrder() {
        var order = new CopyOnWriteArrayList<String>();
        EngineObserver first = new EngineObserver() {
            @Override public void workflowStarted(WorkflowInfo w) { order.add("first"); }
        };
        EngineObserver second = new EngineObserver() {
            @Override public void workflowStarted(WorkflowInfo w) { order.add("second"); }
        };

        CompositeEngineObserver.of(List.of(first, second)).workflowStarted(WF);

        assertEquals(List.of("first", "second"), order);
    }

    // ── Containment ───────────────────────────────────────────────────

    @Test
    @DisplayName("a delegate throwing RuntimeException is contained; later delegates still run")
    void runtimeExceptionContained() {
        EngineObserver misbehaving = new EngineObserver() {
            @Override public void workflowStarted(WorkflowInfo w) {
                throw new IllegalStateException("observer bug");
            }
            @Override public void activityCompleted(ActivityInfo a, Duration d, boolean r) {
                throw new IllegalStateException("observer bug");
            }
        };
        var after = new RecordingEngineObserver();
        var composite = CompositeEngineObserver.of(List.of(misbehaving, after));

        composite.workflowStarted(WF);
        composite.activityCompleted(ACT, Duration.ofMillis(5), false);

        assertEquals(1, after.started().size(),
                "a RuntimeException from an earlier delegate must not starve later delegates");
        assertEquals(1, after.activityCompleted().size(),
                "containment must apply per callback, not just the first one");
    }

    @Test
    @DisplayName("an Error (engine control-flow signal) propagates and is never swallowed")
    void errorPropagates() {
        EngineObserver hostile = new EngineObserver() {
            @Override public void workflowStarted(WorkflowInfo w) {
                throw new ExecutorShutdownException("must not be swallowed");
            }
        };
        var composite = CompositeEngineObserver.of(List.of(hostile, new RecordingEngineObserver()));

        assertThrows(ExecutorShutdownException.class, () -> composite.workflowStarted(WF),
                "Errors — including the engine's control-flow signals — must propagate "
                        + "through the composite, never be contained");
    }

    // ── No-op default ─────────────────────────────────────────────────

    @Test
    @DisplayName("NOOP and a bare `new EngineObserver() {}` accept every callback without effect")
    void noopAcceptsEverything() {
        invokeEveryCallback(EngineObserver.NOOP);
        invokeEveryCallback(new EngineObserver() {});
        // Reaching here without an exception is the assertion: all interface
        // methods are default no-ops, so the interface is its own null object.
    }

    private static void invokeEveryCallback(EngineObserver o) {
        o.workflowStarted(WF);
        o.workflowResumed(WF);
        o.workflowCompleted(WF);
        o.workflowFailed(WF, "java.lang.IllegalStateException");
        o.workflowCompensating(WF);
        o.workflowTerminated(WF);
        o.workflowParked(WF, ParkKind.SIGNAL);
        o.workflowUnparked(WF, ParkKind.SIGNAL);
        o.activityStarted(ACT);
        o.activityCompleted(ACT, Duration.ofMillis(3), false);
        o.activityFailed(ACT, Duration.ofMillis(3), "java.lang.RuntimeException", false);
        o.signalPersisted(SIG);
        o.signalConsumed(SIG, false);
        o.timerScheduled(TIMER, false);
        o.timerFired(TIMER, false);
        o.timerCancelled(TIMER, false);
        o.instanceLockAcquired("wf-1");
        o.instanceLockRenewFailed("wf-1");
        o.instanceLockLost("wf-1");
        o.recoveryPass(3, 2);
        o.standDown(StandDownReason.STALE_RUN, "wf-1", "seq 7");
    }
}
