package io.b2mash.maestro.core.context;

import io.b2mash.maestro.core.engine.WorkflowOperations;
import io.b2mash.maestro.core.retry.RetryUntilOptions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
 * to that thread via {@link ThreadLocal}. ThreadLocals do not inherit
 * to child virtual threads created via {@code Thread.ofVirtual()}, so
 * parallel branches must explicitly bind their own context. The sequence
 * counter uses {@link AtomicInteger} as a defensive measure against
 * accidental misuse from parallel branches.
 *
 * <h2>Workflow API</h2>
 * <p>Workflow authors interact with the engine through methods on this class:
 * <pre>{@code
 * var workflow = WorkflowContext.current();
 * workflow.sleep(Duration.ofMinutes(5));
 * PaymentResult result = workflow.awaitSignal("payment.result",
 *         PaymentResult.class, Duration.ofHours(1));
 * }</pre>
 *
 * <p>These methods delegate to a {@link WorkflowOperations} instance provided
 * by the {@code WorkflowExecutor}. When constructed without operations
 * (e.g., in tests), calling workflow API methods throws {@link IllegalStateException}.
 */
public final class WorkflowContext {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowContext.class);
    private static final ThreadLocal<WorkflowContext> CURRENT = new ThreadLocal<>();

    private final UUID workflowInstanceId;
    private final String workflowId;
    private final UUID runId;
    private final String workflowType;
    private final String taskQueue;
    private final String serviceName;
    private final AtomicInteger sequenceCounter;
    private volatile boolean replaying;
    private final @Nullable WorkflowOperations operations;

    /**
     * Creates a new workflow context without workflow operations.
     *
     * <p>Use this constructor in tests or when only sequence/replay
     * tracking is needed. Calling workflow API methods (sleep, awaitSignal,
     * etc.) will throw {@link IllegalStateException}.
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
        this(workflowInstanceId, workflowId, runId, workflowType, taskQueue,
                serviceName, initialSequence, replaying, null);
    }

    /**
     * Creates a new workflow context with workflow operations support.
     *
     * <p>Used by the {@code WorkflowExecutor} to create fully operational
     * contexts where workflow API methods (sleep, awaitSignal, etc.) are available.
     *
     * @param workflowInstanceId the workflow instance UUID (primary key)
     * @param workflowId         the business workflow ID (e.g., {@code "order-abc"})
     * @param runId              the current run ID (changes on manual retry)
     * @param workflowType       the workflow type name
     * @param taskQueue          the task queue name
     * @param serviceName        the owning service name
     * @param initialSequence    the starting sequence number (0 for new, higher for resumed)
     * @param replaying          whether this execution is replaying stored events
     * @param operations         the workflow operations delegate, or {@code null} for test contexts
     */
    public WorkflowContext(
            UUID workflowInstanceId,
            String workflowId,
            UUID runId,
            String workflowType,
            String taskQueue,
            String serviceName,
            int initialSequence,
            boolean replaying,
            @Nullable WorkflowOperations operations
    ) {
        this.workflowInstanceId = workflowInstanceId;
        this.workflowId = workflowId;
        this.runId = runId;
        this.workflowType = workflowType;
        this.taskQueue = taskQueue;
        this.serviceName = serviceName;
        this.sequenceCounter = new AtomicInteger(initialSequence);
        this.replaying = replaying;
        this.operations = operations;
    }

    // ── ThreadLocal management ─────────────────────────────────────────

    /**
     * Binds the given context to the current thread.
     *
     * <p>Call this at the top of each virtual-thread body that executes
     * workflow or branch logic. Always pair with {@link #clear()} in a
     * {@code finally} block.
     *
     * @param context the context to bind
     */
    public static void bind(WorkflowContext context) {
        var existing = CURRENT.get();
        if (existing != null) {
            logger.warn("Overwriting existing WorkflowContext for workflow '{}' — potential leak. "
                    + "Ensure clear() is called before binding a new context.", existing.workflowId());
        }
        CURRENT.set(context);
    }

    /**
     * Returns the context bound to the current thread.
     *
     * @return the current workflow context
     * @throws IllegalStateException if no context is bound
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
     * <p>Must be called in a {@code finally} block to prevent ThreadLocal leaks
     * on virtual threads.
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

    /**
     * Sets the sequence counter to a specific value.
     *
     * <p><b>Engine-internal method — do not call from workflow code.</b>
     * Calling this from a workflow method will corrupt the memoization
     * sequence and break deterministic replay.
     *
     * <p>Used by the engine for parallel branch contexts, where each
     * branch needs its own sequence space.
     *
     * @param sequence the sequence value to set
     */
    public void setSequence(int sequence) {
        sequenceCounter.set(sequence);
    }

    // ── Workflow API methods (delegate to operations) ─────────────────

    /**
     * Convenience alias for {@link #current()}.
     *
     * <p>Enables the idiomatic workflow pattern:
     * <pre>{@code
     * var workflow = WorkflowContext.workflow();
     * workflow.sleep(Duration.ofMinutes(5));
     * }</pre>
     *
     * @return the current workflow context
     * @throws IllegalStateException if no context is bound
     */
    public static WorkflowContext workflow() {
        return current();
    }

    /**
     * Durably sleeps for the specified duration.
     *
     * @param duration the duration to sleep
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#sleep(Duration)
     */
    public void sleep(Duration duration) {
        requireOperations().sleep(duration);
    }

    /**
     * Waits for a named signal to be delivered to this workflow.
     *
     * @param signalName the signal name to wait for
     * @param type       the expected payload type
     * @param timeout    maximum time to wait
     * @param <T>        the payload type
     * @return the signal payload
     * @throws io.b2mash.maestro.core.exception.SignalTimeoutException if the timeout elapses
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#awaitSignal(String, Class, Duration)
     */
    public <T> T awaitSignal(String signalName, Class<T> type, Duration timeout) {
        return requireOperations().awaitSignal(signalName, type, timeout);
    }

    /**
     * Collects exactly {@code count} signals with the given name.
     *
     * @param signalName the signal name to collect
     * @param type       the expected payload type
     * @param count      the number of signals to collect
     * @param timeout    maximum total time to wait
     * @param <T>        the payload type
     * @return the collected signal payloads
     * @throws io.b2mash.maestro.core.exception.SignalTimeoutException if the timeout elapses
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#collectSignals(String, Class, int, Duration)
     */
    public <T> List<T> collectSignals(String signalName, Class<T> type, int count, Duration timeout) {
        return requireOperations().collectSignals(signalName, type, count, timeout);
    }

    /**
     * Executes multiple tasks in parallel on separate virtual threads.
     *
     * @param tasks the tasks to execute in parallel
     * @param <T>   the result type
     * @return the results in the same order as the input tasks
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#parallel(List)
     */
    public <T> List<T> parallel(List<Callable<T>> tasks) {
        return requireOperations().parallel(tasks);
    }

    /**
     * Returns the current time, memoized for deterministic replay.
     *
     * <p>Use this instead of {@code Instant.now()} in workflow code.
     *
     * @return the current time (live) or the stored time (replay)
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#currentTime()
     */
    public Instant currentTime() {
        return requireOperations().currentTime();
    }

    /**
     * Returns a new UUID string, memoized for deterministic replay.
     *
     * <p>Use this instead of {@code UUID.randomUUID()} in workflow code.
     *
     * @return a UUID string
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#randomUUID()
     */
    public String randomUUID() {
        return requireOperations().randomUUID();
    }

    /**
     * Polls a supplier until a predicate is satisfied, with durable backoff.
     *
     * <p>The supplier should be a memoized activity call. Each backoff interval
     * creates a durable timer, so the entire retry loop survives JVM restarts.
     *
     * @param supplier  the operation to poll (should be a memoized activity call)
     * @param predicate the condition to satisfy
     * @param options   retry configuration
     * @param <T>       the result type
     * @return the first result that satisfies the predicate
     * @throws io.b2mash.maestro.core.exception.RetryExhaustedException if exhausted
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#retryUntil(Supplier, Predicate, RetryUntilOptions)
     */
    public <T> T retryUntil(Supplier<T> supplier, Predicate<T> predicate, RetryUntilOptions options) {
        return requireOperations().retryUntil(supplier, predicate, options);
    }

    /**
     * Pushes a compensation action onto the compensation stack.
     *
     * @param compensation the compensation action
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#addCompensation(Runnable)
     */
    public void addCompensation(Runnable compensation) {
        requireOperations().addCompensation(compensation);
    }

    /**
     * Pushes a named compensation action onto the compensation stack.
     *
     * <p>The step name is used for logging and event recording. This
     * overload is used by the {@code @Compensate} activity proxy
     * integration.
     *
     * @param stepName     the compensation step name for logging and events
     * @param compensation the compensation action
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#addCompensation(String, Runnable)
     */
    public void addCompensation(@org.jspecify.annotations.NonNull String stepName,
                               @org.jspecify.annotations.NonNull Runnable compensation) {
        requireOperations().addCompensation(stepName, compensation);
    }

    private WorkflowOperations requireOperations() {
        if (operations == null) {
            throw new IllegalStateException(
                    "Workflow operations not configured. "
                            + "Workflow API methods (sleep, awaitSignal, etc.) require a fully "
                            + "configured context created by the WorkflowExecutor.");
        }
        return operations;
    }
}
