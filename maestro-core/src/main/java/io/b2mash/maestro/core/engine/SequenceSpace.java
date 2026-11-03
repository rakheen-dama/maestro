package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;

/**
 * The single source of truth for the engine's ×1000 sequence partitioning.
 *
 * <p>A fork at parent sequence {@code p} with {@code n} branches gives branch
 * {@code i} (zero-based) the block starting at
 * {@code p * BRANCH_MULTIPLIER + (i + 1) * BRANCH_MULTIPLIER} — up to
 * {@code BRANCH_MULTIPLIER - 1} sequence slots per branch — and resumes the
 * parent counter one past the last block, at
 * {@code p * BRANCH_MULTIPLIER + (n + 1) * BRANCH_MULTIPLIER}.
 *
 * <p>Callers: {@code DefaultWorkflowOperations.parallel()} (branch bases and the
 * parent advance), and {@code SagaManager.compensate()} (an up-front pre-check,
 * before anything durable changes), {@code executeSequential} (per-entry blocks
 * anchored at {@code COMPENSATION_STARTED}) and {@code executeParallel}
 * (per-branch blocks anchored at the parallel-compensation fork marker).
 *
 * <p>Sequence numbers are 32-bit {@code int}s and every fork level multiplies
 * by 1000, so depth compounds. {@link #nextParentSeq} computes in {@code long}
 * and throws {@link SequenceSpaceExhaustedException} rather than letting the
 * arithmetic wrap. Because every branch base is strictly below the next parent
 * sequence, a successful {@code nextParentSeq} check for a fork proves every
 * {@link #branchBase} of that fork fits too, which is why {@code branchBase}
 * itself does not check.
 *
 * <p><b>Internal engine API.</b> Public only because {@code SagaManager} lives
 * in another package; not intended for workflow code.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
public final class SequenceSpace {

    /** Width of one branch block; also the factor applied to the parent sequence. */
    public static final int BRANCH_MULTIPLIER = 1000;

    private SequenceSpace() {
    }

    /**
     * Returns the first sequence number of branch {@code branchIndex}'s block.
     *
     * <p>Unchecked: callers must have validated the same fork with
     * {@link #nextParentSeq} first, which bounds every branch base.
     *
     * @param parentSeq   the fork's parent sequence number
     * @param branchIndex the zero-based branch index
     * @return {@code parentSeq * 1000 + (branchIndex + 1) * 1000}
     */
    public static int branchBase(int parentSeq, int branchIndex) {
        return parentSeq * BRANCH_MULTIPLIER + (branchIndex + 1) * BRANCH_MULTIPLIER;
    }

    /**
     * Returns the parent sequence a fork resumes at once all its branch blocks
     * are reserved, or throws if that value does not fit in an {@code int}.
     *
     * @param workflowId  the workflow forking (for the exception only)
     * @param parentSeq   the fork's parent sequence number
     * @param branchCount the number of branch blocks the fork reserves
     * @return {@code parentSeq * 1000 + (branchCount + 1) * 1000}
     * @throws SequenceSpaceExhaustedException if the result would exceed
     *         {@link Integer#MAX_VALUE}
     */
    public static int nextParentSeq(String workflowId, int parentSeq, int branchCount)
            throws SequenceSpaceExhaustedException {
        long next = (long) parentSeq * BRANCH_MULTIPLIER + (long) (branchCount + 1) * BRANCH_MULTIPLIER;
        if (next > Integer.MAX_VALUE) {
            throw new SequenceSpaceExhaustedException(workflowId, parentSeq, branchCount);
        }
        return (int) next;
    }
}
