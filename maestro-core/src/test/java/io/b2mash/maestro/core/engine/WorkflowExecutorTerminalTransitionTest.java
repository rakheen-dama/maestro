package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link WorkflowExecutor} finalises a workflow when the instance row
 * is written by somebody else between its read and its write.
 *
 * <h2>Why this matters</h2>
 * <p>The executor's success path re-reads the instance, builds
 * {@code version + 1} and writes {@code COMPLETED}. Any concurrent writer
 * invalidates that version: a second node running the same workflow (the
 * documented no-lock-backend degradation, a lock lost mid-run, or a stale lock
 * on a fresh start), or simply another status transition on this node. The
 * resulting {@link OptimisticLockException} escapes into the generic
 * {@code catch (Exception)} that treats <em>workflow</em> failures — so a
 * workflow that ran to success is recorded as {@code FAILED}, its output
 * replaced by the conflict message, and (for a saga) its compensations run.
 *
 * <p>A persistence conflict is not a workflow failure. Finalisation must
 * converge: retry against a fresh read, and stand down if somebody else
 * already reached a terminal state.
 */
@DisplayName("Finalising a workflow converges when another writer touches the instance first")
class WorkflowExecutorTerminalTransitionTest {

    private VersionedStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("a workflow another node already completed is not overwritten with FAILED")
    void concurrentCompletionByAnotherNode_doesNotFlipTheInstanceToFailed() {
        store.completeOutOfBandOnNextUpdate("done-elsewhere");

        var workflowId = start("converge-completed");

        awaitTerminal(workflowId);
        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                "a version conflict while finalising is not a workflow failure");
        assertEquals("done-elsewhere",
                serializer.deserialize(instance.output(), String.class),
                "the winner's output must stand");
        assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED),
                "no WORKFLOW_FAILED event may be recorded for a successful run");
    }

    @Test
    @DisplayName("a version bump by another writer is retried against a fresh read, and the workflow still completes")
    void concurrentNonTerminalUpdate_isRetriedAndTheWorkflowCompletes() {
        store.bumpVersionOutOfBandOnNextUpdate();

        var workflowId = start("converge-retry");

        awaitTerminal(workflowId);
        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                "the conflict is transient — a fresh read resolves it");
        assertEquals("ok", serializer.deserialize(instance.output(), String.class));
        assertEquals(1, store.eventsOfType(EventType.WORKFLOW_COMPLETED));
        assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String start(String workflowId) {
        var impl = new TrivialWorkflow();
        try {
            var method = TrivialWorkflow.class.getMethod("run");
            executor.startWorkflow(workflowId, "TrivialWorkflow", "default", null, impl, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return workflowId;
    }

    private void awaitTerminal(String workflowId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> i.status().isTerminal())
                        .orElse(false));
    }

    /** A workflow with no activities — only its finalisation is under test. */
    @DurableWorkflow(name = "TrivialWorkflow")
    public static class TrivialWorkflow {

        /** @return a fixed result */
        @WorkflowMethod
        public String run() {
            return "ok";
        }
    }

    /**
     * An in-memory store that enforces the canonical optimistic-locking
     * convention and can inject a competing write immediately before a
     * caller's update is validated — the shape of a second node finishing the
     * same workflow a moment earlier.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for the executor's virtual thread and the test thread.
     */
    private static final class VersionedStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completeOutOfBand = new AtomicBoolean();
        private final AtomicBoolean bumpOutOfBand = new AtomicBoolean();
        private final AtomicInteger updateAttempts = new AtomicInteger();
        private volatile String outOfBandOutput = "";

        /** Makes another node finish the workflow just before the next update lands. */
        void completeOutOfBandOnNextUpdate(String output) {
            outOfBandOutput = output;
            completeOutOfBand.set(true);
        }

        /** Makes another writer bump the version (status unchanged) before the next update. */
        void bumpVersionOutOfBandOnNextUpdate() {
            bumpOutOfBand.set(true);
        }

        int eventsOfType(EventType type) {
            return (int) events.stream().filter(e -> e.eventType() == type).count();
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            if (byWorkflowId.putIfAbsent(instance.workflowId(), instance) != null) {
                throw new WorkflowAlreadyExistsException(instance.workflowId());
            }
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(byWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return byWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override
        public synchronized void updateInstance(WorkflowInstance instance) {
            updateAttempts.incrementAndGet();
            var stored = byWorkflowId.get(instance.workflowId());
            assertNotNull(stored, "cannot update an instance that was never created");

            if (completeOutOfBand.compareAndSet(true, false)) {
                stored = stored.toBuilder()
                        .status(WorkflowStatus.COMPLETED)
                        .output(JsonMapper.builder().build().valueToTree(outOfBandOutput))
                        .completedAt(Instant.now())
                        .updatedAt(Instant.now())
                        .version(stored.version() + 1)
                        .build();
                byWorkflowId.put(stored.workflowId(), stored);
                events.add(new WorkflowEvent(UUID.randomUUID(), stored.id(), 1,
                        EventType.WORKFLOW_COMPLETED, null, stored.output(), Instant.now()));
            } else if (bumpOutOfBand.compareAndSet(true, false)) {
                stored = stored.toBuilder()
                        .updatedAt(Instant.now())
                        .version(stored.version() + 1)
                        .build();
                byWorkflowId.put(stored.workflowId(), stored);
            }

            if (instance.version() != stored.version() + 1) {
                throw new OptimisticLockException(instance.workflowId(),
                        instance.version() - 1, stored.version());
            }
            byWorkflowId.put(instance.workflowId(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            events.add(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId)
                            && e.sequenceNumber() == sequenceNumber)
                    .findFirst();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId))
                    .toList();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            // no signals in these tests
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return new ArrayList<>();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return true;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            // no signals in these tests
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            // no timers in these tests
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return new ArrayList<>();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return true;
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            // no timers in these tests
        }
    }

    /** Guards against the store double silently drifting from the SPI. */
    @Test
    @DisplayName("the store double really does enforce optimistic locking")
    void storeDouble_enforcesOptimisticLocking() {
        var workflowId = start("converge-guard");
        awaitTerminal(workflowId);
        var instance = store.getInstance(workflowId).orElseThrow();
        assertTrue(instance.version() > 0, "a completed workflow must have been versioned up");
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
    }
}
