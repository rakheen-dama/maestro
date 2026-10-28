package io.b2mash.maestro.core.exception;

import io.b2mash.maestro.core.model.WorkflowStatus;

/**
 * Thrown when a fresh read of a workflow's instance row shows a
 * <b>terminal status this run did not write</b> — another runner of the same
 * workflow has already finalised it. The meaning is: <em>write nothing,
 * compensate nothing, stand down.</em>
 *
 * <h2>Where it comes from</h2>
 * <p>Instance locks are best-effort (Issue 11): a node frozen past its lock
 * TTL, partitioned, or running without a lock backend can execute a workflow
 * a peer has also adopted. The peer's outcome is the durable truth. This
 * exception is raised by the engine's status writers when they find it:
 * <ul>
 *   <li>{@code InstanceStatusWriter} — a park/wake status write
 *       ({@code WAITING_SIGNAL}/{@code WAITING_TIMER}/{@code RUNNING}) whose
 *       fresh read is {@code COMPLETED} or {@code FAILED};</li>
 *   <li>{@code SagaManager}'s transition to {@code COMPENSATING} on the same
 *       fresh read — a stale run must never compensate a workflow a peer has
 *       already finished.</li>
 * </ul>
 * A fresh read of {@code TERMINATED} is <em>not</em> this exception: it raises
 * {@link WorkflowTerminatedException}, the operator's stop signal.
 *
 * <h2>How it is handled</h2>
 * <p>It is the status-write variant of the signal a
 * {@link DuplicateEventException} carries at an event append (the store's
 * {@code (workflow_instance_id, sequence_number)} guard rejecting a stale
 * run's write), and it shares that exception's handling:
 * {@code WorkflowExecutor} catches both in one stand-down arm, ahead of its
 * generic failure arm, records nothing and reports
 * {@code StandDownReason.STALE_RUN}. {@code SagaManager} rethrows it rather
 * than recording a compensation-step failure.
 *
 * <p><b>An ordinary {@link MaestroException} by design</b> — not one of the
 * {@code MaestroControlFlowError} signals. Like {@code DuplicateEventException}
 * it reports a persistence-level conflict the engine converges on, and it
 * keeps that exception's catchability: a workflow author's own
 * {@code catch (Exception)} can intercept it, exactly as it can a duplicate
 * append. The {@code permits} clause of {@code MaestroControlFlowError} is
 * deliberately left at three.
 *
 * <p><b>Thread safety:</b> immutable.
 */
public final class StaleRunException extends MaestroException {

    private final String workflowId;
    private final WorkflowStatus observedStatus;
    private final String detail;

    /**
     * @param workflowId     the workflow's business ID
     * @param observedStatus the terminal status the fresh read returned
     * @param detail         what this run was about to do when it found the
     *                       row finalised (e.g. {@code "status write of RUNNING"})
     */
    public StaleRunException(String workflowId, WorkflowStatus observedStatus, String detail) {
        super("Workflow '%s' is already %s — another runner finalised it; this run is stale (%s)"
                .formatted(workflowId, observedStatus, detail));
        this.workflowId = workflowId;
        this.observedStatus = observedStatus;
        this.detail = detail;
    }

    /** @return the workflow's business ID */
    public String workflowId() {
        return workflowId;
    }

    /** @return the terminal status another runner wrote */
    public WorkflowStatus observedStatus() {
        return observedStatus;
    }

    /** @return what this run was about to do when it found the row finalised */
    public String detail() {
        return detail;
    }
}
