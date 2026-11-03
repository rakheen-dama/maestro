package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure arithmetic of {@link SequenceSpace}: the ×1000 partition formula and the
 * overflow guard that replaces a silent {@code int} wrap (C4).
 */
@DisplayName("SequenceSpace block arithmetic")
class SequenceSpaceTest {

    @Test
    void branchBase_matchesTheDocumentedFormula() {
        assertEquals(2000, SequenceSpace.branchBase(1, 0));
        assertEquals(8000, SequenceSpace.branchBase(5, 2));
    }

    @Test
    void nextParentSeq_isOnePastTheLastBranchBlock() {
        assertEquals(5000, SequenceSpace.nextParentSeq("wf", 1, 3));
        assertEquals(5_005_000, SequenceSpace.nextParentSeq("wf", 5001, 3));
        assertEquals(2_147_483_000, SequenceSpace.nextParentSeq("wf", 2_147_479, 3), "largest 3-branch fork that fits");
    }

    @Test
    void nextParentSeq_throwsInsteadOfWrapping() {
        var e = assertThrows(SequenceSpaceExhaustedException.class,
                () -> SequenceSpace.nextParentSeq("wf", 2_147_480, 3));
        assertAll(() -> assertEquals("wf", e.workflowId()),
                () -> assertEquals(2_147_480, e.parentSeq()),
                () -> assertEquals(3, e.branchCount()));
        assertThrows(SequenceSpaceExhaustedException.class,
                () -> SequenceSpace.nextParentSeq("wf", 5_005_001, 3), "the third sequential fork of a workflow");
    }
}
