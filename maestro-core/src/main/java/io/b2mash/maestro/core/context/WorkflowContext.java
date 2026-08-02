package io.b2mash.maestro.core.context;

import io.b2mash.maestro.core.engine.WorkflowOperations;
import io.b2mash.maestro.core.retry.RetryUntilOptions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ScopedValue;
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
 *   <li>The {@code WorkflowExecutor} creates a context and runs the
 *       workflow method inside a {@link ScopedValue} scope via
 *       {@code ScopedValue.where(scopedValue(), ctx).run(...)}.</li>
 *   <li>The workflow method runs. Each activity call reads the context
 *       via {@link #current()} and increments the sequence.</li>
 *   <li>When the scope exits (success or failure), the context is
 *       automatically unbound — no manual cleanup required.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>Each workflow runs on its own virtual thread. The context is bound
 * to that thread via {@link ScopedValue}. ScopedValues do not inherit
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
    private static final ScopedValue<WorkflowContext> CURRENT = ScopedValue.newInstance();

    /**
     * The version {@link #version(String, int, int)} returns for a change-id
     * that the workflow's history predates — no marker was ever recorded for it.
     *
     * <p>Guard the pre-change branch with {@code if (version == DEFAULT_VERSION)}
     * (or, equivalently, {@code version < 1}) for as long as instances started
     * before the change may still be running.
     */
    public static final int DEFAULT_VERSION = -1;

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

    // ── ScopedValue management ─────────────────────────────────────────

    /**
     * Returns the {@link ScopedValue} used to bind workflow contexts.
     *
     * <p>Callers use this to establish a scope:
     * <pre>{@code
     * ScopedValue.where(WorkflowContext.scopedValue(), ctx)
     *     .run(() -> { ... });
     * }</pre>
     *
     * @return the scoped value instance
     */
    public static ScopedValue<WorkflowContext> scopedValue() {
        return CURRENT;
    }

    /**
     * Returns the context bound in the current scope.
     *
     * @return the current workflow context
     * @throws IllegalStateException if no context is bound (not in a workflow scope)
     */
    public static WorkflowContext current() {
        return CURRENT.orElseThrow(() -> new IllegalStateException(
                "No WorkflowContext bound to current scope. "
                        + "Activity methods can only be called from within a workflow execution."));
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
     * @throws io.b2mash.maestro.core.exception.TimerCancelledException
     *         if an operator cancels the timer while the workflow is waiting on it
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
     * Branches on a memoized version decision, so that changing this workflow's
     * code does not change the path of instances already running.
     *
     * <h2>Why this exists</h2>
     * <p>Recovery re-runs the workflow method against the <em>current</em>
     * code. Edit the code while long-lived instances are in flight and a
     * replaying instance takes the new path from wherever it resumed — half its
     * work done the old way, the rest done the new way. {@code version()} makes
     * the choice of path a durable decision like any other memoized step.
     *
     * <h2>How it behaves</h2>
     * <ul>
     *   <li><b>Live (first evaluation):</b> records {@code maxSupported} as a
     *       {@code VERSION_MARKER} event at the current sequence slot and
     *       returns it.</li>
     *   <li><b>Replay:</b> returns the <em>recorded</em> version forever, even
     *       after the code's {@code maxSupported} moves on. That asymmetry is
     *       the whole point.</li>
     *   <li><b>Histories that predate the change:</b> resolve to
     *       {@link #DEFAULT_VERSION} ({@value #DEFAULT_VERSION}) without
     *       consuming a sequence slot, so introducing a {@code version()} call
     *       into existing code leaves old instances' event logs unshifted.</li>
     *   <li><b>Repeated calls</b> with the same {@code changeId} in one run
     *       return the same value and record nothing further.</li>
     * </ul>
     *
     * <h2>The pattern</h2>
     * <pre>{@code
     * // Step 1 — ship the change behind a version gate:
     * var v = workflow.version("shipping-v2", WorkflowContext.DEFAULT_VERSION, 1);
     * if (v == WorkflowContext.DEFAULT_VERSION) {
     *     shipping.dispatch(order);          // what in-flight instances recorded
     * } else {
     *     shipping.dispatchWithCarrier(order, carrier);  // the new branch
     * }
     *
     * // Step 2 — once no pre-change instance can still be running, drop the old
     * // branch and raise the floor:
     * workflow.version("shipping-v2", 1, 1);
     * shipping.dispatchWithCarrier(order, carrier);
     * }</pre>
     *
     * <h2>Raising {@code minSupported} too early</h2>
     * <p>If an instance's recorded version is below {@code minSupported}, the
     * running code no longer carries the branch that instance needs and the call
     * throws {@link io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException}
     * naming the changeId, the recorded version and the supported range. That is
     * an ordinary (deterministic) workflow failure: saga compensation runs if
     * registered, and the instance ends {@code FAILED}. Restore code carrying the
     * old branch and use the admin Retry action — retry clears the failure memos
     * but never the version marker, so the retried run replays the same recorded
     * version.
     *
     * <h2>{@code maxSupported} must be a code constant</h2>
     * <p>Never compute it — no config lookup, no feature flag, no environment
     * read. {@code DeterminismChecker} fingerprints
     * {@code sequence:eventType:stepName} and deliberately excludes the payload
     * (a different recorded version is a different history, not a divergent
     * path), so a workflow whose {@code maxSupported} varies between runs
     * passes the determinism check while recording a different version on every
     * new instance, with nothing to warn you.
     *
     * <h2>Parallel branches</h2>
     * <p>Resolve a changeId <em>before</em> forking branches that depend on it
     * and pass the value in. Branches share one per-run cache whose
     * {@code get} and {@code put} are not atomic, so two branches racing to be
     * the first resolver both miss the cache and <em>each writes its own
     * marker</em>, in its own branch's partitioned sequence space — there is no
     * single winner and no collision, and each slot replays deterministically.
     * What is nondeterministic is <em>how many</em> markers a run writes: on a
     * run where one branch populates the cache before the other peeks, only one
     * is recorded, so the event log differs between runs and
     * {@code DeterminismChecker} reports a fingerprint mismatch. A
     * {@code version()} call made inside a single branch is fine: it allocates
     * from that branch's own sequence block.
     *
     * @param changeId     a stable identifier for this change, unique within the
     *                     workflow definition (e.g. {@code "shipping-v2"})
     * @param minSupported the lowest version the running code still carries;
     *                     pass {@link #DEFAULT_VERSION} while pre-change
     *                     instances may still be running
     * @param maxSupported the highest version the running code carries
     * @return the version this instance is bound to — {@link #DEFAULT_VERSION}
     *         for a history that predates the change
     * @throws io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException
     *         if the resolved version is below {@code minSupported}
     * @throws IllegalArgumentException if {@code changeId} is blank,
     *         {@code maxSupported} is negative, or
     *         {@code minSupported > maxSupported}
     * @throws IllegalStateException if operations are not configured
     * @see WorkflowOperations#version(String, int, int)
     */
    public int version(String changeId, int minSupported, int maxSupported) {
        return requireOperations().version(changeId, minSupported, maxSupported);
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
