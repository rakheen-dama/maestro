package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static io.b2mash.maestro.core.model.EventType.ACTIVITY_COMPLETED;
import static io.b2mash.maestro.core.model.EventType.ACTIVITY_FAILED;
import static io.b2mash.maestro.core.model.EventType.SIDE_EFFECT;
import static io.b2mash.maestro.core.model.EventType.WORKFLOW_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3 Task 2 — {@link LaneTree} rebuilds the lanes of the sequence space from
 * a history (D-2): the root lane, each fork's branch lanes and every lane's
 * continuation after its forks, stopping at the first empty slot. An event the
 * walk never reaches is unplaced; {@code after(F)} is the propagation path of a
 * placed event.
 */
@DisplayName("LaneTree rebuilds the sequence-space lanes of a history")
class LaneTreeTest {

    private static final UUID INSTANCE = UUID.randomUUID();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void theRootLaneIsContiguousUntilItsFirstGap() {
        var t = LaneTree.of(List.of(ev(1, ACTIVITY_COMPLETED, "a"), ev(2, ACTIVITY_FAILED, "b"), ev(4, ACTIVITY_COMPLETED, "c")));
        assertTrue(t.isPlaced(2));
        assertEquals(new TreeSet<>(Set.of(4)), t.unplaced());
    }

    @Test
    void aForkContinuesItsLaneAfterItsBranchBlocks() {
        var t = LaneTree.of(List.of(fork(1, 2), ev(2001, ACTIVITY_COMPLETED, "x"), ev(3001, ACTIVITY_COMPLETED, "y"),
                ev(4001, ACTIVITY_COMPLETED, "z")));
        assertEquals(Set.of(), t.unplaced());
        assertEquals(new TreeSet<>(Set.of(4001)), t.after(2001));         // sibling 3001 excluded
    }

    @Test
    void anEventInABranchBlockAfterAGapIsUnplaced() {
        var t = LaneTree.of(List.of(fork(1, 2), ev(2999, ACTIVITY_COMPLETED, "x"), ev(3000, ACTIVITY_COMPLETED, "y")));
        assertFalse(t.isPlaced(2999));                                     // 2001 is empty: branch 0's walk stops there
        assertEquals(new TreeSet<>(Set.of(2999, 3000)), t.unplaced());     // 3000 is a branch base: never handed out
    }

    @Test
    void afterClimbsEveryAncestorAndKeepsEverySibling() {
        // root fork at 1 (n=2); branch 0 forks at 2001 (n=2): sub-branches 2002001.., 2003001..; branch 0 continues at 2004001
        var t = LaneTree.of(List.of(fork(1, 2), fork(2001, 2), ev(2002001, ACTIVITY_FAILED, "f"), ev(2002002, ACTIVITY_COMPLETED, "alert"),
                ev(2003001, ACTIVITY_COMPLETED, "sib"), ev(2004001, ACTIVITY_COMPLETED, "b0tail"), ev(3001, ACTIVITY_COMPLETED, "b1"),
                ev(4001, ACTIVITY_COMPLETED, "roottail"), ev(4002, WORKFLOW_FAILED, null)));
        assertEquals(new TreeSet<>(Set.of(2002002, 2004001, 4001, 4002)), t.after(2002001));
    }

    @Test
    void afterIncludesTheWholeSubtreeOfAForkInTheSuffix() {
        var t = LaneTree.of(List.of(ev(1, ACTIVITY_FAILED, "p"), fork(2, 2), ev(3001, ACTIVITY_COMPLETED, "a1"),
                ev(4001, ACTIVITY_COMPLETED, "a2"), ev(5001, WORKFLOW_FAILED, null)));
        assertEquals(new TreeSet<>(Set.of(2, 3001, 4001, 5001)), t.after(1));
    }

    @Test
    void aWorkflowFailedAfterAGapIsNotUnplaced() {
        var t = LaneTree.of(List.of(ev(1, ACTIVITY_COMPLETED, "a"), ev(3, WORKFLOW_FAILED, null)));
        assertEquals(Set.of(), t.unplaced());
    }

    @Test
    void aForkMarkerWithoutABranchCountIsNotAFork() {
        var t = LaneTree.of(List.of(ev(1, SIDE_EFFECT, "$maestro:parallel"), ev(2001, ACTIVITY_COMPLETED, "x")));
        assertEquals(new TreeSet<>(Set.of(2001)), t.unplaced());
    }

    @Test
    void aSlotTwoLanesClaim_marksTheTreeAmbiguous() {
        var t = LaneTree.of(issue28Overlap());
        assertTrue(t.ambiguous(), "4002001 is both branch 1's continuation and the later fork's branch 0");
    }

    @Test
    void aTreeWithoutOverlap_isNotAmbiguous() {
        var t = LaneTree.of(List.of(fork(1, 2), ev(2001, ACTIVITY_COMPLETED, "x"), ev(3001, ACTIVITY_COMPLETED, "y"),
                ev(4001, ACTIVITY_COMPLETED, "z")));
        assertFalse(t.ambiguous());
    }

    @Test
    void aForkMarkerWhoseContinuationOverflowsIsNotAFork() {
        // 1 * 1000 + (2_147_483 + 1) * 1000 > Integer.MAX_VALUE
        var t = LaneTree.of(List.of(fork(1, 2_147_483), ev(2, ACTIVITY_FAILED, "p"), ev(2001, ACTIVITY_COMPLETED, "x")));
        assertTrue(t.isPlaced(2), "the marker is an ordinary slot: the root lane continues at 2");
        assertEquals(new TreeSet<>(Set.of(2001)), t.unplaced());
    }

    @Test
    void aForkMarkerWithTheLargestBranchCountIsNotAFork() {
        // branchCount + 1 overflows int
        var t = LaneTree.of(List.of(fork(1, Integer.MAX_VALUE), ev(2, ACTIVITY_FAILED, "p"),
                ev(2001, ACTIVITY_COMPLETED, "x")));
        assertTrue(t.isPlaced(2));
        assertEquals(new TreeSet<>(Set.of(2001)), t.unplaced());
    }

    /**
     * Issue 28's ×1000 overlap: root fork at 1 (n=2); branch 1 (3001..3999)
     * forks at its 999th slot, 3999 (n=2), so it continues at
     * {@code nextParentSeq(3999, 2) + 1 = 4002001}; the root continues at 4001
     * and forks there (n=2), whose branch 0 also starts at 4002001.
     */
    static List<WorkflowEvent> issue28Overlap() {
        var history = new ArrayList<WorkflowEvent>();
        history.add(fork(1, 2));
        history.add(ev(2001, ACTIVITY_COMPLETED, "b0"));
        for (int s = 3001; s <= 3998; s++) {
            history.add(ev(s, ACTIVITY_COMPLETED, "b1"));
        }
        history.add(fork(3999, 2));
        history.add(ev(4000001, ACTIVITY_COMPLETED, "b1sub0"));
        history.add(ev(4001001, ACTIVITY_COMPLETED, "b1sub1"));
        history.add(ev(4002001, ACTIVITY_COMPLETED, "shared"));
        history.add(fork(4001, 2));
        history.add(ev(4003001, ACTIVITY_COMPLETED, "r2b1"));
        history.add(ev(4004001, ACTIVITY_FAILED, "tail"));
        history.add(ev(4004002, WORKFLOW_FAILED, null));
        return history;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static WorkflowEvent ev(int seq, EventType type, @Nullable String step) {
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, seq, type, step, null, Instant.EPOCH);
    }

    private static WorkflowEvent fork(int seq, int n) {
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, seq, SIDE_EFFECT, "$maestro:parallel",
                MAPPER.readTree("{\"branchCount\":" + n + "}"), Instant.EPOCH);
    }
}
