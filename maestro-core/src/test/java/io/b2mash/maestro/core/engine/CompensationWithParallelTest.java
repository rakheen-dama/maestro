package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
