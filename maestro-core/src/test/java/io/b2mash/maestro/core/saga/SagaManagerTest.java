package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.exception.BranchSequenceExhaustedException;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.b2mash.maestro.core.TestEventLogs.removeFailureEvents;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SagaManager}.
 */
class SagaManagerTest {

    private InMemoryStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private SagaManager sagaManager;

    private WorkflowInstance instance;
    private WorkflowContext ctx;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(new ObjectMapper());
        sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        // Create a workflow instance
        var instanceId = UUID.randomUUID();
        instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("test-workflow")
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(5)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0)
                .build();
        store.createInstance(instance);

        ctx = new WorkflowContext(
                instanceId, "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service",
                5, false
        );
    }

    @Test
    @DisplayName("Sequential compensation runs in LIFO order")
    void sequentialCompensationRunsInLifoOrder() {
        var order = new ArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-A", () -> order.add("A"));
        stack.push("step-B", () -> order.add("B"));
        stack.push("step-C", () -> order.add("C"));

        sagaManager.compensate(ctx, instance, stack, false);

        // LIFO: C, B, A
        assertEquals(List.of("C", "B", "A"), order);
    }

    @Test
    @DisplayName("Status transitions to COMPENSATING during compensation")
    void statusTransitionsToCompensating() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> {});

        sagaManager.compensate(ctx, instance, stack, false);

        // The instance should have been set to COMPENSATING (SagaManager doesn't set FAILED)
        var latest = store.getInstance("test-workflow").orElseThrow();
        assertEquals(WorkflowStatus.COMPENSATING, latest.status());
    }

    @Test
    @DisplayName("Transition to COMPENSATING pre-increments the instance version")
    void transitionToCompensatingPreIncrementsVersion() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> {});

        sagaManager.compensate(ctx, instance, stack, false);

        // Canonical optimistic-lock convention: the caller builds the updated
        // instance with version = current + 1 before calling updateInstance.
        var latest = store.getInstance("test-workflow").orElseThrow();
        assertEquals(WorkflowStatus.COMPENSATING, latest.status());
        assertEquals(instance.version() + 1, latest.version());
    }

    @Test
    @DisplayName("Events: COMPENSATION_STARTED, then COMPENSATION_COMPLETED")
    void compensationEventsRecorded() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> {});

        sagaManager.compensate(ctx, instance, stack, false);

        var events = store.getEvents(instance.id());
        var types = events.stream().map(WorkflowEvent::eventType).toList();

        assertTrue(types.contains(EventType.COMPENSATION_STARTED));
        assertTrue(types.contains(EventType.COMPENSATION_COMPLETED));

        // STARTED should come before COMPLETED
        var startedIdx = types.indexOf(EventType.COMPENSATION_STARTED);
        var completedIdx = types.indexOf(EventType.COMPENSATION_COMPLETED);
        assertTrue(startedIdx < completedIdx);
    }

    @Test
    @DisplayName("Failed compensation is logged but doesn't stop remaining compensations")
    void failedCompensationDoesNotStopOthers() {
        var order = new ArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-A", () -> order.add("A"));
        stack.push("step-B", () -> { throw new RuntimeException("B failed"); });
        stack.push("step-C", () -> order.add("C"));

        // Partial failure throws CompensationException
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                CompensationException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(List.of("step-B"), ex.failedCompensations());

        // LIFO: C, then B (fails), then A (still runs)
        assertEquals(List.of("C", "A"), order);

        // COMPENSATION_STEP_FAILED event should be recorded
        var events = store.getEvents(instance.id());
        var failedEvents = events.stream()
                .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
        assertEquals("step-B", failedEvents.getFirst().stepName());
    }

    @Test
    @DisplayName("Parallel compensation runs all compensations concurrently")
    void parallelCompensationRunsConcurrently() {
        var counter = new AtomicInteger(0);
        var stack = new CompensationStack();
        // Push 5 compensations
        for (int i = 0; i < 5; i++) {
            stack.push("step-" + i, () -> {
                counter.incrementAndGet();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            });
        }

        sagaManager.compensate(ctx, instance, stack, true);

        // All 5 should have run
        assertEquals(5, counter.get());
    }

    @Test
    @DisplayName("Empty stack is a no-op")
    void emptyStackIsNoop() {
        var stack = new CompensationStack();

        sagaManager.compensate(ctx, instance, stack, false);

        // No status change, no events
        var latest = store.getInstance("test-workflow").orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, latest.status());

        var events = store.getEvents(instance.id());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("A terminate racing a failing workflow must not overwrite TERMINATED with COMPENSATING")
    void terminateRacingAFailure_doesNotOverwriteTerminatedWithCompensating() {
        // Model WorkflowExecutor.terminateWorkflow having already won the race
        // and durably written TERMINATED before this (separate) failing run
        // reaches its own compensation phase.
        store.updateInstance(instance.toBuilder()
                .status(WorkflowStatus.TERMINATED)
                .completedAt(Instant.now())
                .version(instance.version() + 1)
                .build());

        var order = new ArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-A", () -> order.add("A"));

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowTerminatedException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));
        assertEquals("test-workflow", thrown.workflowId());

        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("test-workflow").orElseThrow().status(),
                "TERMINATED must not be resurrected as COMPENSATING");
        assertTrue(order.isEmpty(), "no compensation may run once the instance is terminated");
        assertTrue(store.getEvents(instance.id()).isEmpty(),
                "no COMPENSATION_STARTED may be recorded for a run that must not compensate");
    }

    @Test
    @DisplayName("Parallel compensation: failed step doesn't prevent others")
    void parallelFailedStepDoesNotPreventOthers() {
        var counter = new AtomicInteger(0);
        var stack = new CompensationStack();
        stack.push("step-ok-1", counter::incrementAndGet);
        stack.push("step-fail", () -> { throw new RuntimeException("fail"); });
        stack.push("step-ok-2", counter::incrementAndGet);

        // Partial failure throws CompensationException
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                CompensationException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));

        assertEquals(1, ex.failedCompensations().size());

        // The 2 successful compensations should have run despite the failure
        assertEquals(2, counter.get());

        // COMPENSATION_STEP_FAILED event should be recorded
        var failedEvents = store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_FAILED)
                .toList();
        assertEquals(1, failedEvents.size());
    }

    @Test
    @DisplayName("Parallel compensation: a branch that exhausts its block is recorded COMPENSATION_STEP_FAILED with the typed cause")
    void parallelCompensationBranchExhaustingItsBlock_isRecordedFailed() {
        var stack = new CompensationStack();
        stack.push("greedy", () -> { for (int i = 0; i < 1000; i++) WorkflowContext.current().nextSequence(); });

        org.junit.jupiter.api.Assertions.assertThrows(CompensationException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));

        var failed = store.getEventBySequence(instance.id(), 8000).orElseThrow();
        assertEquals(EventType.COMPENSATION_STEP_FAILED, failed.eventType());
        assertEquals(BranchSequenceExhaustedException.class.getName(), failed.payload().get("exceptionType").asText());
    }

    // ── Final review I1: sequential entries are bounded to their block ───
    //
    // ctx starts at 5, so COMPENSATION_STARTED takes 6 and the per-entry
    // blocks are 7000 (entry 0, A) and 8000 (entry 1, B); COMPENSATION_COMPLETED
    // lands one past nextParentSeq(6, 2) = 9000. compensate() runs with ctx
    // bound in a scope, exactly as on the engine's workflow thread.

    @Test
    @DisplayName("Sequential compensation: an entry that exhausts its 1000-slot block is recorded COMPENSATION_STEP_FAILED with the typed cause, and the next entry still runs")
    void sequentialEntryExhaustingItsBlock_isRecordedFailedAndTheNextEntryRuns() {
        var bInvocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-B", bInvocations::incrementAndGet);
        stack.push("step-A", () -> { for (int i = 0; i < 1000; i++) WorkflowContext.current().nextSequence(); });

        org.junit.jupiter.api.Assertions.assertThrows(CompensationException.class,
                () -> ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                        .run(() -> sagaManager.compensate(ctx, instance, stack, false)));

        var failed = store.getEventBySequence(instance.id(), 7000).orElseThrow();
        assertEquals(EventType.COMPENSATION_STEP_FAILED, failed.eventType());
        assertEquals("step-A", failed.stepName());
        assertEquals(BranchSequenceExhaustedException.class.getName(), failed.payload().get("exceptionType").asText());
        assertEquals(1, bInvocations.get(), "entry B must not be skipped by A spilling into its guard slot");
        var completed = store.getEventBySequence(instance.id(), 8000).orElseThrow();
        assertEquals(EventType.COMPENSATION_STEP_COMPLETED, completed.eventType());
        assertEquals("step-B", completed.stepName());
        assertEquals(EventType.COMPENSATION_COMPLETED,
                store.getEventBySequence(instance.id(), 9001).orElseThrow().eventType(),
                "the parent counter is still rebased past every entry block");
    }

    @Test
    @DisplayName("Sequential compensation: an entry that takes exactly 999 slots fits its block and both entries complete")
    void sequentialEntryWith999Steps_fitsAndBothComplete() {
        var bInvocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-B", bInvocations::incrementAndGet);
        stack.push("step-A", () -> { for (int i = 0; i < 999; i++) WorkflowContext.current().nextSequence(); });

        ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                .run(() -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(1, bInvocations.get());
        assertEquals(EventType.COMPENSATION_STEP_COMPLETED,
                store.getEventBySequence(instance.id(), 7000).orElseThrow().eventType());
        assertEquals(EventType.COMPENSATION_STEP_COMPLETED,
                store.getEventBySequence(instance.id(), 8000).orElseThrow().eventType());
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
    }

    // ── Issue 14: replay-skip guard ─────────────────────────────────────
    //
    // Simulates a shutdown-interrupted compensation without needing real
    // parking: an ExecutorShutdownException (an Error, deliberately not
    // caught by `catch (Exception)`) thrown synchronously from a
    // compensation action mirrors what SignalManager.awaitSignal throws
    // when a park is abandoned by shutdown. A second compensate() call
    // against a FRESH WorkflowContext seeded at the same initial sequence
    // — over the SAME store/instance — mirrors a recovery re-run: the
    // workflow re-registers the same compensations (same step names, same
    // LIFO order) and compensate() runs again.
    //
    // The completed step is deliberately NOT first in LIFO order (it is
    // pushed last, so it unwinds first) — the shape the existing shutdown
    // fixtures never exercise, per docs/open-issues.md Issue 14.

    @Test
    @DisplayName("Sequential: a compensation that completed before a later one was interrupted "
            + "is not re-invoked on a recovery replay, and its event is not duplicated")
    void sequentialReplayDoesNotReinvokeAnAlreadyCompletedCompensation() {
        var completedInvocations = new AtomicInteger();
        var stack1 = new CompensationStack();
        // Pushed first -> unwinds SECOND -> the one "shutdown" interrupts.
        stack1.push("interrupted-step", () -> {
            throw new ExecutorShutdownException("simulated shutdown");
        });
        // Pushed second -> unwinds FIRST -> completes durably before the
        // interruption.
        stack1.push("completed-step", completedInvocations::incrementAndGet);

        org.junit.jupiter.api.Assertions.assertThrows(ExecutorShutdownException.class,
                () -> sagaManager.compensate(ctx, instance, stack1, false));

        assertEquals(1, completedInvocations.get(),
                "the first-in-LIFO-order step must have completed before the second was interrupted");
        assertEquals(1, countEvents(EventType.COMPENSATION_STEP_COMPLETED),
                "the completed step's outcome must be durable");
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "the interrupted step must not be recorded as failed");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED),
                "shutdown must not finalise the compensation phase");

        // Recovery: a fresh context seeded at the same starting sequence,
        // over the same store/instance — the workflow re-registers the same
        // compensations in the same LIFO order.
        var ctx2 = new WorkflowContext(
                instance.id(), "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service", 5, true);
        var interruptedInvocations = new AtomicInteger();
        var stack2 = new CompensationStack();
        stack2.push("interrupted-step", interruptedInvocations::incrementAndGet);
        stack2.push("completed-step", completedInvocations::incrementAndGet);

        sagaManager.compensate(ctx2, instance, stack2, false);

        assertEquals(1, completedInvocations.get(),
                "the already-completed compensation action must NOT be re-invoked on replay");
        assertEquals(1, interruptedInvocations.get(),
                "the previously-interrupted compensation must complete once retried");
        assertEquals(2, countEvents(EventType.COMPENSATION_STEP_COMPLETED),
                "exactly one COMPENSATION_STEP_COMPLETED per step — no duplicate for 'completed-step'");
        assertEquals(1, countEvents(EventType.COMPENSATION_STARTED),
                "COMPENSATION_STARTED must not be duplicated on replay");
        assertEquals(1, countEvents(EventType.COMPENSATION_COMPLETED),
                "COMPENSATION_COMPLETED must be recorded exactly once, on the run that actually finished");
    }

    @Test
    @DisplayName("Parallel: a compensation branch that completed before a sibling branch was "
            + "interrupted is not re-invoked on a recovery replay, and its event is not duplicated")
    void parallelReplayDoesNotReinvokeAnAlreadyCompletedCompensation() {
        var completedInvocations = new AtomicInteger();
        var stack1 = new CompensationStack();
        stack1.push("interrupted-branch", () -> {
            throw new ExecutorShutdownException("simulated shutdown");
        });
        stack1.push("completed-branch", completedInvocations::incrementAndGet);

        org.junit.jupiter.api.Assertions.assertThrows(ExecutorShutdownException.class,
                () -> sagaManager.compensate(ctx, instance, stack1, true));

        assertEquals(1, completedInvocations.get());
        assertEquals(1, countEvents(EventType.COMPENSATION_STEP_COMPLETED));
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));

        var ctx2 = new WorkflowContext(
                instance.id(), "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service", 5, true);
        var interruptedInvocations = new AtomicInteger();
        var stack2 = new CompensationStack();
        stack2.push("interrupted-branch", interruptedInvocations::incrementAndGet);
        stack2.push("completed-branch", completedInvocations::incrementAndGet);

        sagaManager.compensate(ctx2, instance, stack2, true);

        assertEquals(1, completedInvocations.get(),
                "the already-completed branch's action must NOT be re-invoked on replay");
        assertEquals(1, interruptedInvocations.get(),
                "the previously-interrupted branch must complete once retried");
        assertEquals(2, countEvents(EventType.COMPENSATION_STEP_COMPLETED),
                "exactly one COMPENSATION_STEP_COMPLETED per branch — no duplicate for 'completed-branch'");
        assertEquals(1, countEvents(EventType.COMPENSATION_STARTED));
        assertEquals(1, countEvents(EventType.COMPENSATION_COMPLETED));
        assertEquals(1, store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.SIDE_EFFECT
                        && "$maestro:parallel-compensation".equals(e.stepName())).count(),
                "the fork marker must be appended once, never re-appended on replay");
    }

    // ── Unknown-history stand-down during compensation (design §§6.3–6.4) ──
    //
    // Compensation is the single most dangerous place for an unreadable event.
    // Every read here decides whether a compensation action is re-invoked, and
    // every catch here decides whether a step is recorded COMPENSATION_STEP_FAILED
    // — a durable claim that a reversal was attempted and failed. If a
    // stand-down were recorded as a step failure, an operator investigating a
    // mixed-version window would see a saga that half-unwound when in fact
    // nothing ran at all; and if it were swallowed, the compensations would run
    // twice once an upgraded node adopts the workflow.
    //
    // The stand-down signal is an Error, so `catch (Exception e)` inside
    // SagaManager cannot reach it; what these pins protect is the two places
    // that catch broadly on purpose — executeSequential's DuplicateEventException
    // arm and executeParallel's `catch (Throwable t)` outcome collector.

    /** A type string no build of this repo will ever define (design §8.5, RULING 1). */
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

    private static UnknownWorkflowHistoryException standDown() {
        return new UnknownWorkflowHistoryException("test-workflow", 7000,
                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                "written by a newer node");
    }

    private void plantFutureEvent(int sequenceNumber) {
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), sequenceNumber,
                EventType.fromStoredName(FUTURE_TYPE), "$maestro:from-the-future", null,
                Instant.now()));
    }

    @Test
    @DisplayName("Stand-down: the COMPENSATION_STARTED replay read is guarded — nothing is written, "
            + "no action runs")
    void compensationStartedReadStandsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // ctx is seeded at sequence 5, so compensate() consumes 6 for its
        // COMPENSATION_STARTED replay-skip read.
        plantFutureEvent(6);

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "an unreadable event at the compensation anchor must stand the attempt down");

        assertEquals(0, invocations.get(), "no compensation action may run");
        assertEquals(0, countEvents(EventType.COMPENSATION_STARTED));
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "a stand-down is never a failed compensation step");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Stand-down: a sequential entry's replay-skip read is guarded — the entry is "
            + "neither re-invoked nor recorded failed")
    void sequentialEntryReadStandsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // anchorSeq 6 → first entry's block base is 6*1000 + 1*1000.
        plantFutureEvent(7000);

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(0, invocations.get());
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "recording a step failure here would durably contradict whatever the "
                        + "upgraded node's history actually says");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Stand-down: a parallel branch's replay-skip read is guarded")
    void parallelBranchReadStandsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("branch-A", invocations::incrementAndGet);
        // compensate() consumes 6, executeParallel's fork point consumes 7,
        // so the first branch's base is 7*1000 + 1*1000.
        plantFutureEvent(8000);

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));

        assertEquals(0, invocations.get());
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Stand-down: the parallel-compensation fork marker's replay read is guarded")
    void parallelForkMarkerReadStandsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("branch-A", invocations::incrementAndGet);
        plantFutureEvent(7);   // ctx seeded at 5: COMPENSATION_STARTED consumes 6, the fork marker 7

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));

        assertEquals(0, invocations.get());
        assertEquals(0, countEvents(EventType.SIDE_EFFECT), "no live append may follow an unreadable read");
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Catch ordering: a stand-down raised INSIDE a sequential compensation action is "
            + "rethrown, never recorded as COMPENSATION_STEP_FAILED")
    void sequentialActionStandDownIsRethrown() {
        var stack = new CompensationStack();
        stack.push("nested-replay-read", () -> {
            throw standDown();
        });

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "a compensation action's nested replay read can stand down — the loop must "
                        + "let it out, exactly as it does for a shutdown");

        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "the step did not fail; this node simply cannot read the history");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED),
                "a stand-down must not finalise the compensation phase");
    }

    @Test
    @DisplayName("Catch ordering: a stand-down raised INSIDE a parallel compensation branch is "
            + "rethrown from the outcome loop, never recorded as COMPENSATION_STEP_FAILED")
    void parallelBranchStandDownIsRethrown() {
        var completed = new AtomicInteger();
        var stack = new CompensationStack();
        // Pushed first → unwinds second; the branch that meets unreadable history.
        stack.push("standing-down-branch", () -> {
            throw standDown();
        });
        stack.push("healthy-branch", completed::incrementAndGet);

        org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true),
                "the outcome loop collects branch throwables with catch (Throwable) — it "
                        + "must recognise a control-flow signal before recording failures");

        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "no branch failed; one of them could not read the history");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Ranking (M2): a control-flow signal in a LATER parallel branch outranks a stale-run "
            + "signal in an earlier one — mirrors parallel()'s R27 pre-scan")
    void parallelBranchControlFlowOutranksEarlierStaleRun() {
        var stack = new CompensationStack();
        // Pushed first → unwinds second: branch 1 is shut down mid-unwind.
        stack.push("shutdown-branch", () -> {
            throw new ExecutorShutdownException("deploying");
        });
        // Pushed last → unwinds first: branch 0 finds the row finalised elsewhere.
        stack.push("stale-branch", () -> {
            throw new io.b2mash.maestro.core.exception.StaleRunException(
                    "wf", WorkflowStatus.COMPLETED, "test");
        });

        org.junit.jupiter.api.Assertions.assertThrows(ExecutorShutdownException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true),
                "control-flow first across ALL branches, then stale-run, then index order");
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
    }

    @Test
    @DisplayName("Ranking (M2): a stale-run signal in a LATER parallel branch outranks a plain failure in "
            + "an earlier one — nothing recorded as COMPENSATION_STEP_FAILED")
    void parallelBranchStaleRunOutranksEarlierPlainFailure() {
        var stack = new CompensationStack();
        stack.push("stale-branch", () -> {
            throw new io.b2mash.maestro.core.exception.DuplicateEventException(instance.id(), 9);
        });
        stack.push("failing-branch", () -> {
            throw new IllegalStateException("refund rejected");
        });

        org.junit.jupiter.api.Assertions.assertThrows(
                io.b2mash.maestro.core.exception.DuplicateEventException.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "a stale attempt records no failure — the winner owns the compensation progress");
    }

    @Test
    @DisplayName("Ranking (round 2, R33): a node error in a LATER parallel compensation branch outranks a plain "
            + "failure in an earlier one — rethrown raw, nothing recorded as COMPENSATION_STEP_FAILED")
    void parallelBranchNodeErrorOutranksEarlierPlainFailure() {
        var nodeError = new NoClassDefFoundError("com/acme/Missing");
        var stack = new CompensationStack();
        // Pushed first → unwinds second: branch 1 hits the node error.
        stack.push("node-error-branch", () -> {
            throw nodeError;
        });
        // Pushed last → unwinds first: branch 0 fails plainly.
        stack.push("failing-branch", () -> {
            throw new IllegalStateException("refund rejected");
        });

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(NoClassDefFoundError.class,
                () -> sagaManager.compensate(ctx, instance, stack, true));
        org.junit.jupiter.api.Assertions.assertSame(nodeError, thrown);
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED),
                "a node error is this node's problem — no reversal failed, so none may be recorded");
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    // ── Replay drift during compensation (C10, ruling R4) ──────────────────
    //
    // Every guard slot read here used to treat ANY present event as "this
    // entry already ran": a renamed or reordered compensation entry skipped the
    // wrong entry (never compensating it), a fork marker recording a different
    // entry count was reused as-is, and flipping @Saga(parallelCompensation)
    // between runs re-invoked work that had already been reversed. Each now
    // stands down as NONDETERMINISTIC_HISTORY before any action runs or any
    // event is appended. ctx is seeded at 5, so COMPENSATION_STARTED is 6.

    private void plant(int seq, EventType type, String stepName, String payloadJson) {
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), seq, type, stepName,
                payloadJson == null ? null : new ObjectMapper().readTree(payloadJson), Instant.now()));
    }

    private UnknownWorkflowHistoryException assertDriftStandDown(CompensationStack stack, boolean parallel,
                                                                 String expectedDetail) {
        var eventsBefore = store.getEvents(instance.id()).size();
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(UnknownWorkflowHistoryException.class,
                () -> sagaManager.compensate(ctx, instance, stack, parallel));
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, thrown.kind()),
                () -> assertEquals(expectedDetail, thrown.detail()),
                () -> assertEquals(eventsBefore, store.getEvents(instance.id()).size(),
                        "a drift stand-down appends nothing"));
        return thrown;
    }

    @Test
    @DisplayName("Drift: a sequential entry whose guard slot records a different step name stands "
            + "down — nothing invoked, nothing appended")
    void sequentialRenamedEntry_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7000, EventType.COMPENSATION_STEP_COMPLETED, "step-A-old", null);

        assertDriftStandDown(stack, false,
                "seq=7000 expected=step-A stored=step-A-old type=COMPENSATION_STEP_COMPLETED");
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("Drift: a parallel branch whose guard slot records a different step name stands "
            + "down before any branch is spawned")
    void parallelRenamedBranch_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("branch-B", invocations::incrementAndGet);
        stack.push("branch-A", invocations::incrementAndGet);   // unwinds first → branch 0
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7, EventType.SIDE_EFFECT, "$maestro:parallel-compensation", "{\"branchCount\":2}");
        plant(9000, EventType.COMPENSATION_STEP_COMPLETED, "branch-B-old", null);   // branch 1's slot

        assertDriftStandDown(stack, true,
                "seq=9000 expected=branch-B stored=branch-B-old type=COMPENSATION_STEP_COMPLETED");
        assertEquals(0, invocations.get(), "branch 0's slot is empty, but it must not be spawned either");
    }

    @Test
    @DisplayName("Drift: a parallel-compensation fork marker recording a different entry count stands down")
    void parallelForkMarkerEntryCountMismatch_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("branch-B", invocations::incrementAndGet);
        stack.push("branch-A", invocations::incrementAndGet);
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7, EventType.SIDE_EFFECT, "$maestro:parallel-compensation", "{\"branchCount\":3}");

        assertDriftStandDown(stack, true, "seq=7 expected=$maestro:parallel-compensation branches=2 "
                + "stored=$maestro:parallel-compensation branches=3");
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("Drift: flipping parallelCompensation false→true stands down — the entry the "
            + "sequential run already reversed is not re-invoked")
    void sequentialHistoryRecoveredInParallelMode_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // A sequential run reversed step-A at its entry block, then crashed
        // before COMPENSATION_COMPLETED.
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7000, EventType.COMPENSATION_STEP_COMPLETED, "step-A", null);

        assertDriftStandDown(stack, true, "seq=7000 expected=$maestro:parallel-compensation "
                + "stored=step-A type=COMPENSATION_STEP_COMPLETED");
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("Drift: flipping parallelCompensation true→false stands down at the recorded fork "
            + "marker — no entry runs")
    void parallelHistoryRecoveredInSequentialMode_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // A parallel run recorded its fork marker, then crashed before the branch finished.
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7, EventType.SIDE_EFFECT, "$maestro:parallel-compensation", "{\"branchCount\":1}");

        assertDriftStandDown(stack, false, "seq=7 expected=$maestro:sequential-compensation "
                + "stored=$maestro:parallel-compensation type=SIDE_EFFECT");
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("Drift (R19): a foreign event at the COMPENSATION_STARTED anchor stands down — not "
            + "misread as 'already recorded'; nothing invoked, nothing appended")
    void foreignEventAtCompensationStartedAnchor_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // The code now fails one step earlier than the recorded run did: the
        // anchor slot holds that run's next activity, not a compensation marker.
        plant(6, EventType.ACTIVITY_COMPLETED, "chargeCard", "\"ok\"");
        var before = store.getInstance(instance.workflowId()).orElseThrow();

        assertDriftStandDown(stack, false,
                "seq=6 expected=$maestro:compensation stored=chargeCard type=ACTIVITY_COMPLETED");
        var after = store.getInstance(instance.workflowId()).orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, after.status(),
                "a stand-down writes nothing — the anchor is read BEFORE the COMPENSATING transition, "
                        + "or the instance is stuck in a state the machine only exits to FAILED");
        assertEquals(before.version(), after.version(), "no status write, no version bump");
        assertEquals(0, invocations.get(), "no compensation action may run on drifted history");
        assertEquals(0, countEvents(EventType.COMPENSATION_STEP_COMPLETED));
        assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("Drift (R19): a foreign event at the COMPENSATION_COMPLETED slot stands down — the "
            + "phase is not finalised on another operation's record")
    void foreignEventAtCompensationCompletedSlot_standsDown() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        // ctx at 5: STARTED=6, step-A's block=7000, then the counter advances to
        // nextParentSeq(6, 1)=8000 and COMPENSATION_COMPLETED consumes 8001.
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7000, EventType.COMPENSATION_STEP_COMPLETED, "step-A", null);
        plant(8001, EventType.ACTIVITY_COMPLETED, "chargeCard", "\"ok\"");

        assertDriftStandDown(stack, false,
                "seq=8001 expected=$maestro:compensation stored=chargeCard type=ACTIVITY_COMPLETED");
        assertEquals(0, invocations.get(), "step-A replays from its record, never re-invoked");
    }

    @Test
    @DisplayName("R19: the recorded COMPENSATION_STARTED/COMPLETED markers still replay-skip — a "
            + "fully recorded phase appends nothing on recovery")
    void recordedCompensationMarkers_replayWithoutAppending() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        plant(6, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        plant(7000, EventType.COMPENSATION_STEP_COMPLETED, "step-A", null);
        plant(8001, EventType.COMPENSATION_COMPLETED, "$maestro:compensation", null);
        var eventsBefore = store.getEvents(instance.id()).size();

        sagaManager.compensate(ctx, instance, stack, false);

        assertEquals(0, invocations.get());
        assertEquals(eventsBefore, store.getEvents(instance.id()).size());
    }

    @Test
    @DisplayName("Compensation whose block arithmetic would overflow throws before transitioning or running anything")
    void compensationBlockOverflow_failsBeforeAnyActionRuns() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        stack.push("step-B", invocations::incrementAndGet);
        // anchor 2_147_481; (anchor*1000 + 3*1000) > Integer.MAX_VALUE
        var highCtx = new WorkflowContext(instance.id(), "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service", 2_147_480, false);

        var e = org.junit.jupiter.api.Assertions.assertThrows(SequenceSpaceExhaustedException.class,
                () -> sagaManager.compensate(highCtx, instance, stack, false));
        assertEquals(2_147_481, e.parentSeq());
        assertEquals(0, invocations.get());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("test-workflow").orElseThrow().status(),
                "pre-check runs before transitionToCompensating");
        assertEquals(0, countEvents(EventType.COMPENSATION_STARTED));
    }

    @Test
    @DisplayName("Boundary: anchor 2_147_480 with 2 entries fits sequentially but the parallel arm's extra fork slot overflows")
    void compensationPreCheck_sequentialFitsAtBoundary() {
        var stack = new CompensationStack();
        stack.push("step-A", () -> { });
        stack.push("step-B", () -> { });
        var boundaryCtx = new WorkflowContext(instance.id(), "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service", 2_147_479, false);

        sagaManager.compensate(boundaryCtx, instance, stack, false);

        assertEquals(1, countEvents(EventType.COMPENSATION_STARTED));
    }

    @Test
    @DisplayName("Boundary: the same anchor and stack with parallelCompensation=true throws before COMPENSATION_STARTED")
    void compensationPreCheck_parallelArmOverflowsAtBoundary() {
        var invocations = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", invocations::incrementAndGet);
        stack.push("step-B", invocations::incrementAndGet);
        var boundaryCtx = new WorkflowContext(instance.id(), "test-workflow", instance.runId(),
                "TestWorkflow", "default", "test-service", 2_147_479, false);

        var e = org.junit.jupiter.api.Assertions.assertThrows(SequenceSpaceExhaustedException.class,
                () -> sagaManager.compensate(boundaryCtx, instance, stack, true));
        assertEquals(2_147_481, e.parentSeq());
        assertEquals(0, invocations.get());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("test-workflow").orElseThrow().status());
        assertEquals(0, countEvents(EventType.COMPENSATION_STARTED));
    }

    private long countEvents(EventType type) {
        return store.getEvents(instance.id()).stream().filter(e -> e.eventType() == type).count();
    }

    // ── In-memory store ───────────────────────────────────────────────

    private static class InMemoryStore implements WorkflowStore {
        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            return instance;
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
        }

        @Override public void appendEvent(WorkflowEvent event) {
            // Mirrors the real store's unique index (workflow_instance_id, sequence_number).
            boolean duplicate = events.stream().anyMatch(e ->
                    e.workflowInstanceId().equals(event.workflowInstanceId())
                            && e.sequenceNumber() == event.sequenceNumber());
            if (duplicate) {
                throw new io.b2mash.maestro.core.exception.DuplicateEventException(
                        event.workflowInstanceId(), event.sequenceNumber());
            }
            events.add(event);
        }

        @Override
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            // Test fixture: no CAS and one writer per instance, so composing the two
            // writes is atomic enough here. Event first, so a duplicate writes nothing.
            appendEvent(terminalEvent);
            updateInstance(terminal);
            return true;
        }

        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == seq)
                    .findFirst();
        }

        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override public int deleteFailureEvents(UUID instanceId) {
            return removeFailureEvents(events, instanceId);
        }

        @Override public void saveSignal(WorkflowSignal signal) {}
        @Override public List<WorkflowSignal> getUnconsumedSignals(String wfId, String name) { return List.of(); }
        @Override public boolean markSignalConsumed(UUID signalId) { return true; }
        @Override public void adoptOrphanedSignals(String wfId, UUID instanceId) {}
        @Override public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) { return 0; }
        @Override public void saveTimer(WorkflowTimer timer) {}
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { return List.of(); }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) { return Optional.empty(); }
        @Override public boolean markTimerFired(UUID timerId) { return false; }
        @Override public boolean markTimerCancelled(UUID timerId) { return false; }
    }

    // ── Recording messaging ───────────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {
        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override public void publishSignal(String serviceName, SignalMessage message) {}
        @Override public void publishLifecycleEvent(WorkflowLifecycleEvent event) { events.add(event); }
        @Override public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
