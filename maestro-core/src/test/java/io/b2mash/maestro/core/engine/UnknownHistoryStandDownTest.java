package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unknown-event stand-down contract (spec §C2, design §6) at the executor
 * level: a run that reads persisted history this build cannot interpret
 * <b>stands down</b> — releases the instance lock, leaves the instance exactly
 * as it found it, records nothing and compensates nothing — and the same
 * instance is adoptable and completes once the unreadable row is gone.
 *
 * <h2>The failure this prevents</h2>
 * <p>Version N+1 of a service writes a new event type. A node still on version
 * N adopts one of those workflows for recovery, its row mapper meets a type
 * string it does not define, and — before this mechanism — the resulting
 * exception was indistinguishable from a workflow failure by the time it
 * reached {@code executeWorkflow}. The workflow was durably marked
 * {@code FAILED} and its sagas compensated: refunds issued, reservations
 * released, for a workflow that never failed and was, on the upgraded half of
 * the fleet, perfectly healthy.
 *
 * <p>The assertions here are therefore positive facts about durable state the
 * node must leave alone, plus the two things it must actually do: emit
 * {@code standDown} and let go of the instance lock.
 */
@DisplayName("A run that cannot read its own history stands down — never fails, never compensates")
class UnknownHistoryStandDownTest {

    /** A type string no build of this repo will ever define (design §8.5, RULING 1). */
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

    private static final String LOCK_PREFIX = "maestro:lock:";
    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private MapLock lock;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        lock = new MapLock();
        observer = new RecordingEngineObserver();
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, lock, null, null, serializer, "old-node",
                LOCK_PREFIX, Duration.ofSeconds(30), true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("no failure is written, no compensation runs, and the instance stays recoverable")
    void unknownEvent_standsDownWithoutFailureOrCompensation() {
        var compensations = new AtomicInteger();
        var workflowId = "standdown-unknown-type";
        var run = startAndPlantFutureEvent(workflowId, compensations);

        awaitRunEnded(workflowId);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, instance.status(),
                        "the instance must keep the exact status the run found it in — "
                                + "a stand-down writes no status at all"),
                () -> assertNotEquals(WorkflowStatus.FAILED, instance.status(),
                        "an unreadable history is NEVER a workflow failure"),
                () -> assertFalse(instance.status().isTerminal(),
                        "the instance must stay non-terminal so an upgraded node can adopt it"),
                () -> assertEquals(0, countOfType(run.instanceId(), EventType.WORKFLOW_FAILED),
                        "no WORKFLOW_FAILED event may be written"),
                () -> assertEquals(0, countOfType(run.instanceId(), EventType.COMPENSATION_STARTED),
                        "zero COMPENSATION_STARTED events — a stand-down never opens a "
                                + "compensation phase"),
                () -> assertEquals(0, countOfType(run.instanceId(), EventType.COMPENSATION_STEP_COMPLETED)),
                () -> assertEquals(0, countOfType(run.instanceId(), EventType.COMPENSATION_STEP_FAILED)),
                () -> assertEquals(0, compensations.get(),
                        "no compensation action may run for work that never failed"),
                () -> assertEquals(2, store.getEvents(run.instanceId()).size(),
                        "the log holds exactly the live SIDE_EFFECT and the planted future "
                                + "event — the stand-down appended nothing"),
                () -> assertEquals(1, store.appendAttempts(),
                        "the run must stand down at the READ, before it re-executes the "
                                + "memoized step and attempts a second append. Being stopped "
                                + "by the unique index instead means the side effect already "
                                + "happened and the run stands down as STALE_RUN — the wrong "
                                + "reason, after touching the outside world"),
                () -> assertTrue(store.getRecoverableInstances().stream()
                                .anyMatch(i -> i.workflowId().equals(workflowId)),
                        "the workflow must remain recoverable by an upgraded node"));
    }

    @Test
    @DisplayName("the instance lock is released — an upgraded node can acquire it immediately")
    void unknownEvent_releasesTheInstanceLock() {
        var workflowId = "standdown-lock-release";
        startAndPlantFutureEvent(workflowId, new AtomicInteger());

        awaitRunEnded(workflowId);

        var lockKey = LOCK_PREFIX + "workflow:" + workflowId;
        assertAll(
                () -> assertFalse(lock.isHeld(lockKey),
                        "the instance lock must be released as the stand-down unwinds — "
                                + "a held lock keeps every upgraded node out until TTL"),
                () -> assertTrue(lock.tryAcquire(lockKey, BOUND).isPresent(),
                        "an upgraded node must be able to acquire the lock right now"),
                () -> assertFalse(executor.isRunning(workflowId),
                        "the local run must be over"));
    }

    @Test
    @DisplayName("observer.standDown fires once with UNKNOWN_EVENT_TYPE and the offending sequence")
    void unknownEvent_emitsStandDownObservation() {
        var workflowId = "standdown-observed";
        startAndPlantFutureEvent(workflowId, new AtomicInteger());

        awaitRunEnded(workflowId);
        await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());

        var standDowns = observer.standDowns();
        assertEquals(1, standDowns.size(),
                "exactly one stand-down observation: " + observer.callbackNames());
        var call = standDowns.getFirst();
        assertAll(
                () -> assertEquals(StandDownReason.UNKNOWN_EVENT_TYPE, call.reason(),
                        "the reason tag is what tells an operator a mixed-version fleet is "
                                + "lingering, so it must not be STALE_RUN"),
                () -> assertEquals(workflowId, call.workflowId()),
                () -> assertEquals("seq=2", call.detail(),
                        "the detail must name the sequence of the row this node could not read"),
                () -> assertTrue(observer.failed().isEmpty(),
                        "workflowFailed must NOT fire — this is not a failure"),
                () -> assertTrue(observer.compensating().isEmpty(),
                        "workflowCompensating must NOT fire"),
                () -> assertTrue(observer.completed().isEmpty(),
                        "workflowCompleted must NOT fire either — the run resolved nothing"));
    }

    @Test
    @DisplayName("once the unreadable row is normalized the SAME instance is adopted and completes")
    void afterNormalizingTheRow_theSameInstanceCompletes() {
        var compensations = new AtomicInteger();
        var workflowId = "standdown-then-adopted";
        var run = startAndPlantFutureEvent(workflowId, compensations);

        awaitRunEnded(workflowId);
        await().atMost(BOUND).until(() -> observer.standDowns().size() == 1);

        // The upgraded node's world: the row is readable again (here, removed —
        // the integration suite deletes the injected row for the same reason).
        store.removeEvent(run.instanceId(), 2);

        var openGate = new CountDownLatch(0);
        var recovered = executor.recoverWorkflows(Map.of("StandDownWorkflow",
                new WorkflowRegistration("StandDownWorkflow", "default",
                        new StandDownWorkflow(compensations, openGate), workflowMethod())));
        assertEquals(1, recovered, "the stood-down instance must still be adoptable");

        await().atMost(BOUND)
                .until(() -> store.getInstance(workflowId).orElseThrow().status().isTerminal());

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                        "the workflow the old node could not read completes on a node that can"),
                () -> assertEquals(0, compensations.get(),
                        "the stand-down must not have left compensation debt behind"),
                () -> assertEquals(1, observer.standDowns().size(),
                        "the successful pass must not emit a second stand-down"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private record Run(UUID instanceId, CountDownLatch gate) {}

    /**
     * Starts the workflow, waits for it to park at the gate with its first
     * side-effect durable, plants the future event at the sequence its next
     * read will hit, then releases it — the deterministic stand-in for "a
     * newer node wrote this row while this one was elsewhere".
     */
    private Run startAndPlantFutureEvent(String workflowId, AtomicInteger compensations) {
        var gate = new CountDownLatch(1);
        var instanceId = executor.startWorkflow(workflowId, "StandDownWorkflow", "default", null,
                new StandDownWorkflow(compensations, gate), workflowMethod());
        await().atMost(BOUND)
                .until(() -> store.getEventBySequence(instanceId, 1).isPresent());
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 2,
                EventType.fromStoredName(FUTURE_TYPE), "$maestro:from-the-future", null,
                Instant.now()));
        gate.countDown();
        return new Run(instanceId, gate);
    }

    private int countOfType(UUID instanceId, EventType type) {
        return (int) store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == type).count();
    }

    private static Method workflowMethod() {
        try {
            return StandDownWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitRunEnded(String workflowId) {
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
    }

    /**
     * Registers a compensation, then performs two memoized side effects with a
     * gate between them. The second one's replay read is where the planted
     * future event sits — so the stand-down happens with a non-empty
     * compensation stack, which is exactly the state in which "record a failure
     * and compensate" does real damage.
     */
    @DurableWorkflow(name = "StandDownWorkflow")
    public static class StandDownWorkflow {

        private final AtomicInteger compensations;
        private final CountDownLatch gate;

        StandDownWorkflow(AtomicInteger compensations, CountDownLatch gate) {
            this.compensations = compensations;
            this.gate = gate;
        }

        /** @return a fixed result, reached only when the history is readable */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            workflow.addCompensation(compensations::incrementAndGet);
            workflow.currentTime();   // seq 1 — live, appends SIDE_EFFECT
            awaitGate();
            workflow.currentTime();   // seq 2 — reads the planted future event
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

    /** Minimal keyed {@link DistributedLock}; {@link #isHeld} is the release pin. */
    private static final class MapLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        boolean isHeld(String key) {
            return locks.containsKey(key);
        }

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
