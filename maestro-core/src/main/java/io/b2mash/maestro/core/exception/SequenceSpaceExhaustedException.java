package io.b2mash.maestro.core.exception;

/**
 * A fork — a {@code parallel()} call or a saga compensation phase — cannot be
 * sequenced because its ×1000 branch blocks would not fit in the 32-bit
 * sequence space.
 *
 * <p>Every fork level multiplies the parent sequence by 1000, so depth
 * compounds: from a low parent sequence any one path through a workflow has
 * room for two fork levels. <em>Parent-line</em> levels compound — sequential
 * {@code parallel()} calls and the compensation phase that follows a failure —
 * so two sequential {@code parallel()} calls fit, as does one plus
 * compensation, but two plus compensation do not. A nested {@code parallel()}
 * compounds only within its own branch (it never moves the parent counter), so
 * a nested fork plus compensation fits, as does a nested fork plus a second
 * sequential fork. The engine throws this <em>before</em> any sequence is
 * consumed or anything durable changes, instead of letting {@code int}
 * arithmetic wrap and run work at garbage (negative) sequence numbers.
 *
 * <p>This is a genuine, deterministic workflow failure — it fails the same way
 * on every node and every replay — so unlike the engine's control-flow signals
 * ({@link MaestroControlFlowError}) it extends {@link MaestroException}. When it
 * escapes the workflow method, the workflow finishes {@code FAILED}; when a
 * compensation entry's own {@code parallel()} throws it (a compensation that
 * forks after a forward {@code parallel()}), that entry is recorded
 * {@code COMPENSATION_STEP_FAILED} and the remaining entries still run; when it
 * is thrown by the compensation pre-check, the workflow still finishes
 * {@code FAILED}, no compensation runs, and the engine logs at {@code ERROR}
 * the step names the operator must compensate manually. See {@code docs/concepts.md}
 * § Parallel Execution → Depth limit and {@code docs/open-issues.md#issue-28}.
 *
 * <p><b>Thread safety:</b> immutable; safe to share.
 */
public final class SequenceSpaceExhaustedException extends MaestroException {

    private final String workflowId;
    private final int parentSeq;
    private final int branchCount;

    /**
     * @param workflowId  the workflow whose fork cannot be sequenced
     * @param parentSeq   the fork's parent sequence number
     * @param branchCount the number of branch blocks the fork needs
     */
    public SequenceSpaceExhaustedException(String workflowId, int parentSeq, int branchCount) {
        super(("Forking %d branch(es) at sequence %d of workflow '%s' would exceed the 32-bit sequence space; "
                + "reduce parallel() nesting/sequential forks or the number of compensation entries")
                .formatted(branchCount, parentSeq, workflowId));
        this.workflowId = workflowId;
        this.parentSeq = parentSeq;
        this.branchCount = branchCount;
    }

    /** Returns the workflow whose fork cannot be sequenced. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the fork's parent sequence number. */
    public int parentSeq() {
        return parentSeq;
    }

    /** Returns the number of branch blocks the fork needs. */
    public int branchCount() {
        return branchCount;
    }
}
