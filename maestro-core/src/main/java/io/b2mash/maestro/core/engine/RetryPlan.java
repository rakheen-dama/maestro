package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What an admin Retry of a {@code FAILED} workflow deletes, as computed by
 * {@link RetryPlanner#plan} (WP3, D-3): the event sequences and timer ids the
 * store removes in the same transaction as the {@code FAILED → RUNNING}
 * compare-and-set.
 *
 * <p><b>Thread safety:</b> immutable record; both sets are unmodifiable copies.
 *
 * @param basis            how the failure to re-run was chosen
 * @param deleteSequences  the event sequences to delete, ascending; empty when refused
 * @param deleteTimerIds   the timer ids ({@code timer_id}) whose rows to delete, any
 *                         status; empty when refused
 * @param consumedSignalAt the lowest sequence of a {@code SIGNAL_RECEIVED} memo in the
 *                         delete set, or {@code null}; when set, the Retry is
 *                         {@linkplain #refused() refused} and writes nothing (MD-1)
 */
record RetryPlan(
        Basis basis,
        SortedSet<Integer> deleteSequences,
        SortedSet<String> deleteTimerIds,
        @Nullable Integer consumedSignalAt
) {

    /** How the plan chose the failure memo(s) to re-run. */
    enum Basis {
        /** The {@code WORKFLOW_FAILED} payload's {@code failedSequences} named them. */
        ATTRIBUTED,
        /** A pre-WP3 payload's message matched exactly one failure memo. */
        MATCHED_MESSAGE,
        /** Nothing attributed: only failures whose propagation path recorded nothing go. */
        LAST_IN_LANE,
        /** The lane walk left an event unplaced, or two lanes claimed one slot: today's delete-by-type plan. */
        LEGACY
    }

    /**
     * Copies both sets into unmodifiable sorted sets. A refused plan
     * ({@code consumedSignalAt} set) keeps neither: both sets are empty, so a
     * refusal cannot be applied even by a caller that forgot to check
     * {@link #refused()}.
     */
    RetryPlan {
        if (consumedSignalAt != null) {
            deleteSequences = Collections.emptySortedSet();
            deleteTimerIds = Collections.emptySortedSet();
        } else {
            deleteSequences = Collections.unmodifiableSortedSet(new TreeSet<>(deleteSequences));
            deleteTimerIds = Collections.unmodifiableSortedSet(new TreeSet<>(deleteTimerIds));
        }
    }

    /**
     * @return {@code true} if the delete set holds a consumed signal's memo, so
     *         the Retry must write nothing
     */
    boolean refused() {
        return consumedSignalAt != null;
    }
}
