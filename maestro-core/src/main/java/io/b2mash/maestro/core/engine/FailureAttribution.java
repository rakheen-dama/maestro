package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowContextInternals;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Names the failure memos a workflow's escaping exception carried — the
 * {@code failedSequences} of its {@code WORKFLOW_FAILED} payload (WP3, D-4).
 *
 * <p>The engine registers, in the run's failure-memo registry
 * ({@link WorkflowContextInternals#recordFailureMemo}), every exception it
 * throws for a durable {@code ACTIVITY_FAILED} or {@code SIGNAL_TIMEOUT} memo.
 * {@link #sequences} walks the escaping exception, its cause chain and every
 * suppressed exception, recursively, and looks each one up by identity. So a
 * workflow that rethrows the exception, wraps it with its cause, or attaches it
 * as suppressed is attributed; a wrap that drops the cause is not.
 *
 * <p>Pure read: no store call, no {@code catch}, nothing rethrown or wrapped —
 * it cannot change what propagates. A cyclic cause chain is cut by an
 * identity-visited set, and the walk stops after {@value #MAX_THROWABLES}
 * throwables.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread. The registry it
 * reads is synchronized.
 */
final class FailureAttribution {

    /** The most throwables one walk visits. */
    static final int MAX_THROWABLES = 100;

    private FailureAttribution() {
    }

    /**
     * Returns the sequences of the failure memos {@code failure} carried.
     *
     * @param ctx     any context of the failed run
     * @param failure the exception that ended the run
     * @return the sorted, distinct sequences; empty when none was registered
     */
    static List<Integer> sequences(WorkflowContext ctx, Throwable failure) {
        var found = new TreeSet<Integer>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty() && visited.size() < MAX_THROWABLES) {
            var next = pending.poll();
            if (!visited.add(next)) {
                continue;
            }
            var seq = WorkflowContextInternals.failureMemoSequence(ctx, next);
            if (seq != null) {
                found.add(seq);
            }
            var cause = next.getCause();
            if (cause != null) {
                pending.add(cause);
            }
            for (var suppressed : next.getSuppressed()) {
                pending.add(suppressed);
            }
        }
        return List.copyOf(found);
    }
}
