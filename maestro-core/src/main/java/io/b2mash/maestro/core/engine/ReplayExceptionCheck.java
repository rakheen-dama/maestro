package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

/**
 * Issue 89: decides whether an exception that escaped a lane's workflow code —
 * the workflow method (the root lane) or a {@code parallel()} branch — was
 * thrown while that lane was still replaying recorded history.
 *
 * <p><b>The predicate.</b> The lane is still replaying ({@link
 * WorkflowContext#isReplaying()}: the run was adopted with history and this
 * lane has not yet met a memo miss, so it has written nothing live) <em>and</em>
 * an event already holds the lane's next sequence ({@code currentSequence() + 1}
 * — the slot its next operation, or for the root lane the terminal
 * {@code WORKFLOW_FAILED} write, would take). That event was recorded by an
 * earlier run, so the code threw inside its recorded prefix: a code change,
 * not a failure. Replayed deterministically, code that threw here before would
 * have recorded nothing after this point in the lane.
 *
 * <p>Two things at that slot are not a replay exception:
 * <ul>
 *   <li>nothing — the lane has consumed its history; a recorded failure
 *       replayed at the end of the history (a crash before the terminal write,
 *       a manual Retry) fails the run as before;</li>
 *   <li>{@code COMPENSATION_STARTED} — the root lane's anchor of a saga
 *       recovered while {@code COMPENSATING}: the failure is the one that
 *       started the unwind, which carries on as before.</li>
 * </ul>
 * A lane that went live is never checked: an event at its next slot was then
 * written by a concurrent runner, which the terminal write reports as
 * {@code STALE_RUN} as before.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
final class ReplayExceptionCheck {

    private ReplayExceptionCheck() {
    }

    /**
     * Returns the stand-down detail if {@code failure} escaped {@code lane}
     * while the lane was still replaying recorded history, else {@code null}.
     * Reads one event from the store, and only when the lane is replaying;
     * a store fault propagates to the caller.
     *
     * @param store   the run's store
     * @param lane    the context of the lane the exception escaped
     * @param failure the exception
     * @return {@code seq=<n> stored=<step> type=<event type> threw=<failure>},
     *         or {@code null}
     */
    static @Nullable String detail(WorkflowStore store, WorkflowContext lane, Throwable failure) {
        if (!lane.isReplaying()) {
            return null;
        }
        var next = lane.currentSequence() + 1;
        var recorded = store.getEventBySequence(lane.workflowInstanceId(), next);
        if (recorded.isEmpty() || recorded.get().eventType() == EventType.COMPENSATION_STARTED) {
            return null;
        }
        var event = recorded.get();
        return "seq=" + next + " stored=" + event.stepName() + " type=" + event.eventType()
                + " threw=" + failure;
    }
}
