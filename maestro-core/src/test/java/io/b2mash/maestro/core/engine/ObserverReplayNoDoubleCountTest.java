package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay-no-double-count pin (design doc §8.1, core half): a workflow
 * with three activities runs two of them, "crashes" (executor shutdown while
 * parked — the same recoverable state a JVM kill leaves), and is recovered by
 * a second executor over the same store. Observer callbacks must be
 * exactly-once per <em>logical</em> event across both runs, with
 * {@code replayed=true} on the replayed pair — an adapter that skips
 * {@code replayed} callbacks therefore counts each logical event once.
 */
@DisplayName("Replay emits observer callbacks flagged replayed=true — never double-counted")
class ObserverReplayNoDoubleCountTest {

    private static final String SERVICE = "test-service";

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private final ActivityProxyFactory proxyFactory = new ActivityProxyFactory();
    private final RetryExecutor retryExecutor = new RetryExecutor();
    private WorkflowExecutor executorA;
    private WorkflowExecutor executorB;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        if (executorA != null) executorA.shutdown();
        if (executorB != null) executorB.shutdown();
    }

    private WorkflowExecutor newExecutor(RecordingEngineObserver obs) {
        return new WorkflowExecutor(store, null, null, null, serializer, SERVICE,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(200), obs);
    }

    private WorkflowExecutorObserverTest.StepActivities proxy(RecordingEngineObserver obs) {
        return proxyFactory.createProxy(
                WorkflowExecutorObserverTest.StepActivities.class,
                new WorkflowExecutorObserverTest.StepActivitiesImpl(),
                store, null, null, RetryPolicy.noRetry(), Duration.ofSeconds(30),
                serializer, retryExecutor,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, obs);
    }

    // ── The pin ───────────────────────────────────────────────────────

    @Test
    @DisplayName("3 activities, crash after 2, recover: exactly-once live callbacks; replayed=true on the replayed pair")
    void replayedActivitiesAreFlaggedAndNeverDoubleCounted() throws Exception {
        var observerA = new RecordingEngineObserver();
        executorA = newExecutor(observerA);

        var parked = new CountDownLatch(1);
        var workflowA = new ThreeStepGatedWorkflow(proxy(observerA), parked);
        var method = ThreeStepGatedWorkflow.class.getMethod("run", String.class);

        executorA.startWorkflow("replay-pin", "ThreeStepGatedWorkflow", "default",
                "in", workflowA, method);
        assertTrue(parked.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("replay-pin")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

        // "Crash": the node stops while the workflow is parked. Durable state
        // stays WAITING_SIGNAL — exactly what recovery needs to find.
        executorA.shutdown();
        await().atMost(Duration.ofSeconds(5)).until(() -> !executorA.isRunning("replay-pin"));

        // Run A saw only the live prefix
        assertEquals(1, observerA.started().size());
        assertEquals(2, observerA.activityStarted().size());
        assertEquals(2, observerA.activityCompleted().size());
        assertTrue(observerA.activityCompleted().stream().noneMatch(
                RecordingEngineObserver.ActivityCompletion::replayed));
        assertEquals(0, observerA.completed().size());
        assertEquals(0, observerA.failed().size(),
                "a shutdown while parked is not a workflow failure");

        // ── Recovery on a second "node" over the same store ──
        var observerB = new RecordingEngineObserver();
        executorB = newExecutor(observerB);

        // The awaited signal arrived while the service was down
        executorB.deliverSignal("replay-pin", "resume", "go");

        var workflowB = new ThreeStepGatedWorkflow(proxy(observerB), new CountDownLatch(1));
        var recovered = executorB.recoverWorkflows(Map.of("ThreeStepGatedWorkflow",
                new WorkflowRegistration("ThreeStepGatedWorkflow", "default", workflowB, method)));
        assertEquals(1, recovered);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                WorkflowStatus.COMPLETED,
                store.getInstance("replay-pin").orElseThrow().status()));
        await().atMost(Duration.ofSeconds(2)).until(() -> !executorB.isRunning("replay-pin"));

        // Run B: resumed (not started), replayed the pair, ran step3 live
        assertEquals(0, observerB.started().size(),
                "recovery is a resume, never a second workflowStarted");
        assertEquals(1, observerB.resumed().size());
        assertEquals(new RecordingEngineObserver.RecoveryPassCall(1, 1),
                observerB.recoveryPasses().getFirst());

        assertEquals(1, observerB.activityStarted().size(),
                "activityStarted must never fire for a replayed step");
        assertEquals("StepActivities.step3",
                observerB.activityStarted().getFirst().activityName());

        assertEquals(1, observerB.completionsOf("StepActivities.step1", true).size(),
                "replayed step1 must be emitted with replayed=true");
        assertEquals(1, observerB.completionsOf("StepActivities.step2", true).size(),
                "replayed step2 must be emitted with replayed=true");
        assertTrue(observerB.completionsOf("StepActivities.step1", true).getFirst()
                        .duration().isZero(),
                "a replayed completion carries Duration.ZERO");
        assertEquals(1, observerB.completionsOf("StepActivities.step3", false).size());
        assertEquals(1, observerB.completed().size());

        // ── The exactly-once ledger across both runs ──
        for (var step : new String[]{"StepActivities.step1", "StepActivities.step2",
                "StepActivities.step3"}) {
            var liveCount = observerA.completionsOf(step, false).size()
                    + observerB.completionsOf(step, false).size();
            assertEquals(1, liveCount,
                    "each logical activity completion must be observed live exactly once "
                            + "across the crash-recovery boundary: " + step);
        }
        assertEquals(1, observerA.started().size() + observerB.started().size());
        assertEquals(1, observerA.completed().size() + observerB.completed().size());
        assertEquals(1, observerA.signalConsumed().size() + observerB.signalConsumed().size(),
                "the signal is consumed live exactly once (on the recovering node)");
        assertTrue(observerB.signalConsumed().stream().noneMatch(
                RecordingEngineObserver.SignalConsumption::replayed));
    }

    @Test
    @DisplayName("a memoized signal consumption replays with replayed=true")
    void replayedSignalConsumptionIsFlagged() throws Exception {
        var observerA = new RecordingEngineObserver();
        executorA = newExecutor(observerA);

        var firstConsumed = new CountDownLatch(1);
        var workflowA = new TwoSignalWorkflow(firstConsumed);
        var method = TwoSignalWorkflow.class.getMethod("run", String.class);

        executorA.startWorkflow("replay-signal", "TwoSignalWorkflow", "default",
                "in", workflowA, method);
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("replay-signal")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));
        executorA.deliverSignal("replay-signal", "first", "one");
        assertTrue(firstConsumed.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("replay-signal")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false)
                        && observerA.parked().size() == 2);
        executorA.shutdown();

        assertEquals(1, observerA.signalConsumed().size());
        assertEquals(false, observerA.signalConsumed().getFirst().replayed());

        var observerB = new RecordingEngineObserver();
        executorB = newExecutor(observerB);
        var workflowB = new TwoSignalWorkflow(new CountDownLatch(1));
        executorB.deliverSignal("replay-signal", "second", "two");
        var recovered = executorB.recoverWorkflows(Map.of("TwoSignalWorkflow",
                new WorkflowRegistration("TwoSignalWorkflow", "default", workflowB, method)));
        assertEquals(1, recovered);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                WorkflowStatus.COMPLETED,
                store.getInstance("replay-signal").orElseThrow().status()));

        var replayed = observerB.signalConsumed().stream()
                .filter(RecordingEngineObserver.SignalConsumption::replayed).toList();
        assertEquals(1, replayed.size(),
                "the memoized SIGNAL_RECEIVED must replay with replayed=true");
        assertEquals("first", replayed.getFirst().info().signalName());
        var live = observerB.signalConsumed().stream()
                .filter(c -> !c.replayed()).toList();
        assertEquals(1, live.size());
        assertEquals("second", live.getFirst().info().signalName());
    }

    @Test
    @DisplayName("a memoized sleep replays timerScheduled/timerFired with replayed=true")
    void replayedTimerIsFlagged() throws Exception {
        var observerA = new RecordingEngineObserver();
        executorA = newExecutor(observerA);
        executorA.startTimerPoller(Duration.ofMillis(50), 10);

        var slept = new CountDownLatch(1);
        var workflowA = new SleepThenAwaitWorkflow(slept);
        var method = SleepThenAwaitWorkflow.class.getMethod("run", String.class);

        executorA.startWorkflow("replay-timer", "SleepThenAwaitWorkflow", "default",
                "in", workflowA, method);
        assertTrue(slept.await(10, TimeUnit.SECONDS), "the live sleep should fire via the poller");
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("replay-timer")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));
        executorA.shutdown();

        assertEquals(1, observerA.timerScheduled().size());
        assertEquals(false, observerA.timerScheduled().getFirst().replayed());
        assertEquals(1, observerA.timerFired().size());
        assertEquals(false, observerA.timerFired().getFirst().replayed());

        var observerB = new RecordingEngineObserver();
        executorB = newExecutor(observerB);
        var workflowB = new SleepThenAwaitWorkflow(new CountDownLatch(1));
        executorB.deliverSignal("replay-timer", "after-sleep", "done");
        var recovered = executorB.recoverWorkflows(Map.of("SleepThenAwaitWorkflow",
                new WorkflowRegistration("SleepThenAwaitWorkflow", "default", workflowB, method)));
        assertEquals(1, recovered);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                WorkflowStatus.COMPLETED,
                store.getInstance("replay-timer").orElseThrow().status()));

        assertEquals(1, observerB.timerScheduled().size(),
                "the memoized TIMER_SCHEDULED must replay through the observer");
        assertEquals(true, observerB.timerScheduled().getFirst().replayed());
        assertEquals(1, observerB.timerFired().size());
        assertEquals(true, observerB.timerFired().getFirst().replayed());

        // Exactly-once live across both runs
        var liveFires = observerA.timerFired().stream().filter(t -> !t.replayed()).count()
                + observerB.timerFired().stream().filter(t -> !t.replayed()).count();
        assertEquals(1, liveFires, "the logical timer fire must be observed live exactly once");
        assertEquals(0, observerB.parked().stream()
                        .filter(p -> p.kind() == io.b2mash.maestro.core.observe.ParkKind.TIMER).count(),
                "a fully memoized sleep must not park on replay");
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    public static class ThreeStepGatedWorkflow {
        private final WorkflowExecutorObserverTest.StepActivities activities;
        private final CountDownLatch reachedGate;

        public ThreeStepGatedWorkflow(WorkflowExecutorObserverTest.StepActivities activities,
                                      CountDownLatch reachedGate) {
            this.activities = activities;
            this.reachedGate = reachedGate;
        }

        public String run(String input) {
            var a = activities.step1();
            var b = activities.step2();
            reachedGate.countDown();
            WorkflowContext.current().awaitSignal("resume", String.class, Duration.ofSeconds(30));
            var c = activities.step3();
            return a + b + c;
        }
    }

    public static class TwoSignalWorkflow {
        private final CountDownLatch firstConsumed;

        public TwoSignalWorkflow(CountDownLatch firstConsumed) {
            this.firstConsumed = firstConsumed;
        }

        public String run(String input) {
            var ctx = WorkflowContext.current();
            var one = ctx.awaitSignal("first", String.class, Duration.ofSeconds(30));
            firstConsumed.countDown();
            var two = ctx.awaitSignal("second", String.class, Duration.ofSeconds(30));
            return one + two;
        }
    }

    public static class SleepThenAwaitWorkflow {
        private final CountDownLatch slept;

        public SleepThenAwaitWorkflow(CountDownLatch slept) {
            this.slept = slept;
        }

        public String run(String input) {
            var ctx = WorkflowContext.current();
            ctx.sleep(Duration.ofMillis(100));
            slept.countDown();
            return ctx.awaitSignal("after-sleep", String.class, Duration.ofSeconds(30));
        }
    }
}
