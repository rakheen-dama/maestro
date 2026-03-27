package io.maestro.core.context;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-workflow-instance context bound to the workflow's virtual thread.
 *
 * <p>The activity proxy reads this context to obtain the current sequence
 * number for memoization lookups. The {@link #nextSequence()} method is
 * called once per activity invocation to deterministically assign a
 * sequence number to each step.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>The {@code WorkflowExecutor} creates a context and calls
 *       {@link #bind(WorkflowContext)} on the workflow's virtual thread.</li>
 *   <li>The workflow method runs. Each activity call reads the context
 *       via {@link #current()} and increments the sequence.</li>
 *   <li>When the workflow completes (or fails), the executor calls
 *       {@link #clear()} to remove the context from the thread.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>Each workflow runs on its own virtual thread. The context is bound
 * to that thread via {@link ThreadLocal}. The sequence counter uses
 * {@link AtomicInteger} as a defensive measure against accidental
 * misuse from parallel branches (a future enhancement).
 */
public final class WorkflowContext {

    private static final ThreadLocal<WorkflowContext> CURRENT = new ThreadLocal<>();

    private final UUID workflowInstanceId;
    private final String workflowId;
    private final UUID runId;
    private final String workflowType;
    private final String taskQueue;
    private final String serviceName;
    private final AtomicInteger sequenceCounter;
    private volatile boolean replaying;

    /**
     * Creates a new workflow context.
     *
     * @param workflowInstanceId the workflow instance UUID (primary key)
     * @param workflowId         the business workflow ID (e.g., {@code "order-abc"})
     * @param runId              the current run ID (changes on manual retry)
     * @param workflowType       the workflow type name
     * @param taskQueue          the task queue name
     * @param serviceName        the owning service name
     * @param initialSequence    the starting sequence number (0 for new, higher for resumed)
     * @param replaying          whether this execution is replaying stored events
     */
    public WorkflowContext(
            UUID workflowInstanceId,
            String workflowId,
            UUID runId,
            String workflowType,
            String taskQueue,
            String serviceName,
            int initialSequence,
            boolean replaying
    ) {
        this.workflowInstanceId = workflowInstanceId;
        this.workflowId = workflowId;
        this.runId = runId;
        this.workflowType = workflowType;
        this.taskQueue = taskQueue;
        this.serviceName = serviceName;
        this.sequenceCounter = new AtomicInteger(initialSequence);
        this.replaying = replaying;
    }

    // ── Thread-local management ───────────────────────────────────────

    /**
     * Binds this context to the current thread.
     *
     * <p>Must be called on the workflow's virtual thread before the
     * workflow method starts executing.
     *
     * @param context the context to bind
     */
    public static void bind(WorkflowContext context) {
        CURRENT.set(context);
    }

    /**
     * Returns the context bound to the current thread.
     *
     * @return the current workflow context
     * @throws IllegalStateException if no context is bound (not on a workflow thread)
     */
    public static WorkflowContext current() {
        var ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No WorkflowContext bound to current thread. "
                            + "Activity methods can only be called from within a workflow execution.");
        }
        return ctx;
    }

    /**
     * Removes the context from the current thread.
     *
     * <p>Must be called when the workflow execution ends (success or failure)
     * to prevent thread-local leaks.
     */
    public static void clear() {
        CURRENT.remove();
    }

    // ── Sequence management ───────────────────────────────────────────

    /**
     * Atomically increments and returns the next sequence number.
     *
     * <p>Called once per activity invocation. The returned value is used
     * as the memoization key: {@code (workflowInstanceId, sequence)}.
     *
     * @return the next sequence number (1-based: first call returns 1)
     */
    public int nextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    /**
     * Returns the current sequence number without incrementing.
     *
     * @return the current (last assigned) sequence number
     */
    public int currentSequence() {
        return sequenceCounter.get();
    }

    // ── Replay state ──────────────────────────────────────────────────

    /**
     * Returns whether this execution is currently replaying stored events.
     *
     * <p>Set to {@code true} during recovery. Flipped to {@code false} by
     * the activity proxy when the first live (non-memoized) activity executes.
     *
     * @return {@code true} if replaying
     */
    public boolean isReplaying() {
        return replaying;
    }

    /**
     * Updates the replay state.
     *
     * @param replaying {@code true} if replaying, {@code false} if live
     */
    public void setReplaying(boolean replaying) {
        this.replaying = replaying;
    }

    // ── Identity accessors ────────────────────────────────────────────

    /** Returns the workflow instance UUID (primary key). */
    public UUID workflowInstanceId() {
        return workflowInstanceId;
    }

    /** Returns the business workflow ID (e.g., {@code "order-abc"}). */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the current run ID (changes on manual retry). */
    public UUID runId() {
        return runId;
    }

    /** Returns the workflow type name. */
    public String workflowType() {
        return workflowType;
    }

    /** Returns the task queue name. */
    public String taskQueue() {
        return taskQueue;
    }

    /** Returns the owning service name. */
    public String serviceName() {
        return serviceName;
    }
}
