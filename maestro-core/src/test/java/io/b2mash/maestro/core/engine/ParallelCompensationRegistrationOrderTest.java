package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.saga.CompensationEntry;
import io.b2mash.maestro.core.saga.CompensationStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compensations registered inside {@code parallel()} branches land on the
 * saga stack in <em>branch-index</em> order, never in finish order (plan 02,
 * Task 3 ruling on concern 1).
 *
 * <p>Finish order is a thread race, so a recovery replay need not reproduce
 * it. With finish-order registration, a saga that crashed mid-compensation
 * and was replayed with the branches finishing the other way round met
 * another entry's record at its guard slot — formerly skipping the wrong
 * entry, and since C10 standing down as {@code nondeterministic_history} on
 * code that never changed.
 */
@DisplayName("parallel() branch compensations register in branch-index order, not finish order")
class ParallelCompensationRegistrationOrderTest extends ParallelSagaTestSupport {

    @Test
    @DisplayName("whichever branch finishes first, the stack unwinds branch 1's entry before branch 0's")
    void registrationOrderIgnoresFinishOrder() {
        var branch1First = forkRegistering(true, () -> { }, () -> { });
        var branch0First = forkRegistering(false, () -> { }, () -> { });

        assertAll(
                () -> assertEquals(List.of("undo-b1", "undo-b0"), branch1First,
                        "branch 1 finished first, yet its entry is still the later registration"),
                () -> assertEquals(List.of("undo-b1", "undo-b0"), branch0First));
    }

    @Test
    @DisplayName("a nested parallel() buffers into its enclosing branch, in its own branch-index order")
    void nestedForkBuffersIntoTheEnclosingBranch() {
        var outerB1Done = new CountDownLatch(1);
        var innerB1Done = new CountDownLatch(1);
        Callable<String> outer0 = () -> {
            await(outerB1Done);   // outer branch 1 registers (and finishes) first
            WorkflowContext.current().parallel(List.<Callable<String>>of(
                    () -> {
                        await(innerB1Done);   // inner branch 1 finishes first too
                        WorkflowContext.current().addCompensation("undo-b0.0", () -> { });
                        return "b0.0";
                    },
                    () -> {
                        WorkflowContext.current().addCompensation("undo-b0.1", () -> { });
                        innerB1Done.countDown();
                        return "b0.1";
                    }));
            return "b0";
        };
        Callable<String> outer1 = () -> {
            WorkflowContext.current().addCompensation("undo-b1", () -> { });
            outerB1Done.countDown();
            return "b1";
        };

        inScope(() -> ops.parallel(List.of(outer0, outer1)));

        assertEquals(List.of("undo-b1", "undo-b0.1", "undo-b0.0"), names(stack));
    }

    @Test
    @DisplayName("a saga that crashed mid-compensation replays with the branches finishing the other way "
            + "round — no stand-down, nothing re-invoked, the interrupted entry runs once")
    void replayWithOppositeFinishOrder_doesNotStandDown() {
        // Run 1: branch 1 finishes first. The first compensation to run completes;
        // the second is interrupted by a (simulated) shutdown.
        var run1Invocations = new AtomicInteger();
        Runnable firstCompletesSecondCrashes = () -> {
            if (run1Invocations.incrementAndGet() == 2) {
                throw new ExecutorShutdownException("simulated shutdown mid-compensation");
            }
        };
        forkAndCompensate(true, firstCompletesSecondCrashes, firstCompletesSecondCrashes,
                ExecutorShutdownException.class);
        var firstEntry = store.getEventBySequence(instance.id(), 4_002_000).orElseThrow();
        assertEquals(EventType.COMPENSATION_STEP_COMPLETED, firstEntry.eventType());

        // Run 2 — the recovering node: fresh operations and stack, replaying,
        // and this time branch 0 finishes first.
        var undoB0 = new AtomicInteger();
        var undoB1 = new AtomicInteger();
        freshRun();
        forkAndCompensate(false, undoB0::incrementAndGet, undoB1::incrementAndGet, null);

        assertAll(
                () -> assertEquals("undo-b1", firstEntry.stepName(), "entry 0 is branch 1's, by index"),
                () -> assertEquals(0, undoB1.get(), "the completed entry replays, it is not re-invoked"),
                () -> assertEquals(1, undoB0.get(), "the interrupted entry runs once"),
                () -> assertEquals(1, count(EventType.COMPENSATION_COMPLETED)));
    }

    @Test
    @DisplayName("failure path: two registering branches + one failing branch — entries are pushed in "
            + "branch-index order, whatever the finish order")
    void failurePath_registersInBranchIndexOrder() {
        freshRun();
        var b1Registered = new CountDownLatch(1);
        var b0Registered = new CountDownLatch(1);
        Callable<String> b0 = () -> {
            await(b1Registered);                 // finishes after branch 1
            WorkflowContext.current().addCompensation("undo-b0", () -> { });
            b0Registered.countDown();
            return "b0";
        };
        Callable<String> b1 = () -> {
            WorkflowContext.current().addCompensation("undo-b1", () -> { });
            b1Registered.countDown();            // finishes first
            return "b1";
        };
        Callable<String> b2 = () -> {
            await(b0Registered);                 // fails last, after both registered
            throw new IllegalStateException("branch 2 failed");
        };

        var thrown = assertThrows(IllegalStateException.class,
                () -> inScope(() -> ops.parallel(List.of(b0, b1, b2))));

        assertAll(
                () -> assertEquals("branch 2 failed", thrown.getMessage()),
                // unwind pops LIFO: the last push (branch 1's) first, then branch 0's
                () -> assertEquals(List.of("undo-b1", "undo-b0"), names(stack),
                        "pushed b0 then b1 (branch-index order) although b1 finished first"));
    }

    @Test
    @DisplayName("a control-flow Error in a later branch wins over a plain failure in an earlier one")
    void controlFlowErrorWinsOverEarlierBranchFailure() {
        freshRun();
        var b1Done = new CountDownLatch(1);
        Callable<String> b0 = () -> {
            await(b1Done);
            throw new IllegalStateException("plain failure in branch 0");
        };
        Callable<String> b1 = () -> {
            b1Done.countDown();
            throw new ExecutorShutdownException("node stopping");
        };

        // CLAUDE.md: a broad collector checks MaestroControlFlowError first. Index
        // order would rethrow branch 0's exception and turn a deploy into a
        // recorded workflow failure with compensations.
        assertThrows(ExecutorShutdownException.class,
                () -> inScope(() -> ops.parallel(List.of(b0, b1))));
    }

    @Test
    @DisplayName("joined=false: the dropped branch registrations are named for the WARN, per branch")
    void droppedRegistrations_areNamedPerBranch() {
        var buffers = List.<List<DefaultWorkflowOperations.BufferedCompensation>>of(
                List.of(new DefaultWorkflowOperations.BufferedCompensation("undo-b0", () -> { })),
                List.of(),
                List.of(new DefaultWorkflowOperations.BufferedCompensation(null, () -> { }),
                        new DefaultWorkflowOperations.BufferedCompensation("undo-b2", () -> { })));

        assertEquals("branch 0: [undo-b0]; branch 2: [<unnamed>, undo-b2]",
                DefaultWorkflowOperations.describeDropped(buffers));
    }

    @Test
    @DisplayName("joined=false: an interrupted join registers nothing — the WARN is the only trace")
    void interruptedJoin_registersNothing() throws Exception {
        freshRun();
        var registered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Callable<String> b0 = () -> {
            WorkflowContext.current().addCompensation("undo-b0", () -> { });
            registered.countDown();
            await(release);
            return "b0";
        };
        Callable<String> b1 = () -> {
            await(release);
            return "b1";
        };
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var runner = Thread.ofVirtual().start(() -> {
            try {
                inScope(() -> ops.parallel(List.of(b0, b1)));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        await(registered);
        runner.interrupt();
        runner.join(5_000);
        release.countDown();

        assertAll(
                () -> assertEquals("Parallel execution interrupted", thrown.get().getMessage()),
                () -> assertEquals(List.of(), names(stack),
                        "an interrupted await leaves branches still writing — nothing is flushed"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Forks two branches that each register one compensation; the "late"
     * branch waits until the "early" one has registered.
     *
     * @return the step names the stack would unwind, in unwind order
     */
    private List<String> forkRegistering(boolean branch1First, Runnable undoB0, Runnable undoB1) {
        freshRun();
        inScope(() -> ops.parallel(branches(branch1First, undoB0, undoB1)));
        return names(stack);
    }

    private void forkAndCompensate(boolean branch1First, Runnable undoB0, Runnable undoB1,
                                   Class<? extends Throwable> expected) {
        inScope(() -> ops.parallel(branches(branch1First, undoB0, undoB1)));
        if (expected == null) {
            inScope(() -> sagaManager.compensate(ctx, instance, stack, false));
        } else {
            assertThrows(expected, () -> inScope(() -> sagaManager.compensate(ctx, instance, stack, false)));
        }
    }

    private static List<Callable<String>> branches(boolean branch1First, Runnable undoB0, Runnable undoB1) {
        var early = new CountDownLatch(1);
        Callable<String> b0 = () -> {
            if (branch1First) {
                await(early);
            }
            WorkflowContext.current().addCompensation("undo-b0", undoB0);
            if (!branch1First) {
                early.countDown();
            }
            return "b0";
        };
        Callable<String> b1 = () -> {
            if (!branch1First) {
                await(early);
            }
            WorkflowContext.current().addCompensation("undo-b1", undoB1);
            if (branch1First) {
                early.countDown();
            }
            return "b1";
        };
        return List.of(b0, b1);
    }

    /** A recovering node's world: fresh stack, operations and replaying context over the same store. */
    private void freshRun() {
        var parkingLot = new ParkingLot();
        stack = new CompensationStack();
        ops = new DefaultWorkflowOperations(store, null, null, serializer, parkingLot, stack,
                new SignalManager(store, null, null, serializer, parkingLot));
        ctx = newContext(0, true, ops);
    }

    private static List<String> names(CompensationStack stack) {
        return stack.unwind().stream().map(CompensationEntry::stepName).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
