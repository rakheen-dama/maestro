package io.b2mash.maestro.core.exception;

import io.b2mash.maestro.core.model.EventType;

/**
 * Signals that a workflow's <em>local run</em> must <b>stand down</b> because
 * this node cannot interpret the workflow's persisted history — an event whose
 * type string is absent from this build's {@link EventType} enum (read back as
 * {@link EventType#UNKNOWN}), or a stored payload this build cannot
 * deserialize while replaying. Both mean the same thing: a <em>newer</em> node
 * wrote that history during a mixed-version deploy window, and this node is
 * too old to read it. It is <b>not</b> a workflow failure.
 *
 * <p>The run stands down exactly like a graceful shutdown does: nothing is
 * written, <b>no compensation runs</b>, the instance keeps whatever
 * recoverable status it already had, and the instance lock is released as the
 * thread unwinds through {@code WorkflowExecutor.executeWorkflow}'s
 * {@code finally}. An upgraded node then adopts and processes the workflow
 * through the ordinary lock-TTL/recovery-poller machinery, unchanged.
 *
 * <h2>The failure this prevents</h2>
 * <p>Version N+1 of a service writes a new event type. A node still on version
 * N adopts one of those workflows for recovery and reads the row. Without this
 * signal the enum parse throws an {@link IllegalArgumentException} that looks
 * like any other exception escaping a workflow method — so the engine records
 * the workflow {@code FAILED} <em>and runs its compensations</em>, unwinding
 * real work (refunds issued, reservations released) for a workflow that never
 * failed and is, on the other half of the fleet, perfectly healthy.
 *
 * <h2>Why this extends {@code Error}, not {@code MaestroException}</h2>
 * <p>Same rationale as {@link ExecutorShutdownException} and
 * {@link WorkflowTerminatedException}, collected on their shared base
 * {@link MaestroControlFlowError}: a workflow author's ordinary
 * {@code try { ... } catch (Exception e) { ... }} around an activity call or a
 * park point is common, reasonable-looking code, and if this were a
 * {@link RuntimeException} that block would swallow it and convert "this node
 * is too old to read this history" back into a recorded workflow failure with
 * compensations — precisely the catastrophe above, reinstated by the very code
 * that was written to be careful. Extending {@code Error} puts it outside
 * {@code catch (Exception)}'s reach, and outside most {@code catch (Throwable)}
 * "log and continue" blocks. Broad {@code catch (Throwable)} collectors inside
 * the engine check for {@link MaestroControlFlowError} and rethrow before
 * recording anything as a failure.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch, swallow, or wrap this exception. If you must catch broadly,
 * check for {@link MaestroControlFlowError} first and rethrow it.
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share.
 *
 * @see MaestroControlFlowError
 * @see EventType#UNKNOWN
 */
public final class UnknownWorkflowHistoryException extends MaestroControlFlowError {

    /** Why the persisted history could not be interpreted. */
    public enum Kind {

        /**
         * The event's stored {@code event_type} string is not a constant of
         * this build's {@link EventType} enum — the row mapper read it back as
         * {@link EventType#UNKNOWN}.
         */
        UNKNOWN_EVENT_TYPE,

        /**
         * The event's type is known but its stored payload could not be
         * interpreted on the replay path — it failed to deserialize, or (for a
         * {@link EventType#VERSION_MARKER}) carried a shape this build does not
         * understand. Only <em>stored</em> history counts: a live-path
         * serialization failure is an ordinary workflow failure.
         */
        UNKNOWN_EVENT_PAYLOAD
    }

    private final String workflowId;
    private final int sequenceNumber;
    private final Kind kind;

    /**
     * Creates a stand-down signal.
     *
     * @param workflowId     the workflow whose local run is standing down
     * @param sequenceNumber the sequence number of the unreadable event
     * @param kind           why the history could not be interpreted
     * @param message        descriptive message naming the workflow, sequence
     *                       and cause
     */
    public UnknownWorkflowHistoryException(String workflowId, int sequenceNumber,
                                           Kind kind, String message) {
        super(message);
        this.workflowId = workflowId;
        this.sequenceNumber = sequenceNumber;
        this.kind = kind;
    }

    /** @return the workflow whose local run stood down */
    public String workflowId() {
        return workflowId;
    }

    /** @return the sequence number of the event this node could not interpret */
    public int sequenceNumber() {
        return sequenceNumber;
    }

    /** @return why the history could not be interpreted */
    public Kind kind() {
        return kind;
    }
}
