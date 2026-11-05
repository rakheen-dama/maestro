package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.context.WorkflowContext;
import org.jspecify.annotations.NullMarked;

/**
 * Deterministic message ids for signals published from inside an activity.
 *
 * <p>{@link #forCurrentActivity()} returns {@code "<workflowId>:<seq>"} — the
 * publishing workflow's business id and the sequence number of the activity
 * that is running. Both are stable across every re-execution of that
 * activity: a retry after a failed send, and a re-run after a crash between
 * the send and the activity's memoized result (the replay reaches the same
 * sequence). So every publish attempt of one logical signal carries the same
 * id, and the receiving service — which persists at most one signal per
 * {@code (workflowId, signalName, messageId)} — keeps exactly one.
 *
 * <p><b>One activity, several signals:</b> an activity that publishes more
 * than one signal of the same name to the same target workflow must give each
 * a distinct suffix ({@link #forCurrentActivity(String)}, e.g. the item key),
 * or the second is suppressed as a duplicate of the first. Different signal
 * names, or different target workflows, never collide.
 *
 * <p><b>Workflow id reuse:</b> the id does not include the run, so a workflow
 * id reused after the earlier run was purged reproduces the earlier run's ids.
 * A target that still holds the earlier signals would suppress the new ones;
 * add a run-unique suffix (a business key) where workflow ids are reused.
 *
 * <p><b>Thread safety:</b> stateless utility; reads the calling thread's
 * bound {@link WorkflowContext}.
 *
 * @see MaestroSignalHeaders#MESSAGE_ID
 */
@NullMarked
public final class SignalMessageIds {

    private SignalMessageIds() {
    }

    /**
     * The message id for the one signal the running activity publishes.
     *
     * @return {@code "<workflowId>:<activitySequence>"}
     * @throws IllegalStateException if called outside a workflow or activity
     *                               execution (no bound {@link WorkflowContext})
     */
    public static String forCurrentActivity() {
        var ctx = WorkflowContext.current();
        return ctx.workflowId() + ":" + ctx.currentSequence();
    }

    /**
     * The message id for one of several signals the running activity
     * publishes.
     *
     * @param suffix distinguishes this signal from the activity's others — the
     *               same value on every attempt (an item key, an index into a
     *               fixed list); must not be blank
     * @return {@code "<workflowId>:<activitySequence>:<suffix>"}
     * @throws IllegalStateException    if called outside a workflow or activity
     *                                  execution
     * @throws IllegalArgumentException if {@code suffix} is blank
     */
    public static String forCurrentActivity(String suffix) {
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("a message id suffix must not be blank");
        }
        return forCurrentActivity() + ":" + suffix;
    }
}
