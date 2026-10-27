package io.b2mash.maestro.core.context;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.b2mash.maestro.core.exception.BranchSequenceExhaustedException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowContextBoundTest {

    private WorkflowContext parent;
    private WorkflowContext branch;

    @BeforeEach
    void setUp() {
        parent = new WorkflowContext(UUID.randomUUID(), "wf", UUID.randomUUID(), "T", "q", "svc", 1, false);
        branch = WorkflowContext.bounded(parent, 0, 2000, 3000, null);
    }

    @Test
    void boundedBranch_hands_out_exactly_999_slots() {
        for (int expected = 2001; expected <= 2999; expected++) {
            assertEquals(expected, branch.nextSequence());
        }
        var e = assertThrows(BranchSequenceExhaustedException.class, branch::nextSequence);
        assertAll(
                () -> assertEquals("wf", e.workflowId()),
                () -> assertEquals(0, e.branchIndex()),
                () -> assertEquals(2000, e.baseSeq()),
                () -> assertEquals(3000, e.limit()));
        assertEquals(2999, branch.currentSequence(), "a refused step must not move the counter");
    }

    @Test
    void boundedBranch_copiesIdentityAndReplayFlag() {
        assertAll(
                () -> assertEquals(parent.workflowInstanceId(), branch.workflowInstanceId()),
                () -> assertEquals(parent.runId(), branch.runId()),
                () -> assertEquals(parent.isReplaying(), branch.isReplaying()),
                () -> assertEquals(2000, branch.currentSequence()));
    }

    @Test
    void counterRebasedByANestedJoin_isNotBounded() {
        branch.setSequence(2_005_000);
        assertEquals(2_005_001, branch.nextSequence());
    }

    /**
     * The four-argument overload inherits the parent's operations. WorkflowOperations is sealed,
     * so the "has operations" half is pinned at engine level ({@code CompensationWithParallelTest},
     * whose compensation entry calls {@code parallel()} through the inherited delegate); here we
     * pin the bound and the "parent has none" half.
     */
    @Test
    void fourArgBounded_inheritsTheParentsOperationsAndIsBounded() {
        var child = WorkflowContext.bounded(parent, 1, 3000, 4000);

        assertEquals(3000, child.currentSequence());
        assertThrows(IllegalStateException.class, child::randomUUID,
                "a parent without operations yields a child without operations");
        child.setSequence(3999);
        var e = assertThrows(BranchSequenceExhaustedException.class, child::nextSequence);
        assertEquals(1, e.branchIndex());
    }

    @Test
    void unboundedContext_neverThrows() {
        parent.setSequence(2999);
        assertEquals(3000, parent.nextSequence());
    }
}
