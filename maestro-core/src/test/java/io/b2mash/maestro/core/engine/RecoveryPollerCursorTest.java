package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery poller's per-queue cursors: where a capacity pass starts
 * ({@link RecoveryPoller#earliest}) and what each queue's cursor becomes after
 * it ({@link RecoveryPoller#afterCapacityPass}).
 */
class RecoveryPollerCursorTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final RecoveryCursor START = cursor(10);

    /** A cursor at {@code BASE + n} seconds. */
    private static RecoveryCursor cursor(int n) {
        return new RecoveryCursor(BASE.plusSeconds(n), new UUID(0, n));
    }

    /** A capacity pass's outcome: queue {@code q}'s first deferral (if any), where it read to, and whether it reached the end. */
    private static WorkflowExecutor.RecoveryPass pass(@Nullable RecoveryCursor deferralOfQ, boolean deferredQ,
                                                      @Nullable RecoveryCursor readTo, boolean reachedEnd) {
        var byQueue = new HashMap<String, @Nullable RecoveryCursor>();
        if (deferredQ) {
            byQueue.put("q", deferralOfQ);
        }
        return new WorkflowExecutor.RecoveryPass(0, 0, Map.of(), deferredQ ? Map.of("q", 1) : Map.of(),
                deferralOfQ, false, byQueue, readTo, reachedEnd);
    }

    /** One row per branch of {@code afterCapacityPass}, for queue {@code q}, the pass having started at {@link #START}. */
    enum Branch {
        /** q's cursor lies before the pass's start: its rows there were not re-read, so it keeps its cursor. */
        BEFORE_THE_START_KEEPS_ITS_CURSOR(cursor(5), pass(cursor(12), true, cursor(20), true), cursor(5)),
        /** q deferred again in this pass: it resumes before that first deferral. */
        DEFERRED_AGAIN_TAKES_ITS_NEW_FIRST_DEFERRAL(cursor(10), pass(cursor(14), true, cursor(20), false), cursor(14)),
        /** q deferred nothing and the pass read to the last row: q is drained. */
        NOTHING_DEFERRED_TO_THE_END_IS_DRAINED(cursor(11), pass(null, false, cursor(20), true), null),
        /** q deferred nothing but the pass stopped early: q resumes where the pass stopped reading. */
        NOTHING_DEFERRED_BEFORE_A_STOP_RESUMES_WHERE_IT_STOPPED(cursor(11), pass(null, false, cursor(20), false),
                cursor(20));

        final RecoveryCursor old;
        final WorkflowExecutor.RecoveryPass pass;
        final @Nullable RecoveryCursor expected;

        Branch(RecoveryCursor old, WorkflowExecutor.RecoveryPass pass, @Nullable RecoveryCursor expected) {
            this.old = old;
            this.pass = pass;
            this.expected = expected;
        }
    }

    @ParameterizedTest
    @EnumSource(Branch.class)
    void afterACapacityPassEachQueueTakesItsBranchsCursor(Branch branch) {
        var next = RecoveryPoller.afterCapacityPass(Map.of("q", branch.old), START, branch.pass);
        if (branch.expected == null) {
            assertFalse(next.containsKey("q"), "q is drained: " + next);
        } else {
            assertEquals(branch.expected, next.get("q"));
        }
    }

    @Test
    void aQueueThatFirstDefersInACapacityPassGetsACursor() {
        var next = RecoveryPoller.afterCapacityPass(Map.of(), START, pass(cursor(13), true, cursor(20), true));
        assertEquals(cursor(13), next.get("q"));
    }

    @Test
    void aCapacityPassStartsAtTheEarliestCursorOfTheQueuesWithRoom() {
        var cursors = new HashMap<String, @Nullable RecoveryCursor>();
        cursors.put("late", cursor(9));
        cursors.put("early", cursor(5));
        cursors.put("full", cursor(1));
        assertAll(
                () -> assertEquals(cursor(5), RecoveryPoller.earliest(cursors, Set.of("late", "early")),
                        "both have room: the earlier of the two"),
                () -> assertEquals(cursor(5), RecoveryPoller.earliest(cursors, Set.of("early", "late")),
                        "whatever the iteration order"),
                () -> assertEquals(cursor(9), RecoveryPoller.earliest(cursors, Set.of("late")),
                        "a full queue's earlier cursor is not used"));
    }

    @Test
    void aQueueResumingAtTheFirstRowStartsThePassThere() {
        var cursors = new HashMap<String, @Nullable RecoveryCursor>();
        cursors.put("first", null);
        cursors.put("later", cursor(9));
        assertAll(
                () -> assertNull(RecoveryPoller.earliest(cursors, Set.of("first", "later"))),
                () -> assertTrue(RecoveryPoller.earliest(cursors, Set.of("later")) != null));
    }
}
