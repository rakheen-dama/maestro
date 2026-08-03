package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single place the engine turns "persisted history this build cannot
 * interpret" into a stand-down.
 *
 * <p>Applied immediately after <em>every</em> replay read whose result drives a
 * memoization decision — the activity memoization lookup, the signal and timer
 * replay checks, the parallel fork check, the side-effect checks, the version
 * marker peek, and the saga's compensation replay-skip guards. Reading an
 * {@link EventType#UNKNOWN} row anywhere in that set means a newer node wrote
 * this workflow's history and this node must not act on it.
 *
 * <p>Deliberately fails <em>before</em> any branch is taken. An unguarded
 * unknown event does not merely produce a worse error later: it silently falls
 * through to the live path, re-executes a step that already ran, and attempts a
 * duplicate append — so the run stands down for the wrong reason, after
 * touching the outside world.
 *
 * <h2>Visibility</h2>
 * <p>Public because {@code io.b2mash.maestro.core.saga.SagaManager} performs
 * replay reads of its own and must apply the identical guard. It is internal
 * engine API — not part of the embedder-facing surface — and carries no
 * compatibility promise.
 *
 * <p><b>Thread safety:</b> stateless; all methods are static and safe to call
 * from any thread.
 */
public final class UnknownHistoryGuard {

    private UnknownHistoryGuard() {
    }

    /**
     * Returns {@code event} unless its type is the {@link EventType#UNKNOWN}
     * sentinel, in which case the run stands down.
     *
     * @param event      the event just read from the store
     * @param workflowId the workflow being replayed, for the message
     * @return {@code event}, for chaining into a replay decision
     * @throws UnknownWorkflowHistoryException if the event's type is unknown to
     *                                         this build
     */
    public static WorkflowEvent requireKnown(WorkflowEvent event, String workflowId) {
        if (event.eventType() == EventType.UNKNOWN) {
            throw new UnknownWorkflowHistoryException(workflowId, event.sequenceNumber(),
                    UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                    ("Workflow '%s' has an event of an unknown type at sequence %d — written by a "
                            + "newer node; standing this run down without recording a failure")
                            .formatted(workflowId, event.sequenceNumber()));
        }
        return event;
    }

    /**
     * Applies {@link #requireKnown(WorkflowEvent, String)} to a store lookup
     * result, leaving an empty result untouched.
     *
     * @param event      the lookup result
     * @param workflowId the workflow being replayed, for the message
     * @return {@code event}
     * @throws UnknownWorkflowHistoryException if a present event's type is
     *                                         unknown to this build
     */
    public static Optional<WorkflowEvent> requireKnown(Optional<WorkflowEvent> event, String workflowId) {
        event.ifPresent(e -> requireKnown(e, workflowId));
        return event;
    }

    /**
     * Runs a <em>replay-path</em> deserialization, converting a
     * {@link SerializationException} into a stand-down.
     *
     * <p>Only stored history counts. A live-path serialization failure is an
     * ordinary workflow failure and must keep flowing to the failure handler;
     * a stored payload this build cannot read means a newer node shaped it, so
     * the run stands down instead.
     *
     * @param workflowId     the workflow being replayed
     * @param sequenceNumber the sequence number of the event being read
     * @param what           short description of the payload, for the message
     *                       (e.g. {@code "activity result"})
     * @param deserialize    the deserialization to attempt
     * @param <T>            the deserialized type
     * @return the deserialized payload
     * @throws UnknownWorkflowHistoryException if the stored payload cannot be
     *                                         deserialized by this build
     */
    public static <T> T requireReadablePayload(String workflowId, int sequenceNumber,
                                               String what, Supplier<T> deserialize) {
        try {
            return deserialize.get();
        } catch (SerializationException e) {
            var standDown = payloadStandDown(workflowId, sequenceNumber,
                    "could not deserialize the stored %s".formatted(what));
            standDown.initCause(e);
            throw standDown;
        }
    }

    /**
     * Builds the payload-flavoured stand-down for a stored payload this build
     * cannot interpret even though its <em>type</em> is known — used where the
     * failure is a shape mismatch rather than a thrown
     * {@link SerializationException} (for example a
     * {@link EventType#VERSION_MARKER} whose {@code version} field a newer node
     * reshaped).
     *
     * @param workflowId     the workflow being replayed
     * @param sequenceNumber the sequence number of the event being read
     * @param detail         what could not be read, phrased to follow "…at
     *                       sequence N: "
     * @return the stand-down to throw
     */
    public static UnknownWorkflowHistoryException payloadStandDown(String workflowId,
                                                                   int sequenceNumber,
                                                                   String detail) {
        return new UnknownWorkflowHistoryException(workflowId, sequenceNumber,
                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD,
                ("Workflow '%s' has an event at sequence %d whose stored payload this build "
                        + "cannot interpret (%s) — likely written by a newer node; standing this "
                        + "run down without recording a failure")
                        .formatted(workflowId, sequenceNumber, detail));
    }
}
