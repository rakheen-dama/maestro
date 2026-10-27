package io.b2mash.maestro.core.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.BranchSequenceExhaustedException;
import io.b2mash.maestro.core.model.EventType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins C3: a parallel branch may take at most 999 memoized steps; the 1000th throws. */
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
}
