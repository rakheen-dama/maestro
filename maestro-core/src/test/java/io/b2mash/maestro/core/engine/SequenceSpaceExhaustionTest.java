package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins C4 at the engine level: a fork that cannot be sequenced is a typed,
 * loud failure that consumes no sequence, and a compensation phase whose block
 * arithmetic would overflow never runs anything at a wrapped sequence — the
 * workflow still finishes {@code FAILED} instead of becoming a zombie.
 */
@DisplayName("Sequence-space exhaustion in parallel() and saga compensation")
class SequenceSpaceExhaustionTest extends ParallelSagaTestSupport {

    private static List<Callable<String>> twoBranches() {
        return List.<Callable<String>>of(() -> "a", () -> "b");
    }

    /** Registers one compensation, then forks three times — the third fork cannot be sequenced. */
    public static class TripleForkWorkflow {

        private final AtomicInteger undone;

        public TripleForkWorkflow(AtomicInteger undone) {
            this.undone = undone;
        }

        public String run(String input) {
            var workflow = WorkflowContext.current();
            workflow.addCompensation("undo", undone::incrementAndGet);
            workflow.parallel(twoBranches());
            workflow.parallel(twoBranches());
            workflow.parallel(twoBranches());
            return input;
        }
    }

    @Test
    @DisplayName("the third sequential parallel() fails with SequenceSpaceExhaustedException, consuming no sequence and appending nothing")
    void thirdSequentialFork_failsBeforeConsumingASequence() {
        inScope(() -> {
            ops.parallel(twoBranches());
            ops.parallel(twoBranches());
        });
        assertEquals(4_004_000, ctx.currentSequence(), "1 → 4000 → 4001*1000+3000");

        var e = assertThrows(SequenceSpaceExhaustedException.class, () -> inScope(() -> ops.parallel(twoBranches())));
        assertAll(() -> assertEquals(4_004_001, e.parentSeq()), () -> assertEquals(2, e.branchCount()));
        assertEquals(4_004_000, ctx.currentSequence(), "the pre-check must not consume the fork's sequence");
        assertTrue(store.getEventBySequence(instance.id(), 4_004_001).isEmpty(), "no fork marker for a fork that never happened");
    }

    @Test
    @DisplayName("when compensation block arithmetic would overflow, the workflow still finishes FAILED — no zombie, no compensation run at a wrapped sequence")
    void compensationOverflow_finishesFailedWithoutRunningCompensations() throws Exception {
        var undone = new AtomicInteger();
        var workflow = new TripleForkWorkflow(undone);
        var executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
        try {
            executor.startWorkflow("triple-fork", "TripleForkWorkflow", "default", "in", () -> workflow,
                    TripleForkWorkflow.class.getMethod("run", String.class));
            TestTerminalWait.awaitTerminal(store, "triple-fork", Duration.ofSeconds(5));
            var tripleFork = store.getInstance("triple-fork").orElseThrow();
            assertEquals(WorkflowStatus.FAILED, tripleFork.status());
            assertEquals(SequenceSpaceExhaustedException.class.getName(),
                    tripleFork.output().get("exceptionType").stringValue());
            assertEquals(0, undone.get(), "no compensation may run at an int-wrapped sequence");
            assertEquals(2, count(tripleFork.id(), EventType.SIDE_EFFECT, "$maestro:parallel"));
            assertEquals(0, count(tripleFork.id(), EventType.COMPENSATION_STARTED, null));
            assertEquals(1, count(tripleFork.id(), EventType.WORKFLOW_FAILED, null));
        } finally {
            executor.shutdown();
        }
    }

    /** Counts events on the executor-created instance (the base class counts on its own fixture instance). */
    private long count(UUID instanceId, EventType type, @org.jspecify.annotations.Nullable String stepName) {
        return store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == type && (stepName == null || stepName.equals(e.stepName())))
                .count();
    }
}
