package io.maestro.core.engine;

import io.maestro.core.retry.RetryUntilOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Internal contract for durable workflow operations.
 *
 * <p>{@link io.maestro.core.context.WorkflowContext} delegates its public
 * workflow API methods (sleep, awaitSignal, parallel, etc.) to an
 * implementation of this interface. This separation keeps the context
 * lean while encapsulating infrastructure access (store, locks, parking)
 * in the engine layer.
 *
 * <p>This interface is {@code sealed} — only {@link DefaultWorkflowOperations}
 * may implement it. Workflow authors interact via
 * {@link io.maestro.core.context.WorkflowContext}, not this interface directly.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be safe for use from a single workflow virtual
 * thread. Concurrent access from multiple workflows uses separate instances.
 *
 * @see DefaultWorkflowOperations
 * @see io.maestro.core.context.WorkflowContext
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
     * @param duration the duration to sleep
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
     * @throws io.maestro.core.exception.SignalTimeoutException if the timeout elapses
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
     * @throws io.maestro.core.exception.SignalTimeoutException if the timeout elapses
     */
    <T> List<T> collectSignals(String signalName, Class<T> type, int count, Duration timeout);

    /**
     * Executes multiple tasks in parallel, each on its own virtual thread.
     *
     * <p>Each branch gets an independent sequence space for memoization.
     * On replay, each branch replays its own events independently,
     * regardless of execution order.
     *
     * @param tasks the tasks to execute in parallel
     * @param <T>   the result type
     * @return the results in the same order as the input tasks
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
     * @throws io.maestro.core.exception.RetryExhaustedException if all attempts
     *         are exhausted or the maximum duration is exceeded
     */
    <T> T retryUntil(Supplier<T> supplier, Predicate<T> predicate, RetryUntilOptions options);

    /**
     * Pushes a compensation action onto the compensation stack.
     *
     * <p>On workflow failure, compensations are executed in LIFO order
     * (most recent first) to undo completed activities. The stack is
     * rebuilt during replay as the workflow re-executes.
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
     * @param stepName     the compensation step name for logging and events
     * @param compensation the compensation action to register
     */
    void addCompensation(String stepName, Runnable compensation);
}
