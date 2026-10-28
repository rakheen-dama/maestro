package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C10 — replay drift. Memoization used to match a stored event to a step by
 * sequence number (and, at some sites, event type) only, so a workflow edited
 * without a {@code workflow.version()} gate silently replayed <em>another
 * step's</em> recorded payload: a renamed activity returned the old step's
 * result and the run completed on it; a side effect landing on a slot recorded
 * by a different operation tried to read that operation's payload as its own
 * and was misclassified as "unreadable payload from a newer node".
 *
 * <p>The contract pinned here: a stored event whose step name or type does not
 * match the operation the current code performs at that sequence stands the
 * run down as {@link StandDownReason#NONDETERMINISTIC_HISTORY} — at the
 * <em>read</em>, before any live side effect — writing nothing, failing
 * nothing, compensating nothing.
 *
 * <p>Fixture shape copied from {@link UnknownHistoryStandDownTest}: the
 * workflow performs a live side effect at seq 1, parks at a gate while the test
 * plants a row at seq 2, then performs the operation under test at seq 2.
 */
@DisplayName("Replay drift — a stored event that disagrees with the current code stands down")
class ReplayDriftStandDownTest {

    private static final String LOCK_PREFIX = "maestro:lock:";
    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private AtomicInteger invocations;
    private Acts acts;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        observer = new RecordingEngineObserver();
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, new MapLock(), null, null, serializer, "node",
                LOCK_PREFIX, Duration.ofSeconds(30), true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
        invocations = new AtomicInteger();
        Acts impl = () -> {
            invocations.incrementAndGet();
            return "live";
        };
        acts = new ActivityProxyFactory().createProxy(Acts.class, impl, store, null, null,
                RetryPolicy.defaultPolicy(), Duration.ofSeconds(5), serializer, new RetryExecutor());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("a renamed activity stands down at the read — the stale payload is never returned")
    void renamedActivity_standsDownBeforeExecuting() {
        var run = startAndPlant("drift-renamed", () -> acts.newName(),
                plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.oldName", json("\"stale\"")));

        awaitRunEnded("drift-renamed");
        await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());

        var call = observer.standDowns().getFirst();
        assertAll(
                () -> assertEquals(1, observer.standDowns().size()),
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=2 expected=Acts.newName stored=Acts.oldName type=ACTIVITY_COMPLETED",
                        call.detail()),
                () -> assertEquals(0, invocations.get(),
                        "the activity impl must not run — stand down at the READ"),
                () -> assertEquals(1, store.appendAttempts(), "nothing appended after the planted row"),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-renamed").orElseThrow().status()),
                () -> assertEquals(0, countOfType(run.instanceId(), EventType.WORKFLOW_FAILED)),
                () -> assertTrue(observer.failed().isEmpty()),
                () -> assertTrue(observer.compensating().isEmpty()));
    }

    @Test
    @DisplayName("a side effect on a fork-marker slot stands down on the step name alone")
    void sideEffectOnForkSlot_standsDownOnStepNameAlone() {
        // type matches (SIDE_EFFECT) — only the step name differs
        startAndPlant("drift-sideeffect", () -> WorkflowContext.current().currentTime(),
                plantAt(2, EventType.SIDE_EFFECT, "$maestro:parallel", json("{\"branchCount\":2}")));

        awaitRunEnded("drift-sideeffect");
        await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());

        var call = observer.standDowns().getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason(),
                        "a side effect reading another operation's slot is drift, not a newer "
                                + "node's payload"),
                () -> assertEquals("seq=2 expected=$maestro:currentTime stored=$maestro:parallel "
                        + "type=SIDE_EFFECT", call.detail()),
                () -> assertEquals(1, store.appendAttempts()),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-sideeffect").orElseThrow().status()));
    }

    @Test
    @DisplayName("history that matches the code replays normally")
    void matchingHistory_replaysNormally() {
        var result = new AtomicReference<String>();
        startAndPlant("drift-none", () -> result.set(acts.newName()),
                plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.newName", json("\"memo\"")));

        TestTerminalWait.awaitTerminal(store, "drift-none", BOUND);

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("drift-none").orElseThrow().status()),
                () -> assertEquals("memo", result.get()),
                () -> assertEquals(0, invocations.get()),
                () -> assertTrue(observer.standDowns().isEmpty()));
    }

    @Test
    @DisplayName("randomUUID() on a currentTime slot stands down — the ISO instant is never "
            + "returned as the UUID")
    void randomUuidOnCurrentTimeSlot_standsDown() {
        var returned = new AtomicReference<String>();
        startAndPlant("drift-uuid", () -> returned.set(WorkflowContext.current().randomUUID()),
                plantAt(2, EventType.SIDE_EFFECT, "$maestro:currentTime",
                        json("\"2026-10-27T10:15:30Z\"")));

        awaitRunEnded("drift-uuid");

        var downs = observer.standDowns();
        assertEquals(1, downs.size(), "exactly one stand-down; got " + downs);
        var call = downs.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=2 expected=$maestro:randomUUID stored=$maestro:currentTime "
                        + "type=SIDE_EFFECT", call.detail()),
                () -> assertNull(returned.get(),
                        "the recorded instant string must never be handed back as the UUID"),
                () -> assertEquals(1, store.appendAttempts()),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-uuid").orElseThrow().status()));
    }

    @Test
    @DisplayName("sleep() on a slot another operation recorded stands down BEFORE saving a timer row")
    void sleepOnOccupiedSlot_standsDownWithoutSavingATimer() {
        startAndPlant("drift-sleep", () -> WorkflowContext.current().sleep(Duration.ofHours(1)),
                plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.oldName", json("\"stale\"")));

        awaitRunEnded("drift-sleep");

        var downs = observer.standDowns();
        assertEquals(1, downs.size(), "exactly one stand-down; got " + downs);
        var call = downs.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=2 expected=$maestro:sleep stored=Acts.oldName type=ACTIVITY_COMPLETED",
                        call.detail()),
                () -> assertTrue(store.getDueTimers(Instant.now().plus(Duration.ofDays(2)), 100).isEmpty(),
                        "the pre-fix symptom: a PENDING timer row saved before the duplicate append "
                                + "— one leaked row per recovery pass"),
                () -> assertEquals(1, store.appendAttempts()),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-sleep").orElseThrow().status()));
    }

    @Test
    @DisplayName("sleep() whose wake slot holds another operation's event stands down at the read — "
            + "no re-park, no timer-row logic, no outcome appended (R19)")
    void sleepWakeSlotForeignEvent_standsDownBeforeAnyTimerLogic() {
        // seq 2 is this sleep's own TIMER_SCHEDULED; seq 3 — the wake/outcome
        // slot — holds an activity the old code ran right after a 1-slot op.
        // Pre-fix the read was type-switched only: a foreign event fell through
        // to the timer-row lookup, re-parked on a timer that does not exist, and
        // eventually collided appending TIMER_FIRED — misreported as stale_run.
        startAndPlant("drift-sleep-wake", () -> WorkflowContext.current().sleep(Duration.ofHours(1)),
                plantAt(2, EventType.TIMER_SCHEDULED, "$maestro:sleep",
                        json("{\"timerId\":\"sleep-2\",\"duration\":\"PT1H\"}")),
                instanceId -> store.injectRawEvent(plantAt(3, EventType.ACTIVITY_COMPLETED, "Acts.oldName",
                        json("\"stale\"")).apply(instanceId)));

        awaitRunEnded("drift-sleep-wake");

        var downs = observer.standDowns();
        assertEquals(1, downs.size(), "exactly one stand-down; got " + downs);
        var call = downs.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=3 expected=$maestro:timer-fired stored=Acts.oldName "
                        + "type=ACTIVITY_COMPLETED", call.detail()),
                () -> assertTrue(store.getDueTimers(Instant.now().plus(Duration.ofDays(2)), 100).isEmpty(),
                        "no timer row written"),
                () -> assertEquals(1, store.appendAttempts(), "no TIMER_FIRED/CANCELLED append attempted"),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-sleep-wake").orElseThrow().status()));
    }

    @Test
    @DisplayName("sleep() whose wake slot holds its own TIMER_FIRED replays without parking")
    void sleepWakeSlotOwnOutcome_replays() {
        startAndPlant("drift-sleep-fired", () -> WorkflowContext.current().sleep(Duration.ofHours(1)),
                plantAt(2, EventType.TIMER_SCHEDULED, "$maestro:sleep",
                        json("{\"timerId\":\"sleep-2\",\"duration\":\"PT1H\"}")),
                instanceId -> store.injectRawEvent(plantAt(3, EventType.TIMER_FIRED, "$maestro:timer-fired",
                        null).apply(instanceId)));

        TestTerminalWait.awaitTerminal(store, "drift-sleep-fired", BOUND);

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("drift-sleep-fired").orElseThrow().status()),
                () -> assertTrue(observer.standDowns().isEmpty()));
    }

    @Test
    @DisplayName("awaitSignal() of a renamed signal stands down — the old signal's payload is never "
            + "returned and the waiting signal is never consumed")
    void awaitSignalRenamed_standsDownWithoutConsuming() {   // Review Focus 2
        var returned = new AtomicReference<String>();
        // a signal for the NEW name is already waiting — the live path would consume it
        startAndPlant("drift-signal",
                () -> returned.set(WorkflowContext.current().awaitSignal("rejected", String.class,
                        Duration.ofSeconds(30))),
                plantAt(2, EventType.SIGNAL_RECEIVED, "$maestro:awaitSignal:approved", json("\"yes\"")),
                instanceId -> store.saveSignal(new WorkflowSignal(UUID.randomUUID(), instanceId,
                        "drift-signal", "rejected", json("\"no\""), false, Instant.now(), null)));

        awaitRunEnded("drift-signal");

        var downs = observer.standDowns();
        assertEquals(1, downs.size(), "exactly one stand-down; got " + downs
                + " (awaitSignal returned " + returned.get() + ", status "
                + store.getInstance("drift-signal").orElseThrow().status() + ")");
        var call = downs.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=2 expected=$maestro:awaitSignal:rejected "
                        + "stored=$maestro:awaitSignal:approved type=SIGNAL_RECEIVED", call.detail()),
                () -> assertNull(returned.get(), "the 'approved' payload must never be returned"),
                () -> assertEquals(1, store.getUnconsumedSignals("drift-signal", "rejected").size(),
                        "never consumed, never discarded"),
                () -> assertTrue(observer.signalConsumed().isEmpty()),
                () -> assertEquals(1, store.appendAttempts()),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-signal").orElseThrow().status()));
    }

    @Test
    @DisplayName("parallel() whose branch count differs from the recorded fork stands down before "
            + "any branch starts")
    void parallelBranchCountMismatch_standsDownBeforeForking() {
        var branchRuns = new AtomicInteger();
        Callable<String> task = () -> {
            branchRuns.incrementAndGet();
            return "b";
        };
        startAndPlant("drift-parallel", () -> WorkflowContext.current().parallel(List.of(task, task)),
                plantAt(2, EventType.SIDE_EFFECT, "$maestro:parallel", json("{\"branchCount\":3}")));

        awaitRunEnded("drift-parallel");

        var downs = observer.standDowns();
        assertEquals(1, downs.size(), "exactly one stand-down; got " + downs
                + " (branches run: " + branchRuns.get() + ", status "
                + store.getInstance("drift-parallel").orElseThrow().status() + ")");
        var call = downs.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                () -> assertEquals("seq=2 expected=$maestro:parallel branches=2 "
                        + "stored=$maestro:parallel branches=3", call.detail()),
                () -> assertEquals(0, branchRuns.get(), "no branch may start"),
                () -> assertEquals(1, store.appendAttempts()),
                () -> assertEquals(WorkflowStatus.RUNNING,
                        store.getInstance("drift-parallel").orElseThrow().status()));
    }

    @Test
    @DisplayName("version() peeking over a non-marker event is 'predates the change', never drift")
    void versionPeekOverForeignEvent_isNotDrift() {   // Review Focus 1
        var resolved = new AtomicInteger(-2);
        var result = new AtomicReference<String>();
        // minSupported must admit DEFAULT_VERSION, or guardVersion rejects the
        // (correct) predates-the-change resolution as unsupported.
        startAndPlant("drift-version", () -> {
            resolved.set(WorkflowContext.current().version("c", WorkflowContext.DEFAULT_VERSION, 1));
            result.set(acts.newName());
        }, plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.newName", json("\"memo\"")));

        TestTerminalWait.awaitTerminal(store, "drift-version", BOUND);

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("drift-version").orElseThrow().status()),
                () -> assertEquals(WorkflowContext.DEFAULT_VERSION, resolved.get(),
                        "a non-marker at the peek slot means 'predates the change'"),
                () -> assertEquals("memo", result.get()),
                () -> assertTrue(observer.standDowns().isEmpty()));
    }

    @Test
    @DisplayName("the stand-down WARN's remedy clause matches the kind — drift says roll back, not "
            + "'an upgraded node will adopt it'")
    void standDownRemedy_dependsOnKind() {
        assertAll(
                () -> assertEquals("an upgraded node will adopt it via recovery",
                        WorkflowExecutor.standDownRemedy(
                                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE)),
                () -> assertEquals("if a newer node wrote it, that node adopts it on recovery; on a "
                                + "homogeneous fleet this is an incompatible payload change — roll back, "
                                + "gate the change with workflow.version(), or terminate (operations §10.4)",
                        WorkflowExecutor.standDownRemedy(
                                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD)),
                () -> assertEquals("roll back the deploy or gate the change behind workflow.version()",
                        WorkflowExecutor.standDownRemedy(
                                UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY)));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private record Run(UUID instanceId, CountDownLatch gate) {}

    /**
     * Starts the workflow, waits for its seq-1 side effect to be durable, plants
     * the given row at the sequence its next read will hit, then releases the
     * gate — the deterministic stand-in for "an earlier build of this workflow
     * recorded a different step here".
     */
    private Run startAndPlant(String workflowId, Runnable secondOp,
                              Function<UUID, WorkflowEvent> plant) {
        return startAndPlant(workflowId, secondOp, plant, _ -> { });
    }

    /**
     * As {@link #startAndPlant(String, Runnable, Function)}, then runs
     * {@code alsoPlant} (e.g. saving a waiting signal) before the gate opens.
     */
    private Run startAndPlant(String workflowId, Runnable secondOp,
                              Function<UUID, WorkflowEvent> plant, Consumer<UUID> alsoPlant) {
        var gate = new CountDownLatch(1);
        var instanceId = executor.startWorkflow(workflowId, "DriftWorkflow", "default", null,
                () -> new DriftWorkflow(gate, secondOp), workflowMethod());
        await().atMost(BOUND)
                .until(() -> store.getEventBySequence(instanceId, 1).isPresent());
        store.injectRawEvent(plant.apply(instanceId));
        alsoPlant.accept(instanceId);
        gate.countDown();
        return new Run(instanceId, gate);
    }

    private static Function<UUID, WorkflowEvent> plantAt(int seq, EventType type, String stepName,
                                                         JsonNode payload) {
        return instanceId -> new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, stepName,
                payload, Instant.now());
    }

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    private int countOfType(UUID instanceId, EventType type) {
        return (int) store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == type).count();
    }

    private static Method workflowMethod() {
        try {
            return DriftWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitRunEnded(String workflowId) {
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
    }

    /** The activity whose method the "new build" renamed from {@code oldName}. */
    @Activity(name = "Acts")
    public interface Acts {
        /** @return a value; {@code "live"} when actually executed */
        String newName();
    }

    /**
     * Performs a live side effect at seq 1, parks at the gate, then runs the
     * operation under test at seq 2 — where the planted row sits.
     */
    @DurableWorkflow(name = "DriftWorkflow")
    public static class DriftWorkflow {

        private final CountDownLatch gate;
        private final Runnable secondOp;

        DriftWorkflow(CountDownLatch gate, Runnable secondOp) {
            this.gate = gate;
            this.secondOp = secondOp;
        }

        /** @return a fixed result, reached only when the history matches the code */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            workflow.currentTime();   // seq 1 — live, appends SIDE_EFFECT
            awaitGate();
            secondOp.run();           // seq 2 — reads the planted row
            return "completed";
        }

        private void awaitGate() {
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test gate never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Minimal keyed {@link DistributedLock}. */
    private static final class MapLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
