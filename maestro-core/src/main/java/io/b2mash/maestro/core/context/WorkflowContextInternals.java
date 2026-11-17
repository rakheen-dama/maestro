package io.b2mash.maestro.core.context;

import org.jspecify.annotations.Nullable;

/**
 * Engine-internal access to {@link WorkflowContext}'s sequence counter,
 * replay flag and failure-memo registry.
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

    /**
     * Engine-internal: registers {@code thrown}, the exact exception object the
     * engine is about to throw for a durable failure memo
     * ({@code ACTIVITY_FAILED} or {@code SIGNAL_TIMEOUT}, live or replayed),
     * against that memo's sequence in the run's failure-memo registry. The
     * registry is shared by the root context and every bounded context of the
     * run, keyed by identity, and read when the run records
     * {@code WORKFLOW_FAILED} ({@code failedSequences}) so that admin Retry
     * can tell which failure ended the run.
     *
     * <p>Registering changes nothing about what is thrown; it only remembers.
     *
     * @param ctx      any context of the run (root or branch)
     * @param thrown   the exact object about to be thrown
     * @param sequence the sequence number of its failure memo
     */
    public static void recordFailureMemo(WorkflowContext ctx, Throwable thrown, int sequence) {
        ctx.recordFailureMemo(thrown, sequence);
    }

    /**
     * Engine-internal: returns the sequence of the failure memo {@code thrown}
     * was registered against in this run, by identity.
     *
     * @param ctx    any context of the run (root or branch)
     * @param thrown an exception
     * @return the memo's sequence, or {@code null} if the run registered no
     *         memo for this exact object
     */
    public static @Nullable Integer failureMemoSequence(WorkflowContext ctx, Throwable thrown) {
        return ctx.failureMemoSequence(thrown);
    }
}
