package io.b2mash.maestro.core.observe;

/**
 * Why a workflow's <em>local run</em> ended on its own thread without this node
 * recording a terminal outcome (design §11, RULING 5).
 *
 * <p>None of these is a failure, and none of them is a
 * {@link StandDownReason}. A stand-down means "another runner's durable state
 * governs this workflow's progress"; these mean "this thread stopped running the
 * workflow, and the reason is not the workflow's" — routine for all but
 * {@link #NODE_ERROR}, which is a node problem. Keeping them apart is what stops a
 * routine deploy or an operator terminate from incrementing a failure-shaped
 * counter — the same reasoning that makes the engine's control-flow signals
 * {@code Error}s rather than exceptions.
 *
 * @see EngineObserver#runAbandoned(WorkflowInfo, AbandonReason)
 */
public enum AbandonReason {

    /** This node is shutting down; the instance stays recoverable as-is. */
    SHUTDOWN,

    /** An operator terminated the workflow; the {@code TERMINATED} row stands. */
    TERMINATED,

    /**
     * Another writer finalised the instance row first, so this run lost the
     * terminal transition and deliberately did not double-record the outcome.
     */
    CONVERGED,

    /**
     * The terminal status write itself failed. The run is over on this thread
     * and no outcome was recorded; recovery decides what happens next.
     */
    TERMINAL_WRITE_FAILED,

    /**
     * A node-level failure — bad deploy or resource exhaustion — not a workflow
     * failure; the instance stays recoverable.
     *
     * <p>Emitted when a {@link LinkageError} (for example
     * {@link NoClassDefFoundError}, {@link ExceptionInInitializerError}) or a
     * {@link VirtualMachineError} other than {@link StackOverflowError} (for
     * example {@link OutOfMemoryError}, {@link InternalError}) escapes the
     * workflow method. Nothing is written and no compensation runs: the cause
     * is this node's classpath or memory, not the workflow, and recording
     * {@code FAILED} would compensate real work that never failed. A rollback
     * or a healthy node heals it through ordinary recovery. The engine also
     * logs it at {@code ERROR} under the workflow's MDC.
     *
     * <p>Unlike the other constants this one is <em>not</em> routine — alert
     * on it.
     */
    NODE_ERROR
}
