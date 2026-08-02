package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.ParkKind;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every {@code EngineObserver} emission site the design doc §1.3 assigns
 * to the engine: workflow lifecycle, activities (via the proxy), signals,
 * workflow-side timer observation, saga compensation, recovery passes and the
 * stale-run stand-down. Replay-flag semantics are pinned separately in
 * {@link ObserverReplayNoDoubleCountTest}.
 */
@DisplayName("WorkflowExecutor wires EngineObserver emissions at every design §1 site")
class WorkflowExecutorObserverTest {

    private static final String SERVICE = "test-service";

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private final ActivityProxyFactory proxyFactory = new ActivityProxyFactory();
    private final RetryExecutor retryExecutor = new RetryExecutor();

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observer = new RecordingEngineObserver();
        executor = newExecutor(observer);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private WorkflowExecutor newExecutor(EngineObserver obs) {
        return new WorkflowExecutor(store, null, null, null, serializer, SERVICE,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(200), obs);
    }

    private StepActivities proxy(EngineObserver obs) {
        return proxyFactory.createProxy(StepActivities.class, new StepActivitiesImpl(),
                store, null, null, RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, obs);
    }

    // ── Workflow lifecycle + activities ───────────────────────────────

    @Test
    @DisplayName("live run: workflowStarted, activityStarted/Completed(replayed=false), workflowCompleted")
    void liveRunEmitsLifecycleAndActivityCallbacks() throws Exception {
        var done = new CountDownLatch(1);
        var workflow = new TwoStepWorkflow(proxy(observer), done);
        var method = TwoStepWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-live", "TwoStepWorkflow", "default", "in", workflow, method);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() -> !executor.isRunning("obs-live"));

        assertEquals(1, observer.started().size(), "workflowStarted must fire exactly once");
        var info = observer.started().getFirst();
        assertEquals("obs-live", info.workflowId());
        assertEquals("TwoStepWorkflow", info.workflowType());
        assertEquals(SERVICE, info.serviceName());

        assertEquals(0, observer.resumed().size(), "a fresh start is not a resume");
        assertEquals(2, observer.activityStarted().size(),
                "both live activities must emit activityStarted");
        assertEquals(2, observer.activityCompleted().size());
        assertTrue(observer.activityCompleted().stream().noneMatch(
                        RecordingEngineObserver.ActivityCompletion::replayed),
                "a live execution must carry replayed=false");
        assertEquals(1, observer.completionsOf("StepActivities.step1", false).size());
        assertEquals(1, observer.completionsOf("StepActivities.step2", false).size());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, observer.completed().size(),
                        "workflowCompleted must fire exactly once from the winning terminal transition"));
        assertEquals(0, observer.failed().size());
    }

    @Test
    @DisplayName("failed run: workflowFailed carries the exception type")
    void failedRunEmitsWorkflowFailedWithExceptionType() throws Exception {
        var workflow = new ThrowingWorkflow();
        var method = ThrowingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-fail", "ThrowingWorkflow", "default", "in", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, observer.failed().size(), "workflowFailed must fire exactly once"));
        assertEquals("java.lang.IllegalStateException",
                observer.failed().getFirst().exceptionType());
        assertEquals(0, observer.completed().size());
    }

    @Test
    @DisplayName("converged loser: another runner finalised first — no workflowCompleted emitted")
    void convergedLoserEmitsNoTerminalCallback() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workflow = new GatedWorkflow(started, release);
        var method = GatedWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-loser", "GatedWorkflow", "default", "in", workflow, method);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // Another runner finalised the instance while this node's run was mid-flight
        store.forceStatus("obs-loser", WorkflowStatus.COMPLETED);
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("obs-loser"));

        assertEquals(0, observer.completed().size(),
                "a converged loser must not double-fire workflowCompleted — only the "
                        + "runner that won transitionToTerminal counts the outcome");
        assertEquals(0, observer.failed().size());
    }

    @Test
    @DisplayName("terminate: workflowTerminated fires once from the winning CAS, never on ALREADY_TERMINAL")
    void terminateEmitsOnce() throws Exception {
        var waiting = new CountDownLatch(1);
        var workflow = new AwaitingWorkflow(waiting);
        var method = AwaitingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-term", "AwaitingWorkflow", "default", "in", workflow, method);
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("obs-term").map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
                executor.terminateWorkflow("obs-term", "test"));
        assertEquals(WorkflowExecutor.TerminateOutcome.ALREADY_TERMINAL,
                executor.terminateWorkflow("obs-term", "again"));

        assertEquals(1, observer.terminated().size(),
                "only the winning terminate CAS may emit workflowTerminated");
        assertEquals("obs-term", observer.terminated().getFirst().workflowId());
    }

    @Test
    @DisplayName("stale run: DuplicateEventException at top level emits standDown(STALE_RUN)")
    void staleRunEmitsStandDown() throws Exception {
        var workflow = new StaleRunWorkflow();
        var method = StaleRunWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-stale", "StaleRunWorkflow", "default", "in", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, observer.standDowns().size(), "standDown must fire exactly once"));
        var standDown = observer.standDowns().getFirst();
        assertEquals(StandDownReason.STALE_RUN, standDown.reason());
        assertEquals("obs-stale", standDown.workflowId());
        assertNotNull(standDown.detail(), "the colliding sequence should be carried as detail");
        assertEquals(0, observer.failed().size(),
                "a stand-down is not a workflow failure and must not be counted as one");
    }

    // ── Terminal-outcome emission vs. the event append ────────────────

    @Test
    @DisplayName("terminal append collision: a run that durably completed still reports completion, never a stand-down")
    void completedIsReportedEvenWhenTheTerminalAppendCollides() throws Exception {
        var done = new CountDownLatch(1);
        var workflow = new TwoStepWorkflow(proxy(observer), done);
        var method = TwoStepWorkflow.class.getMethod("run", String.class);
        // A concurrent runner already owns the sequence this run's
        // WORKFLOW_COMPLETED would take
        store.collideOnEventType(EventType.WORKFLOW_COMPLETED);

        executor.startWorkflow("obs-collide-done", "TwoStepWorkflow", "default", "in", workflow, method);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("obs-collide-done"));

        // The instance row is durably COMPLETED — this run won
        // transitionToTerminal — so nothing after that win may decide whether
        // the completion is observed.
        assertEquals(WorkflowStatus.COMPLETED,
                store.getInstance("obs-collide-done").orElseThrow().status());
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, observer.completed().size(),
                        "a durably completed run must report workflowCompleted"));
        assertEquals(0, observer.standDowns().size(),
                "a completion must never be counted as a stand-down");
    }

    @Test
    @DisplayName("terminal append collision: a run that durably failed still reports the failure")
    void failedIsReportedEvenWhenTheTerminalAppendCollides() throws Exception {
        var workflow = new ThrowingWorkflow();
        var method = ThrowingWorkflow.class.getMethod("run", String.class);
        store.collideOnEventType(EventType.WORKFLOW_FAILED);

        executor.startWorkflow("obs-collide-fail", "ThrowingWorkflow", "default", "in", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, observer.failed().size(),
                        "a durably failed run must report workflowFailed"));
        assertEquals(WorkflowStatus.FAILED,
                store.getInstance("obs-collide-fail").orElseThrow().status());
        assertEquals(0, observer.completed().size());
    }

    @Test
    @DisplayName("compensation-start append collision: the compensation phase is still reported, not only the stand-down")
    void compensatingIsReportedEvenWhenTheCompensationStartAppendCollides() throws Exception {
        var workflow = new CompensatingWorkflow();
        var method = CompensatingWorkflow.class.getMethod("run", String.class);
        // SagaManager.appendEvent deliberately RETHROWS DuplicateEventException
        // (unlike the executor's), so this collision stands the run down — the
        // phase still started on this node and must still be observed.
        store.collideOnEventType(EventType.COMPENSATION_STARTED);

        executor.startWorkflow("obs-collide-comp", "CompensatingWorkflow", "default", "in", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, observer.standDowns().size(),
                        "the colliding append must stand the run down"));
        assertEquals(1, observer.compensating().size(),
                "a compensation phase that started must be observed even when its "
                        + "COMPENSATION_STARTED append loses to a concurrent runner");
        assertEquals(0, observer.failed().size(),
                "a stand-down is not a workflow failure");
    }

    // ── Ruling 4: structural containment at un-hardened emission sites ──

    @Test
    @DisplayName("one registered throwing observer cannot corrupt engine control flow at un-hardened sites")
    void loneThrowingObserverIsContainedAtActivityAndSignalSites() throws Exception {
        var throwing = new AlwaysThrowingObserver();
        // EXACTLY ONE registered observer — the common deployment (a lone
        // Micrometer adapter in Task 4, a lone tracing adapter in Task 5), and
        // the case CompositeEngineObserver.of used to collapse to the bare
        // delegate, leaving zero containment.
        var registered = CompositeEngineObserver.of(List.of(throwing));

        executor.shutdown();
        executor = newExecutor(registered);
        var workflow = new ActivityThenSignalWorkflow(proxy(registered));
        var method = ActivityThenSignalWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-hostile", "ActivityThenSignalWorkflow", "default", "in",
                workflow, method);
        await().atMost(Duration.ofSeconds(5)).until(() -> throwing.calls.contains("workflowParked"));
        executor.deliverSignal("obs-hostile", "go", "payload");

        // ActivityInvocationHandler and SignalManager emit RAW — they were not
        // hand-hardened. Containment here is structural, from the wrapper.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("obs-hostile").orElseThrow().status(),
                        "a throwing observer must not be able to fail a workflow"));
        assertTrue(throwing.calls.containsAll(List.of(
                        "activityStarted", "activityCompleted", "signalPersisted",
                        "signalConsumed", "workflowParked", "workflowUnparked")),
                "every un-hardened site must still have been reached (and contained); got "
                        + throwing.calls);
    }

    // ── Signals ───────────────────────────────────────────────────────

    @Test
    @DisplayName("signal flow: parked/unparked(SIGNAL) around the live park; signalPersisted + signalConsumed(replayed=false)")
    void signalFlowEmitsParkAndConsume() throws Exception {
        var waiting = new CountDownLatch(1);
        var workflow = new AwaitingWorkflow(waiting);
        var method = AwaitingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-signal", "AwaitingWorkflow", "default", "in", workflow, method);
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, observer.parked().size(), "the live park must emit workflowParked"));
        assertEquals(ParkKind.SIGNAL, observer.parked().getFirst().kind());

        executor.deliverSignal("obs-signal", "go", "payload");
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("obs-signal"));

        assertEquals(1, observer.signalPersisted().size(), "delivery must emit signalPersisted");
        assertEquals("go", observer.signalPersisted().getFirst().signalName());
        assertEquals("AwaitingWorkflow", observer.signalPersisted().getFirst().workflowType());

        assertEquals(1, observer.signalConsumed().size());
        var consumption = observer.signalConsumed().getFirst();
        assertEquals("go", consumption.info().signalName());
        assertEquals(false, consumption.replayed(), "a live consume must carry replayed=false");

        assertEquals(1, observer.unparked().size(), "the live wake must emit workflowUnparked");
        assertEquals(ParkKind.SIGNAL, observer.unparked().getFirst().kind());
    }

    // ── Timers (workflow-side observation; the poller is not instrumented) ──

    @Test
    @DisplayName("timer flow: timerScheduled/timerFired(replayed=false) + parked/unparked(TIMER)")
    void timerFlowEmitsScheduleAndFire() throws Exception {
        var done = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMillis(100), done);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        executor.startTimerPoller(Duration.ofMillis(50), 10);
        executor.startWorkflow("obs-timer", "SleepingWorkflow", "default", "in", workflow, method);

        assertTrue(done.await(10, TimeUnit.SECONDS), "sleep should complete via the timer poller");
        await().atMost(Duration.ofSeconds(2)).until(() -> !executor.isRunning("obs-timer"));

        assertEquals(1, observer.timerScheduled().size());
        var scheduled = observer.timerScheduled().getFirst();
        assertEquals(false, scheduled.replayed());
        assertEquals("obs-timer", scheduled.info().workflowId());
        assertEquals("SleepingWorkflow", scheduled.info().workflowType());

        assertEquals(1, observer.timerFired().size(),
                "the workflow-side wake is the single place a fire is observed");
        assertEquals(false, observer.timerFired().getFirst().replayed());
        assertEquals(0, observer.timerCancelled().size());

        assertEquals(1, observer.parked().stream()
                .filter(p -> p.kind() == ParkKind.TIMER).count(), "live sleep must park as TIMER");
        assertEquals(1, observer.unparked().stream()
                .filter(p -> p.kind() == ParkKind.TIMER).count());
    }

    // ── Saga compensation ─────────────────────────────────────────────

    @Test
    @DisplayName("saga: workflowCompensating fires once when a live compensation phase starts")
    void compensationEmitsWorkflowCompensating() throws Exception {
        var workflow = new CompensatingWorkflow();
        var method = CompensatingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("obs-saga", "CompensatingWorkflow", "default", "in", workflow, method);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, observer.compensating().size(),
                        "a live compensation phase must emit workflowCompensating exactly once"));
        assertEquals("obs-saga", observer.compensating().getFirst().workflowId());
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, observer.failed().size()));
    }

    // ── Recovery ──────────────────────────────────────────────────────

    @Test
    @DisplayName("recoverWorkflows emits recoveryPass(scanned, adopted) even when nothing is adoptable")
    void recoveryPassEmitted() {
        var count = executor.recoverWorkflows(Map.of());

        assertEquals(0, count);
        assertEquals(1, observer.recoveryPasses().size(),
                "every recovery pass must be observed, adopted or not");
        assertEquals(new RecordingEngineObserver.RecoveryPassCall(0, 0),
                observer.recoveryPasses().getFirst());
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    /** Simple activity group executed through the memoizing proxy. */
    public interface StepActivities {
        String step1();

        String step2();

        String step3();
    }

    public static class StepActivitiesImpl implements StepActivities {
        @Override public String step1() { return "one"; }

        @Override public String step2() { return "two"; }

        @Override public String step3() { return "three"; }
    }

    public static class TwoStepWorkflow {
        private final StepActivities activities;
        private final CountDownLatch done;

        public TwoStepWorkflow(StepActivities activities, CountDownLatch done) {
            this.activities = activities;
            this.done = done;
        }

        public String run(String input) {
            var a = activities.step1();
            var b = activities.step2();
            done.countDown();
            return a + b;
        }
    }

    public static class ThrowingWorkflow {
        public String run(String input) {
            throw new IllegalStateException("intentional failure");
        }
    }

    public static class GatedWorkflow {
        private final CountDownLatch started;
        private final CountDownLatch release;

        public GatedWorkflow(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        public String run(String input) throws InterruptedException {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "done";
        }
    }

    public static class AwaitingWorkflow {
        private final CountDownLatch waiting;

        public AwaitingWorkflow(CountDownLatch waiting) {
            this.waiting = waiting;
        }

        public String run(String input) {
            waiting.countDown();
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
        }
    }

    public static class SleepingWorkflow {
        private final Duration nap;
        private final CountDownLatch done;

        public SleepingWorkflow(Duration nap, CountDownLatch done) {
            this.nap = nap;
            this.done = done;
        }

        public String run(String input) {
            WorkflowContext.current().sleep(nap);
            done.countDown();
            return "woke";
        }
    }

    /** Simulates the Issue 18 stale-run collision escaping to the executor. */
    public static class StaleRunWorkflow {
        public String run(String input) {
            throw new DuplicateEventException(UUID.randomUUID(), 7);
        }
    }

    /** Exercises the activity proxy and the signal park in one run. */
    public static class ActivityThenSignalWorkflow {
        private final StepActivities activities;

        public ActivityThenSignalWorkflow(StepActivities activities) {
            this.activities = activities;
        }

        public String run(String input) {
            var a = activities.step1();
            var signal = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
            return a + signal + activities.step2();
        }
    }

    /** An adapter that throws from every callback the engine reaches. */
    private static final class AlwaysThrowingObserver implements EngineObserver {

        final List<String> calls = new CopyOnWriteArrayList<>();

        private void blowUp(String callback) {
            calls.add(callback);
            throw new IllegalStateException("adapter blew up in " + callback);
        }

        @Override public void workflowStarted(WorkflowInfo w) { blowUp("workflowStarted"); }

        @Override public void workflowCompleted(WorkflowInfo w) { blowUp("workflowCompleted"); }

        @Override public void workflowParked(WorkflowInfo w, ParkKind k) { blowUp("workflowParked"); }

        @Override public void workflowUnparked(WorkflowInfo w, ParkKind k) { blowUp("workflowUnparked"); }

        @Override public void activityStarted(ActivityInfo a) { blowUp("activityStarted"); }

        @Override public void activityCompleted(ActivityInfo a, Duration d, boolean replayed) {
            blowUp("activityCompleted");
        }

        @Override public void signalPersisted(SignalInfo s) { blowUp("signalPersisted"); }

        @Override public void signalConsumed(SignalInfo s, boolean replayed) { blowUp("signalConsumed"); }

        @Override public void instanceLockAcquired(String workflowId) { blowUp("instanceLockAcquired"); }
    }

    public static class CompensatingWorkflow {
        public String run(String input) {
            WorkflowContext.current().addCompensation("undo", () -> { });
            throw new IllegalStateException("fail after registering compensation");
        }
    }
}
