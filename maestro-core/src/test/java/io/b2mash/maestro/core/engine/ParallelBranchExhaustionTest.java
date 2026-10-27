package io.b2mash.maestro.core.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.BranchSequenceExhaustedException;
import io.b2mash.maestro.core.model.EventType;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins C3: a parallel branch may take at most 999 sequence slots; the 1000th throws. Also pins
 * that the bound leaves a nested {@code parallel()} inside a branch working, with exact numbering.
 */
@DisplayName("parallel() branch sequence-block exhaustion")
class ParallelBranchExhaustionTest extends ParallelSagaTestSupport {

    @Test
    @DisplayName("the 1000th step of a branch fails the fork with BranchSequenceExhaustedException; the sibling's slots are untouched and fully persisted")
    void branchExceedingItsBlock_failsWithoutTouchingSiblings() {
        var e = assertThrows(BranchSequenceExhaustedException.class, () -> inScope(() -> ops.parallel(List.of(
                () -> {
                    for (int i = 0; i < 1000; i++) {
                        WorkflowContext.current().randomUUID();
                    }
                    return "never";
                },
                () -> WorkflowContext.current().randomUUID()))));
        assertAll(
                () -> assertEquals(0, e.branchIndex()),
                () -> assertEquals(2000, e.baseSeq()),
                () -> assertEquals(3000, e.limit()));
        assertTrue(store.getEventBySequence(instance.id(), 2999).isPresent(), "999 steps fit");
        assertTrue(store.getEventBySequence(instance.id(), 3000).isEmpty(),
                "the sibling's base is never written by branch 0");
        assertEquals(EventType.SIDE_EFFECT, store.getEventBySequence(instance.id(), 3001).orElseThrow().eventType(),
                "parallel() joins every branch before rethrowing, so branch 1's own step is durable");
        assertEquals(1000, count(EventType.SIDE_EFFECT, "$maestro:randomUUID"),
                "999 from branch 0 + 1 from branch 1");
        assertEquals(4000, ctx.currentSequence(), "Task 1's finally still advances the parent");
    }

    @Test
    @DisplayName("999 steps in a branch succeed")
    void branchWith999Steps_succeeds() {
        inScope(() -> ops.parallel(List.of(
                () -> {
                    for (int i = 0; i < 999; i++) {
                        WorkflowContext.current().randomUUID();
                    }
                    return "ok";
                },
                () -> "ok")));
        assertEquals(999, count(EventType.SIDE_EFFECT, "$maestro:randomUUID"));
        assertTrue(store.getEventBySequence(instance.id(), 2999).isPresent());
        assertTrue(store.getEventBySequence(instance.id(), 3000).isEmpty());
    }

    /** Branch 0 forks two ways, then takes one more step after its inner join; branch 1 takes one step. */
    private List<String> nestedFork() {
        return ops.parallel(List.<Callable<String>>of(
                () -> {
                    var inner = WorkflowContext.current().parallel(List.of(
                            () -> WorkflowContext.current().randomUUID(),
                            () -> WorkflowContext.current().randomUUID()));
                    return inner.get(0) + "|" + inner.get(1) + "|" + WorkflowContext.current().randomUUID();
                },
                () -> WorkflowContext.current().randomUUID()));
    }

    @Test
    @DisplayName("a nested parallel() inside a branch completes with exact numbering: inner fork at 2001, inner blocks at 2_002_000 / 2_003_000, parent at 4000 after the join, and replays identically")
    void nestedParallel_numbersExactlyAndReplays() {
        var firstRun = new AtomicReference<List<String>>();
        inScope(() -> firstRun.set(nestedFork()));

        // Sorted numerically. Branch 0's base is 2000, so its first slot (2001) is the inner
        // fork; inner branch i's base is 2001*1000 + (i+1)*1000. After the inner join branch 0's
        // counter is rebased to 2001*1000 + 3*1000 = 2_004_000, so its post-join step is 2_004_001.
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "2001:SIDE_EFFECT:$maestro:parallel",
                "3001:SIDE_EFFECT:$maestro:randomUUID",
                "2002001:SIDE_EFFECT:$maestro:randomUUID",
                "2003001:SIDE_EFFECT:$maestro:randomUUID",
                "2004001:SIDE_EFFECT:$maestro:randomUUID"),
                eventLogSortedBySequence());
        assertEquals(4000, ctx.currentSequence(), "1*1000 + 3*1000 — the inner fork never moves the parent");
        assertEquals(2, firstRun.get().size());

        var before = eventLogSortedBySequence();
        ctx = newContext(0, true, ops);
        var replay = new AtomicReference<List<String>>();
        inScope(() -> replay.set(nestedFork()));
        assertEquals(firstRun.get(), replay.get(), "every slot replays its recorded value");
        assertEquals(before, eventLogSortedBySequence());
        assertEquals(4000, ctx.currentSequence());
    }
}
