package io.b2mash.maestro.core.context;

/**
 * Engine-internal access to {@link WorkflowContext}'s sequence counter and
 * replay flag.
 *
 * <p><b>Not for workflow code.</b> Calling any method here from a workflow
 * method corrupts the memoization sequence or the replay state and breaks
 * deterministic replay. These mutators used to be public on
 * {@link WorkflowContext}, where they appeared in the {@code workflow.}
 * autocomplete that workflow authors use; they are now package-private there
 * and reachable only through this deliberately named class.
 *
 * <p>This is a documented limitation, not true encapsulation: the engine
 * lives in the {@code engine} and {@code saga} packages, so package-private
 * access alone cannot reach it, and {@code maestro-core} has no
 * {@code module-info.java} to export a package selectively. Third-party
 * {@code WorkflowOperations} implementers use this class.
 *
 * <p><b>Thread safety:</b> stateless; each method inherits the guarantees of
 * the {@link WorkflowContext} method it delegates to.
 */
public final class WorkflowContextInternals {

    private WorkflowContextInternals() {
    }

    /**
     * Atomically increments and returns the context's next sequence number.
     *
     * @param ctx the workflow context
     * @return the next sequence number (1-based: first call returns 1)
     * @throws io.b2mash.maestro.core.exception.BranchSequenceExhaustedException
     *         if {@code ctx} is a bounded branch context whose block is full
     * @see WorkflowContext#bounded(WorkflowContext, int, int, int, io.b2mash.maestro.core.engine.WorkflowOperations)
     */
    public static int nextSequence(WorkflowContext ctx) {
        return ctx.nextSequence();
    }

    /**
     * Sets the context's sequence counter to a specific value. Used for
     * parallel branch contexts and to rebase past a fork's sequence space.
     *
     * @param ctx      the workflow context
     * @param sequence the sequence value to set
     */
    public static void setSequence(WorkflowContext ctx, int sequence) {
        ctx.setSequence(sequence);
    }

    /**
     * Updates the context's replay state.
     *
     * @param ctx       the workflow context
     * @param replaying {@code true} if replaying, {@code false} if live
     */
    public static void setReplaying(WorkflowContext ctx, boolean replaying) {
        ctx.setReplaying(replaying);
    }
}
