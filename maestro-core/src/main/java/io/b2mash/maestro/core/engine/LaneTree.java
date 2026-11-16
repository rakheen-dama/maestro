package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The lanes of a workflow's sequence space, rebuilt from its history alone
 * (WP3, D-2) — the structure admin Retry plans its deletes over.
 *
 * <h2>Lanes</h2>
 * <p>A <em>lane</em> is the sequence of slots one {@code WorkflowContext}
 * counter hands out. The root lane is {@code 1, 2, 3, …}. A fork marker at
 * slot {@code p} with {@code n} branches ({@link #isForkMarker}) opens branch
 * lane {@code i} at {@link SequenceSpace#branchBase branchBase(p, i)}{@code + 1},
 * bounded by {@code branchBase + 999} until that branch's own first fork, and
 * the forking lane continues at
 * {@link SequenceSpace#nextParentSeq nextParentSeq(p, n)}{@code + 1}, unbounded.
 * So a lane's slots ascend but are not numerically contiguous after a fork,
 * and lane ranges overlap numerically; no {@code seq → lane} formula is exact,
 * which is why the history is walked.
 *
 * <h2>The walk</h2>
 * <p>Each lane is walked slot by slot and stops at its first empty slot (or a
 * slot another lane already took). The walk is iterative, with an explicit
 * stack, so deep nesting cannot overflow the thread stack. An event no lane
 * reaches is {@linkplain #unplaced() unplaced}, except {@code WORKFLOW_FAILED},
 * which sits at the root's next slot — after a gap whenever the run's last step
 * consumed a slot without writing a memo.
 *
 * <h2>{@link #after(int)}</h2>
 * <p>The propagation path of a placed event {@code F}: the slots of
 * {@code F}'s lane after {@code F}; for every ancestor lane, its slots after
 * the fork marker that spawned the child lane on the path; and, for every fork
 * marker in those slots, that marker's whole subtree. Sibling branches of a
 * lane on the path, and the subtrees of forks before the path positions, are
 * never in it.
 *
 * <p><b>Thread safety:</b> immutable after {@link #of}; safe to share across
 * threads.
 */
final class LaneTree {

    /** The step name of a {@code parallel()} fork marker. */
    static final String FORK_STEP = "$maestro:parallel";

    /** The workflow id {@link SequenceSpace#nextParentSeq} is given; only its exception names it. */
    private static final String WORKFLOW_ID = "lane-tree";

    /** The most slots a branch lane may take before its first fork. */
    private static final int BRANCH_SLOTS = SequenceSpace.BRANCH_MULTIPLIER - 1;

    /**
     * One lane: its slots in hand-out order, the lane that forked it and the
     * fork marker's slot in that lane ({@code null} parent for the root).
     */
    private static final class Lane {
        private final @Nullable Lane parent;
        private final int forkSlot;
        private final TreeSet<Integer> slots = new TreeSet<>();

        private Lane(@Nullable Lane parent, int forkSlot) {
            this.parent = parent;
            this.forkSlot = forkSlot;
        }
    }

    /** A pending walk: lane {@code lane} from slot {@code from}, at most to {@code limit}. */
    private record Walk(Lane lane, long from, long limit) {}

    private final Map<Integer, Lane> laneOfSlot;
    private final Map<Integer, List<Lane>> branchesOfFork;
    private final SortedSet<Integer> unplaced;

    private LaneTree(Map<Integer, Lane> laneOfSlot, Map<Integer, List<Lane>> branchesOfFork,
                     SortedSet<Integer> unplaced) {
        this.laneOfSlot = laneOfSlot;
        this.branchesOfFork = branchesOfFork;
        this.unplaced = unplaced;
    }

    /**
     * Rebuilds the lanes of {@code history}.
     *
     * @param history a workflow's events, in any order; at most one per sequence
     *                is considered (the first)
     * @return the lane tree
     */
    static LaneTree of(List<WorkflowEvent> history) {
        var bySeq = new HashMap<Integer, WorkflowEvent>();
        for (var event : history) {
            bySeq.putIfAbsent(event.sequenceNumber(), event);
        }

        var laneOfSlot = new HashMap<Integer, Lane>();
        var branchesOfFork = new HashMap<Integer, List<Lane>>();
        var pending = new ArrayDeque<Walk>();
        pending.push(new Walk(new Lane(null, 0), 1, Integer.MAX_VALUE));

        while (!pending.isEmpty()) {
            var walk = pending.pop();
            var lane = walk.lane();
            long slot = walk.from();
            long limit = walk.limit();
            while (slot <= limit) {
                var seq = (int) slot;
                var event = bySeq.get(seq);
                if (event == null || laneOfSlot.containsKey(seq)) {
                    break;
                }
                laneOfSlot.put(seq, lane);
                lane.slots.add(seq);
                if (isForkMarker(event)) {
                    var branchCount = branchCount(event);
                    // A recorded fork already passed this overflow check when it ran.
                    var continuation = SequenceSpace.nextParentSeq(WORKFLOW_ID, seq, branchCount);
                    var branches = new ArrayList<Lane>(branchCount);
                    for (int i = 0; i < branchCount; i++) {
                        var branch = new Lane(lane, seq);
                        branches.add(branch);
                        long base = SequenceSpace.branchBase(seq, i);
                        pending.push(new Walk(branch, base + 1, base + BRANCH_SLOTS));
                    }
                    branchesOfFork.put(seq, List.copyOf(branches));
                    // The forking lane continues past every branch block, unbounded.
                    slot = (long) continuation + 1;
                    limit = Integer.MAX_VALUE;
                } else {
                    slot++;
                }
            }
        }

        var unplaced = new TreeSet<Integer>();
        for (var event : bySeq.values()) {
            if (!laneOfSlot.containsKey(event.sequenceNumber())
                    && event.eventType() != EventType.WORKFLOW_FAILED) {
                unplaced.add(event.sequenceNumber());
            }
        }
        return new LaneTree(Map.copyOf(laneOfSlot), Map.copyOf(branchesOfFork),
                Collections.unmodifiableSortedSet(unplaced));
    }

    /**
     * Reports whether {@code e} is a {@code parallel()} fork marker: a
     * {@code SIDE_EFFECT} named {@value #FORK_STEP} whose payload's
     * {@code branchCount} is an integral number of at least 2. A missing,
     * non-integral or smaller count is not a fork ({@code parallel()} of 0 or 1
     * task writes no marker).
     *
     * @param e an event
     * @return {@code true} if it is a fork marker
     */
    static boolean isForkMarker(WorkflowEvent e) {
        if (e.eventType() != EventType.SIDE_EFFECT || !FORK_STEP.equals(e.stepName())) {
            return false;
        }
        var payload = e.payload();
        if (payload == null) {
            return false;
        }
        var count = payload.get("branchCount");
        return count != null && count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 2;
    }

    private static int branchCount(WorkflowEvent forkMarker) {
        return forkMarker.payload().get("branchCount").intValue();
    }

    /**
     * @param sequence a sequence number
     * @return {@code true} if the walk reached an event at {@code sequence}
     */
    boolean isPlaced(int sequence) {
        return laneOfSlot.containsKey(sequence);
    }

    /**
     * @return every event sequence the walk did not reach, except those of
     *         {@code WORKFLOW_FAILED} events; unmodifiable
     */
    SortedSet<Integer> unplaced() {
        return unplaced;
    }

    /**
     * Returns the propagation path of the placed event at {@code sequence}
     * (class Javadoc). The event itself is not included.
     *
     * @param sequence a placed sequence
     * @return the path's sequences, ascending
     * @throws IllegalArgumentException if {@code sequence} is not placed
     */
    SortedSet<Integer> after(int sequence) {
        var lane = laneOfSlot.get(sequence);
        if (lane == null) {
            throw new IllegalArgumentException("Sequence " + sequence + " is not placed in the lane tree");
        }
        var path = new TreeSet<Integer>();
        var forks = new ArrayDeque<Integer>();
        int from = sequence;
        for (var current = lane; current != null; current = current.parent) {
            for (var slot : current.slots.tailSet(from, false)) {
                path.add(slot);
                if (branchesOfFork.containsKey(slot)) {
                    forks.push(slot);
                }
            }
            from = current.forkSlot;
        }
        // Every fork in the path takes its whole subtree with it.
        while (!forks.isEmpty()) {
            for (var branch : branchesOfFork.get(forks.pop())) {
                for (var slot : branch.slots) {
                    path.add(slot);
                    if (branchesOfFork.containsKey(slot)) {
                        forks.push(slot);
                    }
                }
            }
        }
        return Collections.unmodifiableSortedSet(path);
    }
}
