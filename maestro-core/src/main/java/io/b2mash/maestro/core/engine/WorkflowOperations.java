package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.retry.RetryUntilOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Internal contract for durable workflow operations.
 *
 * <p>{@link io.b2mash.maestro.core.context.WorkflowContext} delegates its public
 * workflow API methods (sleep, awaitSignal, parallel, etc.) to an
 * implementation of this interface. This separation keeps the context
 * lean while encapsulating infrastructure access (store, locks, parking)
 * in the engine layer.
 *
 * <p>This interface is {@code sealed} — only {@link DefaultWorkflowOperations}
 * may implement it. Workflow authors interact via
 * {@link io.b2mash.maestro.core.context.WorkflowContext}, not this interface directly.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be safe for use from a single workflow virtual
 * thread. Concurrent access from multiple workflows uses separate instances.
 *
 * @see DefaultWorkflowOperations
 * @see io.b2mash.maestro.core.context.WorkflowContext
 */
public sealed interface WorkflowOperations permits DefaultWorkflowOperations {

    /**
     * Durably sleeps for the specified duration.
     *
     * <p>Persists a timer to the workflow store, parks the virtual thread,
     * and resumes when the timer fires. Survives JVM restarts.
     *
     * <p>During replay, a previously completed sleep is skipped instantly.
     *
     * <p>If an operator cancels the timer while the workflow is waiting on it
     * (or, on replay, previously waited on it), this call throws
     * {@link io.b2mash.maestro.core.exception.TimerCancelledException}
     * instead of returning — deterministically, on the live path and on
     * every later replay. Uncaught, it propagates like any other exception:
     * saga compensation runs (if registered) and the instance ends
     * {@code FAILED}. This also affects {@link #retryUntil}, whose backoff
     * delay is implemented with this method — a cancelled backoff sleep
     * surfaces the same way, out of {@code retryUntil} rather than as a
     * {@link io.b2mash.maestro.core.exception.RetryExhaustedException}.
     *
     * @param duration the duration to sleep
     * @throws io.b2mash.maestro.core.exception.TimerCancelledException
     *         if an operator cancels the timer while the workflow is waiting on it
     */
    void sleep(Duration duration);

    /**
     * Waits for a named signal to be delivered to this workflow.
     *
     * <p>Checks for already-arrived signals first (self-recovery pattern).
     * If none found, parks the virtual thread until a signal arrives or
     * the timeout elapses.
     *
     * <p>During replay, a previously received signal is returned instantly.
     *
     * @param signalName the signal name to wait for (e.g., {@code "payment.result"})
     * @param type       the expected payload type
     * @param timeout    maximum time to wait
     * @param <T>        the payload type
     * @return the signal payload
     * @throws io.b2mash.maestro.core.exception.SignalTimeoutException if the timeout elapses
     */
    <T> T awaitSignal(String signalName, Class<T> type, Duration timeout);

    /**
     * Collects exactly {@code count} signals with the given name.
     *
     * <p>Useful for quorum patterns (e.g., "wait for 3 out of 5 approvals").
     * Each signal consumption is individually memoized for replay.
     *
     * @param signalName the signal name to collect
     * @param type       the expected payload type
     * @param count      the number of signals to collect
     * @param timeout    maximum total time to wait for all signals
     * @param <T>        the payload type
     * @return the collected signal payloads in order of receipt
     * @throws io.b2mash.maestro.core.exception.SignalTimeoutException if the timeout elapses
     */
    <T> List<T> collectSignals(String signalName, Class<T> type, int count, Duration timeout);

    /**
     * Executes multiple tasks in parallel, each on its own virtual thread.
     *
     * <p>Each branch gets an independent sequence space for memoization.
     * On replay, each branch replays its own events independently,
     * regardless of execution order.
     *
     * <h2>Limits</h2>
     * <ul>
     *   <li><b>999 sequence slots per branch.</b> Branch {@code i} of a fork at
     *       parent sequence {@code p} draws from the block starting at
     *       {@code p * 1000 + (i + 1) * 1000}; the 1000th slot throws
     *       {@link io.b2mash.maestro.core.exception.BranchSequenceExhaustedException}
     *       instead of spilling into the sibling's block. Most steps take one
     *       slot; {@code sleep()} takes two.</li>
     *   <li><b>Depth.</b> Every fork level multiplies the sequence by 1000 and
     *       sequence numbers are 32-bit, so only a few levels fit. A fork that
     *       cannot be sequenced throws
     *       {@link io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException}
     *       before it consumes a slot. See {@code docs/concepts.md} § Parallel
     *       Execution → Depth limit for which shapes fit.</li>
     * </ul>
     * <p>Both are ordinary {@link io.b2mash.maestro.core.exception.MaestroException}s,
     * not engine control-flow signals: they fail the branch (rethrown once every
     * branch has joined) and, uncaught, fail the workflow, which runs its
     * registered compensations.
     *
     * @param tasks the tasks to execute in parallel
     * @param <T>   the result type
     * @return the results in the same order as the input tasks
     * @throws io.b2mash.maestro.core.exception.BranchSequenceExhaustedException
     *         if a branch takes a 1000th sequence slot
     * @throws io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException
     *         if the fork's branch blocks would overflow the sequence space
     */
    <T> List<T> parallel(List<Callable<T>> tasks);

    /**
     * Returns the current time, memoized for deterministic replay.
     *
     * <p>Workflow code must use this instead of {@code Instant.now()} or
     * {@code LocalDateTime.now()} to maintain determinism across replays.
     *
     * @return the current time (live) or the stored time (replay)
     */
    Instant currentTime();

    /**
     * Returns a new UUID string, memoized for deterministic replay.
     *
     * <p>Workflow code must use this instead of {@code UUID.randomUUID()}
     * to maintain determinism across replays.
     *
     * @return a UUID string (live) or the stored UUID string (replay)
     */
    String randomUUID();

    /**
     * Polls a supplier until a predicate is satisfied, with durable backoff.
     *
     * <p>Each iteration calls the supplier (which should be a memoized activity
     * call), tests the result against the predicate, and if unsatisfied, durably
     * sleeps with exponential backoff before retrying. All operations (supplier
     * call, time checks, sleeps) are memoized — the entire loop replays correctly
     * across JVM restarts.
     *
     * <p><b>Important:</b> The {@code supplier} must be a memoized operation
     * (typically an activity call through the proxy). Passing a raw lambda
     * that performs I/O will break deterministic replay.
     *
     * @param supplier  the operation to poll (should be a memoized activity call)
     * @param predicate the condition to satisfy
     * @param options   retry configuration (attempts, duration, backoff)
     * @param <T>       the result type
     * @return the first result that satisfies the predicate
     * @throws io.b2mash.maestro.core.exception.RetryExhaustedException if all attempts
     *         are exhausted or the maximum duration is exceeded
     */
    <T> T retryUntil(Supplier<T> supplier, Predicate<T> predicate, RetryUntilOptions options);

    /**
     * Resolves a memoized change-branching decision.
     *
     * <p>The first live evaluation records {@code maxSupported} durably as a
     * {@link io.b2mash.maestro.core.model.EventType#VERSION_MARKER} event at the
     * current sequence slot and returns it; every replay returns the recorded
     * value, regardless of the code's current {@code maxSupported}. A history
     * written before this change-id existed resolves to
     * {@link io.b2mash.maestro.core.context.WorkflowContext#DEFAULT_VERSION}
     * <em>without</em> consuming a sequence slot, so inserting a
     * {@code version()} call into existing code leaves old instances' histories
     * byte-stable.
     *
     * @param changeId     stable identifier for this change, unique within the
     *                     workflow definition
     * @param minSupported the lowest version the running code still carries
     * @param maxSupported the highest version the running code carries
     * @return the version this instance is bound to
     * @throws io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException
     *         if the resolved version is below {@code minSupported}
     * @throws IllegalArgumentException if {@code changeId} is blank,
     *         {@code maxSupported} is negative, or
     *         {@code minSupported > maxSupported}
     * @see io.b2mash.maestro.core.context.WorkflowContext#version(String, int, int)
     */
    int version(String changeId, int minSupported, int maxSupported);

    /**
     * Pushes a compensation action onto the compensation stack.
     *
     * <p>On workflow failure, compensations are executed in LIFO order
     * (most recent first) to undo completed activities. The stack is
     * rebuilt during replay as the workflow re-executes.
     *
     * <p><b>Inside a {@code parallel()} branch</b> the action is not pushed
     * immediately: it is buffered per branch and pushed when the fork joins, in
     * branch-index order (not finish order), whether the fork returns or
     * throws. So until the enclosing {@code parallel()} returns, the action is
     * not yet on the stack; and if the fork's join is interrupted it is
     * dropped (with a WARN naming it).
     *
     * @param compensation the compensation action to register
     */
    void addCompensation(Runnable compensation);

    /**
     * Pushes a named compensation action onto the compensation stack.
     *
     * <p>The step name is used for logging and event recording. This
     * overload is used by the {@code @Compensate} integration in the
     * activity proxy.
     *
     * <p>The same branch-buffering caveat as {@link #addCompensation(Runnable)}
     * applies inside a {@code parallel()} branch: pushed at the fork's join, in
     * branch-index order.
     *
     * @param stepName     the compensation step name for logging and events
     * @param compensation the compensation action to register
     */
    void addCompensation(String stepName, Runnable compensation);
}
