package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.DuplicateEventException;
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

import static io.b2mash.maestro.core.TestEventLogs.removeFailureEvents;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link WorkflowExecutor} reacts when a mid-run event append hits the
 * store's {@code (workflow_instance_id, sequence_number)} unique guard —
 * {@link DuplicateEventException} reaching the executor's top level.
 *
 * <h2>Why this matters (Issue 18)</h2>
 * <p>In the Issue 11 no-fencing split-brain window (a node frozen past the
 * instance-lock TTL; a peer adopts and re-runs the workflow), the stale runner's
 * next event append collides with the winner's already-persisted event. That
 * exception means <em>"another run owns this workflow's progress — this node's
 * view is stale"</em>. It is the store's dedup guard doing its job, not a
 * workflow failure. Before the fix it fell into the generic
 * {@code catch (Exception)}, which durably marked a workflow that SUCCEEDED (on
 * the winner) as {@code FAILED} and ran its compensations — real side-effect
 * reversal of completed work.
 *
 * <p>The contract pinned here: the loser <b>stands down</b> like the
 * shutdown/termination cases — writes no failure state, runs no compensation,
 * and leaves the winner's durable outcome (or, absent a winner, a recoverable
 * active instance) untouched.
 */
@DisplayName("A stale run whose event append collides with a concurrent runner stands down")
class WorkflowExecutorDuplicateEventStandDownTest {

    private ConflictingStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new ConflictingStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("the loser adopts the winner's COMPLETED outcome: no FAILED write, no compensation")
    void appendCollision_whenWinnerCompleted_standsDownWithoutFailureOrCompensation() {
        var compensations = new AtomicInteger();
        // The winner (the adopting node) already completed the workflow; this
        // node's next append collides with the winner's event at that sequence.
        store.duplicateOnNextWorkflowAppend(true, "winner-output");

        var workflowId = start("standdown-winner", compensations);

        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning(workflowId));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status(),
                "the winner's terminal outcome must stand — a duplicate append is "
                        + "a stale-run signal, not a workflow failure");
        assertEquals("winner-output",
                serializer.deserialize(instance.output(), String.class),
                "the winner's output must not be overwritten");
        assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED),
                "the loser must not record WORKFLOW_FAILED for a workflow that succeeded");
        assertEquals(0, compensations.get(),
                "the loser must not compensate work that did not fail");
        assertEquals(0, store.eventsOfType(EventType.COMPENSATION_STARTED),
                "no compensation phase may be recorded by the stale run");
    }

    @Test
    @DisplayName("with no winner the instance stays active and recoverable — never invented FAILED")
    void appendCollision_withNoWinner_leavesInstanceRecoverable() {
        var compensations = new AtomicInteger();
        store.duplicateOnNextWorkflowAppend(false, null);

        var workflowId = start("standdown-self", compensations);

        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning(workflowId));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertNotEquals(WorkflowStatus.FAILED, instance.status(),
                "a duplicate append must never be recorded as a workflow failure");
        assertFalse(instance.status().isTerminal(),
                "the instance must stay non-terminal so recovery can replay it from the log");
        assertTrue(store.getRecoverableInstances().stream()
                        .anyMatch(i -> i.workflowId().equals(workflowId)),
                "the workflow must remain recoverable");
        assertEquals(0, compensations.get(), "no compensation may run");
        assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED));
    }

    @Test
    @DisplayName("a duplicate append during compensation stands the whole attempt down, recording nothing")
    void appendCollisionDuringCompensation_standsDownWithoutRecordingStepFailure() {
        var compensations = new AtomicInteger();
        // The workflow genuinely fails; compensation begins; but the
        // COMPENSATION_STARTED append collides — the adopting node is already
        // compensating this workflow. The stale run must abandon the attempt.
        store.duplicateOnNextCompensationAppend();

        var workflowId = startFailing("standdown-compensation", compensations);

        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning(workflowId));

        var instance = store.getInstance(workflowId).orElseThrow();
        assertFalse(instance.status().isTerminal(),
                "the stale run must not finalise a workflow another runner is compensating");
        assertEquals(0, compensations.get(),
                "the stale run must not execute compensation actions the winner owns");
        assertEquals(0, store.eventsOfType(EventType.COMPENSATION_STEP_FAILED),
                "a duplicate append is not a compensation-step failure");
        assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED),
                "the stale run must not write the terminal outcome");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String start(String workflowId, AtomicInteger compensations) {
        var impl = new CompensableWorkflow(compensations, false);
        try {
            var method = CompensableWorkflow.class.getMethod("run");
            executor.startWorkflow(workflowId, "CompensableWorkflow", "default", null, impl, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return workflowId;
    }

    private String startFailing(String workflowId, AtomicInteger compensations) {
        var impl = new CompensableWorkflow(compensations, true);
        try {
            var method = CompensableWorkflow.class.getMethod("run");
            executor.startWorkflow(workflowId, "CompensableWorkflow", "default", null, impl, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return workflowId;
    }

    /**
     * Registers a compensation, then performs a deterministic operation whose
     * event append is the collision point ({@code currentTime()} appends a
     * SIDE_EFFECT event), then either succeeds or fails.
     */
    @DurableWorkflow(name = "CompensableWorkflow")
    public static class CompensableWorkflow {

        private final AtomicInteger compensations;
        private final boolean failAfter;

        CompensableWorkflow(AtomicInteger compensations, boolean failAfter) {
            this.compensations = compensations;
            this.failAfter = failAfter;
        }

        /** @return a fixed result (when the run survives its append) */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            workflow.addCompensation(compensations::incrementAndGet);
            workflow.currentTime();   // appends a SIDE_EFFECT event — the collision point
            if (failAfter) {
                throw new RuntimeException("genuine workflow failure");
            }
            return "local-output";
        }
    }

    /**
     * An in-memory store whose {@code appendEvent} can be armed to throw
     * {@link DuplicateEventException} — the unique-index rejection a stale run
     * sees when a concurrent runner already wrote that sequence — optionally
     * installing the winner's COMPLETED outcome at the same moment.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for the executor's virtual threads and the test thread.
     */
    private static final class ConflictingStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean duplicateNextWorkflowAppend = new AtomicBoolean();
        private final AtomicBoolean duplicateNextCompensationAppend = new AtomicBoolean();
        private final AtomicBoolean winnerCompletes = new AtomicBoolean();
        private volatile String winnerOutput = "";

        void duplicateOnNextWorkflowAppend(boolean winnerCompleted, String output) {
            winnerCompletes.set(winnerCompleted);
            if (output != null) {
                winnerOutput = output;
            }
            duplicateNextWorkflowAppend.set(true);
        }

        void duplicateOnNextCompensationAppend() {
            duplicateNextCompensationAppend.set(true);
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
            var stored = byWorkflowId.get(instance.workflowId());
            assertNotNull(stored, "cannot update an instance that was never created");
            if (instance.version() != stored.version() + 1) {
                throw new io.b2mash.maestro.core.exception.OptimisticLockException(
                        instance.workflowId(), instance.version() - 1, stored.version());
            }
            byWorkflowId.put(instance.workflowId(), instance);
        }

        @Override
        public synchronized void appendEvent(WorkflowEvent event) {
            boolean compensationEvent = event.eventType() == EventType.COMPENSATION_STARTED
                    || event.eventType() == EventType.COMPENSATION_STEP_COMPLETED;
            if (compensationEvent && duplicateNextCompensationAppend.compareAndSet(true, false)) {
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            if (!compensationEvent && duplicateNextWorkflowAppend.compareAndSet(true, false)) {
                if (winnerCompletes.get()) {
                    installWinnerOutcome(event.workflowInstanceId());
                }
                throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
            }
            events.add(event);
        }

        /** The adopting node's finalisation: COMPLETED instance + terminal event. */
        private void installWinnerOutcome(UUID instanceId) {
            byWorkflowId.values().stream()
                    .filter(i -> i.id().equals(instanceId))
                    .findFirst()
                    .ifPresent(stored -> {
                        var output = JsonMapper.builder().build().valueToTree(winnerOutput);
                        var winner = stored.toBuilder()
                                .status(WorkflowStatus.COMPLETED)
                                .output(output)
                                .completedAt(Instant.now())
                                .updatedAt(Instant.now())
                                .version(stored.version() + 1)
                                .build();
                        byWorkflowId.put(winner.workflowId(), winner);
                        events.add(new WorkflowEvent(UUID.randomUUID(), instanceId, 999,
                                EventType.WORKFLOW_COMPLETED, null, output, Instant.now()));
                    });
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
        public int deleteFailureEvents(UUID instanceId) {
            return removeFailureEvents(events, instanceId);
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
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return Optional.empty();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return true;
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            return false;
        }
    }
}
