package io.b2mash.maestro.core.exception;

/**
 * A parallel branch or saga compensation entry tried to take more sequence
 * slots than its sequence block holds.
 *
 * <p>Each branch of a {@code parallel()} call, each branch of a parallel saga
 * compensation, and each entry of a sequential saga compensation owns a block
 * of 1000 sequence numbers, {@code [baseSeq, limit)}, of which {@code baseSeq}
 * itself is never handed out — so it may take at most 999 sequence slots (most
 * steps take one; {@code sleep()} takes two). The 1000th slot would land on the
 * next sibling's base and silently read or overwrite that sibling's history;
 * instead the bounded context throws this exception <em>before</em> consuming
 * the number.
 *
 * <p>This is a genuine, deterministic workflow failure (it fails identically on
 * every replay), so it is an ordinary {@link MaestroException}, not a
 * {@link MaestroControlFlowError}: in a {@code parallel()} branch it fails the
 * branch, and therefore the workflow, and registered compensations run; in a
 * compensation branch or entry it is recorded as that step's
 * {@code COMPENSATION_STEP_FAILED} and the remaining compensations still run.
 * See {@code docs/concepts.md} § Parallel Execution.
 *
 * <p><b>Thread safety:</b> immutable; safe to share.
 */
public final class BranchSequenceExhaustedException extends MaestroException {

    private final String workflowId;
    private final int branchIndex;
    private final int baseSeq;
    private final int limit;

    /**
     * @param workflowId  the workflow whose branch ran out of sequence numbers
     * @param branchIndex the zero-based index of the branch within its fork
     * @param baseSeq     the branch's base sequence number (inclusive; never handed out)
     * @param limit       the exclusive upper bound of the branch's block
     */
    public BranchSequenceExhaustedException(String workflowId, int branchIndex, int baseSeq, int limit) {
        super(("Branch %d of workflow '%s' (a parallel() branch or compensation entry) exhausted its "
                + "sequence block [%d, %d): it may take at most %d sequence slots (sleep() takes two)")
                .formatted(branchIndex, workflowId, baseSeq, limit, limit - baseSeq - 1));
        this.workflowId = workflowId;
        this.branchIndex = branchIndex;
        this.baseSeq = baseSeq;
        this.limit = limit;
    }

    /** Returns the workflow whose branch ran out of sequence numbers. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the zero-based index of the branch within its fork. */
    public int branchIndex() {
        return branchIndex;
    }

    /** Returns the branch's base sequence number. */
    public int baseSeq() {
        return baseSeq;
    }

    /** Returns the exclusive upper bound of the branch's block. */
    public int limit() {
        return limit;
    }
}
