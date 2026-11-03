package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@code parallel()} called from inside a saga compensation {@link Runnable}
 * (the only workflow-author code that runs on the workflow thread during compensation).
 */
class CompensationWithParallelTest extends ParallelSagaTestSupport {

    private void forwardPath(AtomicInteger fanOuts) {
        ops.currentTime();
        ops.addCompensation("undoFanOut", () -> {
            fanOuts.incrementAndGet();
            WorkflowContext.current().parallel(List.of(
                    () -> WorkflowContext.current().randomUUID(),
                    () -> WorkflowContext.current().randomUUID()));
        });
    }

    @Test
    @DisplayName("a compensation that fans out with parallel() lands in the entry's own block, never in a forward slot, and runs once across a replay")
    void compensationThatForks_runsOnceInItsOwnBlock() {
        var fanOuts = new AtomicInteger();
        inScope(() -> forwardPath(fanOuts));
        inScope(() -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(1, fanOuts.get());
        // Sorted numerically: the entry's block (3000-3001) and the completion marker (4001)
        // sit below the branch blocks (3_002_001, 3_003_001), which live at p*1000 + ...
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:currentTime",
                "2:COMPENSATION_STARTED:$maestro:compensation",
                "3000:COMPENSATION_STEP_COMPLETED:undoFanOut",
                "3001:SIDE_EFFECT:$maestro:parallel",
                "4001:COMPENSATION_COMPLETED:$maestro:compensation",
                "3002001:SIDE_EFFECT:$maestro:randomUUID",
                "3003001:SIDE_EFFECT:$maestro:randomUUID"),
                eventLogSortedBySequence());

        var before = eventLogSortedBySequence();
        ctx = newContext(0, true, ops);
        inScope(() -> forwardPath(fanOuts));
        inScope(() -> sagaManager.compensate(ctx, instance, stack, false));
        assertEquals(1, fanOuts.get(), "the durable COMPENSATION_STEP_COMPLETED at 3000 skips the entry");
        assertEquals(before, eventLogSortedBySequence());
    }

    /**
     * Forward fork, then an ordinary compensation and one that forks (registered last, so it
     * unwinds first). The forward fork leaves the parent at 4000, so the compensation phase is
     * already the second parent-line level: anchor 4001, entry block 4,002,000, and the nested
     * fork at 4,002,001 would need 4,002,001 * 1000 — past {@code Integer.MAX_VALUE}. The
     * compensation pre-check (anchor 4001, two entries → 4,004,000) passes; the overflow fails
     * that entry only.
     */
    private void forkAfterForwardFork(AtomicInteger ordinary) {
        ops.parallel(List.of(
                () -> WorkflowContext.current().randomUUID(),
                () -> WorkflowContext.current().randomUUID()));
        ops.addCompensation("undoOrdinary", ordinary::incrementAndGet);
        ops.addCompensation("undoFanOut", () -> WorkflowContext.current().parallel(List.of(
                () -> WorkflowContext.current().randomUUID(),
                () -> WorkflowContext.current().randomUUID())));
    }

    @Test
    @DisplayName("a compensation that forks after a forward parallel() fails that step with SequenceSpaceExhaustedException; the remaining entry still runs")
    void forkInsideCompensationAfterForwardFork_failsThatStepOnly() {
        var ordinary = new AtomicInteger();
        inScope(() -> forkAfterForwardFork(ordinary));

        assertThrows(CompensationException.class,
                () -> inScope(() -> sagaManager.compensate(ctx, instance, stack, false)));

        assertEquals(1, ordinary.get(), "the remaining entry's action ran");
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "2001:SIDE_EFFECT:$maestro:randomUUID",
                "3001:SIDE_EFFECT:$maestro:randomUUID",
                "4001:COMPENSATION_STARTED:$maestro:compensation",
                "4002000:COMPENSATION_STEP_FAILED:undoFanOut",
                "4003000:COMPENSATION_STEP_COMPLETED:undoOrdinary",
                "4004001:COMPENSATION_COMPLETED:$maestro:compensation"),
                eventLogSortedBySequence());
        assertEquals(SequenceSpaceExhaustedException.class.getName(),
                store.getEventBySequence(instance.id(), 4_002_000).orElseThrow()
                        .payload().get("exceptionType").asText());
    }

    @Test
    @DisplayName("under parallel compensation a compensation branch cannot fork at all: it has no operations, so parallel() fails that step with IllegalStateException")
    void forkInsideParallelCompensationBranch_failsWithNoOperations() {
        var ordinary = new AtomicInteger();
        inScope(() -> forkAfterForwardFork(ordinary));

        assertThrows(CompensationException.class,
                () -> inScope(() -> sagaManager.compensate(ctx, instance, stack, true)));

        assertEquals(1, ordinary.get());
        // started 4001, fork marker 4002, branch blocks 4,003,000 and 4,004,000, completion at 4,005,001
        var failed = store.getEventBySequence(instance.id(), 4_003_000).orElseThrow();
        assertEquals("undoFanOut", failed.stepName());
        assertEquals(IllegalStateException.class.getName(), failed.payload().get("exceptionType").asText());
        assertEquals("undoOrdinary", store.getEventBySequence(instance.id(), 4_004_000).orElseThrow().stepName());
    }
}
