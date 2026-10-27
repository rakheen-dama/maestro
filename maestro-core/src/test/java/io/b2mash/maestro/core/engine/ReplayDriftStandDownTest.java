package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        var gate = new CountDownLatch(1);
        var instanceId = executor.startWorkflow(workflowId, "DriftWorkflow", "default", null,
                new DriftWorkflow(gate, secondOp), workflowMethod());
        await().atMost(BOUND)
                .until(() -> store.getEventBySequence(instanceId, 1).isPresent());
        store.injectRawEvent(plant.apply(instanceId));
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
