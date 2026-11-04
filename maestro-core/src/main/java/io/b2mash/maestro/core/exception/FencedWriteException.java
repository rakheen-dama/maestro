package io.b2mash.maestro.core.exception;

import java.util.UUID;

/**
 * A run-scoped store write was rejected because the writing run's lease epoch
 * is no longer the instance row's current {@code lease_epoch}: another node
 * has adopted the workflow since this run read it, so this run has been
 * <b>fenced</b> and nothing was written.
 *
 * <p>Raised by the fenced {@link io.b2mash.maestro.core.spi.WorkflowStore}
 * overloads that take a {@link io.b2mash.maestro.core.spi.RunLease} —
 * {@code appendEvent}, {@code completeInstance}, {@code updateInstance} and
 * {@code saveTimer} — the four writes that carry progress, each of which checks
 * the epoch atomically with the write. (The three CAS flips
 * {@code markSignalConsumed}, {@code markTimerFired} and
 * {@code markTimerCancelled} are best-effort — epoch checked in the same
 * statement, row not locked — and report a fence silently by returning
 * {@code false}: none carries progress on its own, and the loud fenced append
 * of the same step, whether it came before or after, is the rejection that
 * stands the run down.)
 *
 * <p>Like {@link DuplicateEventException} and {@link StaleRunException}, this
 * means <em>the run must stand down</em>, not that the workflow failed: the
 * adopting node owns the workflow's progress and its durable state governs.
 * {@code WorkflowExecutor} routes it to the same stand-down arm, reported as
 * {@link io.b2mash.maestro.core.observe.StandDownReason#LEASE_FENCED}, and every
 * engine write site marks the run's {@code RunCancellation} stale before
 * rethrowing, so workflow code that swallows the exception still stops at its
 * next live step. Closes {@code docs/open-issues.md} Issue 11.
 */
public final class FencedWriteException extends MaestroException {

    private final UUID instanceId;
    private final long leaseEpoch;
    private final String operation;

    /**
     * Creates a fenced-write exception.
     *
     * @param instanceId the workflow instance whose row rejected the write
     * @param leaseEpoch the epoch the rejected run held — necessarily lower
     *                   than the row's current epoch
     * @param operation  the store operation that was fenced, e.g.
     *                   {@code "appendEvent"} or {@code "completeInstance"}
     */
    public FencedWriteException(UUID instanceId, long leaseEpoch, String operation) {
        super(("Fenced write rejected: %s on workflow instance %s — this run's lease epoch %d is no "
                + "longer current; another node has adopted the workflow and this run must stand down")
                .formatted(operation, instanceId, leaseEpoch));
        this.instanceId = instanceId;
        this.leaseEpoch = leaseEpoch;
        this.operation = operation;
    }

    /** The workflow instance whose row rejected the write. */
    public UUID instanceId() {
        return instanceId;
    }

    /** The lease epoch the rejected run held. */
    public long leaseEpoch() {
        return leaseEpoch;
    }

    /** The store operation that was fenced. */
    public String operation() {
        return operation;
    }

    /**
     * The one-line detail the engine records on the run's
     * {@code RunCancellation} and in the stand-down observation.
     *
     * @return e.g. {@code "lease epoch 0 superseded at appendEvent"}
     */
    public String staleDetail() {
        return "lease epoch " + leaseEpoch + " superseded at " + operation;
    }
}
