package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Manages parked virtual threads for durable sleep and signal-await operations.
 *
 * <p>When a workflow calls {@code sleep()} or {@code awaitSignal()}, the
 * workflow's virtual thread needs to block cheaply until an external event
 * (timer fire, signal delivery) resumes it. This class provides the
 * park/unpark mechanism using {@link CompletableFuture}.
 *
 * <p>Virtual threads block on {@link CompletableFuture#join()} without
 * consuming a platform thread — the carrier thread is released back to
 * the ForkJoinPool.
 *
 * <h2>Key Format</h2>
 * <p>Keys encode the workflow identity and parking reason:
 * <ul>
 *   <li>{@code "{workflowId}:signal:{signalName}"} — waiting for a signal</li>
 *   <li>{@code "{workflowId}:timer:{timerId}"} — waiting for a timer</li>
 * </ul>
 *
 * <h2>Ownership</h2>
 * <p>Waiters and permits are stored under the pair <em>(workflow id, key)</em>,
 * never under the key string alone. A signal name may contain {@code ':'}, so
 * two workflows can spell the same key: workflow {@code order} awaiting signal
 * {@code 1:signal:go} and workflow {@code order:signal:1} awaiting {@code go}
 * both spell {@code "order:signal:1:signal:go"}. Keyed by the string, the
 * second park found the slot occupied and its workflow failed; keyed by the
 * pair, they are two slots. For the same reason the per-workflow operations —
 * {@link #clearPending}, {@link #abandonWorkflow} and the terminate poison —
 * match the workflow id exactly and never a key prefix:
 * {@code "order:1:signal:go"} starts with {@code "order:"}, so a prefix match
 * would let ending or terminating workflow {@code order} reach
 * {@code order:1}. Production parks and wakes pass the workflow id with the
 * key ({@link #park(String, String)},
 * {@link #parkWithTimeout(String, String, Duration)},
 * {@link #wake(String, String)}).
 *
 * <h2>Wake Permits</h2>
 * <p>A {@link #wake(String, String)} that finds no registered waiter stores a
 * <b>one-shot permit</b> — but only for a workflow that runs on this node, as
 * the {@code runsHere} predicate (the executor's running-workflow map) says,
 * checked before the put and again after it. The next park on that key
 * consumes the permit and returns immediately. This closes the race where a
 * wake source fires between a run on this node announcing its intent to wait
 * (e.g. status {@code WAITING_TIMER}) and the park actually registering —
 * without permits, a timer fired in that window would never be re-fired and
 * the workflow would stall until its wake-recheck interval.
 *
 * <p>A wake for a workflow that does not run here stores nothing (finding
 * 3.2). No wake is lost by that: a waiter on another node is woken by
 * {@code NOTIFY} or its own wake-recheck, and a workflow that runs nowhere yet
 * reads its durable signal or timer row before its first park. A permit
 * carries no payload — every park's caller re-reads the store — so it is a
 * key and its owning workflow id. Once the gate holds, every stored permit belongs to
 * a run on this node and is dropped at that run's next park on the key, its
 * end ({@link #clearPending}), a terminate ({@link #abandonWorkflow}) or
 * {@link #shutdown()}. Each wake stores a permit object of its own, so the
 * re-check of a wake can only ever remove the permit that wake stored — never
 * one a later wake stored for a new run of the same workflow id.
 *
 * <h2>Shutdown</h2>
 * <p>{@link #shutdown()} abandons every waiter with an
 * {@link ExecutorShutdownException} — a signal the executor tells apart from a
 * workflow failure, so a workflow parked at shutdown keeps its
 * {@code WAITING_*} status and stays recoverable rather than being failed and
 * compensated. The shutdown state is sticky: a park that registers after
 * shutdown began throws immediately instead of blocking out its timeout, which
 * is what bounds how long {@code shutdown()} has to wait.
 *
 * <h2>Terminate</h2>
 * <p>{@link #abandonWorkflow(String, String)} is the per-workflow analogue:
 * every waiter belonging to one workflow is abandoned with a
 * {@link WorkflowTerminatedException}, and the poison is likewise sticky so a
 * park racing the sweep fails fast. Unlike shutdown, the abandoned run is not
 * recoverable — its instance row is already {@code TERMINATED} — and the poison
 * is per-workflow and cleared by {@link #clearTerminated(String)} when the run
 * unwinds, so this lot stays usable for every other workflow.
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. The underlying {@link ConcurrentHashMap}
 * handles concurrent park/unpark from different virtual threads.
 */
final class ParkingLot {

    private static final Logger logger = LoggerFactory.getLogger(ParkingLot.class);

    /** Reason marker for a terminate that carried no operator-supplied reason. */
    private static final String NO_REASON = "";

    /**
     * A parking slot: the workflow it belongs to and the key within it. Two
     * workflows whose keys spell the same string still get two slots.
     */
    private record ParkKey(String workflowId, String key) {}

    /** A waiter registered at a slot. */
    private record Waiter(CompletableFuture<Object> future) {}

    /**
     * A stored wake permit: the payload a park returns. Deliberately not a
     * record — equality is identity, so {@code pendingWakes.remove(slot, permit)}
     * removes this permit and no other, however many permits carry the same
     * payload.
     */
    private static final class Permit {
        final @Nullable Object payload;

        Permit(@Nullable Object payload) {
            this.payload = payload;
        }
    }

    private final ConcurrentHashMap<ParkKey, Waiter> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ParkKey, Permit> pendingWakes = new ConcurrentHashMap<>();

    /**
     * Whether a workflow runs on this node — the executor's
     * {@code runningWorkflows::containsKey}. Must be thread-safe and
     * non-blocking: {@link #wake(String, String)} calls it on whatever thread
     * the wake arrives on.
     */
    private final Predicate<String> runsHere;

    /**
     * Sticky per-workflow terminate poison: {@code workflowId → reason}. Mirrors
     * {@link #shuttingDown} but scoped to one workflow, so a park that registers
     * just after {@link #abandonWorkflow} swept the lot throws immediately
     * instead of blocking out its timeout. Normally empty, so the common park
     * path costs one {@code get}; entries are dropped by
     * {@link #clearTerminated} when the run ends.
     */
    private final ConcurrentHashMap<String, String> terminatedWorkflows = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    /**
     * Builds a lot under which every workflow counts as running on this node,
     * so every {@link #wake(String, String)} that finds no waiter stores a
     * permit — for tests that build a lot or a {@code SignalManager} directly.
     */
    ParkingLot() {
        this(workflowId -> true);
    }

    /**
     * Builds a lot that stores a wake permit only for a workflow
     * {@code runsHere} accepts.
     *
     * @param runsHere whether a workflow (by business ID) runs on this node;
     *                 thread-safe and non-blocking
     */
    ParkingLot(Predicate<String> runsHere) {
        this.runsHere = runsHere;
    }

    /**
     * Test seam: {@link #park(String, String)} with the workflow id taken to be
     * the key's text before its first {@code ':'}. Production code passes the
     * workflow id itself — an id may contain {@code ':'}.
     *
     * @param key the parking key (e.g., {@code "order-abc:signal:payment.result"})
     * @return the permit's payload ({@code null} for every production wake)
     * @throws ExecutorShutdownException if the executor is shutting down
     */
    @Nullable Object park(String key) {
        return park(ownerOf(key), key);
    }

    /**
     * Parks the current virtual thread until a wake reaches {@code key}.
     *
     * <p>Creates a {@link CompletableFuture}, registers it under the given key
     * for {@code workflowId}, and blocks on {@link CompletableFuture#join()}.
     * The virtual thread yields its carrier thread while parked.
     *
     * @param workflowId the business ID of the workflow parking
     * @param key        the parking key, prefixed {@code workflowId + ":"}
     * @return the payload of the wake: {@code null} for every
     *         {@link #wake(String, String)}; only the {@link #unpark(String, Object)}
     *         test seam delivers one
     * @throws ExecutorShutdownException   if the executor is shutting down
     * @throws WorkflowTerminatedException if the workflow is terminated
     */
    @Nullable Object park(String workflowId, String key) {
        var slot = new ParkKey(workflowId, key);
        var waiter = register(slot);
        try {
            // Consume a permit stored by a wake that raced ahead of this
            // park. Checked AFTER registering the waiter: a wake running
            // now either sees the waiter (completes it) or stored a permit
            // before we registered (we consume it here) — never lost.
            var permit = pendingWakes.remove(slot);
            if (permit != null) {
                return permit.payload;
            }
            return waiter.future().join();
        } catch (CompletionException e) {
            throw rethrow(key, e);
        } finally {
            futures.remove(slot, waiter);
        }
    }

    /**
     * Test seam: {@link #parkWithTimeout(String, String, Duration)} with the
     * workflow id taken to be the key's text before its first {@code ':'}.
     *
     * @param key     the parking key
     * @param timeout maximum time to wait
     * @return the permit's payload ({@code null} for every production wake)
     * @throws TimeoutException          if the timeout elapses before a wake
     * @throws ExecutorShutdownException if the executor is shutting down
     */
    @Nullable Object parkWithTimeout(String key, Duration timeout) throws TimeoutException {
        return parkWithTimeout(ownerOf(key), key, timeout);
    }

    /**
     * Parks the current virtual thread with a timeout.
     *
     * <p>Behaves like {@link #park(String, String)} but throws
     * {@link TimeoutException} if the specified duration elapses before a wake
     * reaches {@code key}.
     *
     * @param workflowId the business ID of the workflow parking
     * @param key        the parking key, prefixed {@code workflowId + ":"}
     * @param timeout    maximum time to wait
     * @return the payload of the wake: {@code null} for every
     *         {@link #wake(String, String)}
     * @throws TimeoutException            if the timeout elapses before a wake
     * @throws ExecutorShutdownException   if the executor is shutting down
     * @throws WorkflowTerminatedException if the workflow is terminated
     */
    @Nullable Object parkWithTimeout(String workflowId, String key, Duration timeout) throws TimeoutException {
        var slot = new ParkKey(workflowId, key);
        var waiter = register(slot);
        try {
            // See park(): consume a permit from a wake that raced ahead
            var permit = pendingWakes.remove(slot);
            if (permit != null) {
                return permit.payload;
            }
            return waiter.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while parked at key: " + key);
        } catch (java.util.concurrent.ExecutionException e) {
            throw rethrow(key, e);
        } finally {
            futures.remove(slot, waiter);
        }
    }

    /**
     * The workflow id the single-argument test seams assume for a key: its
     * text before the first {@code ':'}, or the whole key if it has none.
     */
    private static String ownerOf(String key) {
        var colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    /**
     * Registers a waiter's future under the given slot.
     *
     * <p>The shutdown and terminate checks happen <em>after</em> registration,
     * mirroring the permit protocol: a concurrent {@link #shutdown()} or
     * {@link #abandonWorkflow} either sees this waiter and completes it, or set
     * its flag before we read it — never neither.
     *
     * @param slot the workflow the waiter belongs to and its parking key
     * @return the registered waiter
     * @throws IllegalStateException       if the slot is already occupied
     * @throws ExecutorShutdownException   if the executor is shutting down
     * @throws WorkflowTerminatedException if the workflow is terminated
     */
    private Waiter register(ParkKey slot) {
        var waiter = new Waiter(new CompletableFuture<>());
        var existing = futures.putIfAbsent(slot, waiter);
        if (existing != null) {
            throw new IllegalStateException(
                    "Parking key '%s' already occupied — duplicate park call would orphan the existing waiter"
                            .formatted(slot.key()));
        }
        if (shuttingDown) {
            futures.remove(slot, waiter);
            throw shutdownSignal(slot.key());
        }
        var reason = terminatedWorkflows.get(slot.workflowId());
        if (reason != null) {
            futures.remove(slot, waiter);
            throw terminateSignal(slot.workflowId(), reason);
        }
        return waiter;
    }

    /**
     * Unwraps a completion wrapper thrown by {@code join()}/{@code get()} and
     * rethrows its cause, so a shutdown reaches the workflow as an
     * {@link ExecutorShutdownException} rather than a wrapper the executor
     * would read as a failure.
     *
     * <p>{@link ExecutorShutdownException} is an {@link Error}, not a
     * {@link RuntimeException} — {@link CompletableFuture#completeExceptionally}
     * still wraps it the same way, so the {@code Error} branch is checked
     * first here; falling through to the {@code RuntimeException} branch
     * would otherwise re-wrap it and hide it from the caller's
     * {@code catch (ExecutorShutdownException e)}.
     *
     * @param key the parking key, for the fallback message
     * @param e   the wrapper thrown by the future
     * @return never returns normally — declared so callers can write
     *         {@code throw rethrow(...)}; the {@code Error} case throws directly
     */
    private static RuntimeException rethrow(String key, Exception e) {
        var cause = e.getCause();
        if (cause instanceof Error err) {
            throw err;
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException("Unexpected exception while parked at key: " + key, cause);
    }

    /**
     * Wakes the waiter parked at {@code key}, or stores a one-shot permit for
     * it — only if {@code workflowId} runs on this node. The production wake
     * path: every signal delivery, cross-node notification, timer fire or
     * cancel and await-deadline transition comes through here.
     *
     * <ol>
     *   <li>A waiter registered at {@code key} is completed with {@code null},
     *       whatever {@code runsHere} says: only the node running the workflow
     *       registers one.</li>
     *   <li>Otherwise, if the workflow does not run here, nothing is stored. The
     *       wake is not lost: the signal or timer row is already durable, and
     *       the node that runs (or will run) the workflow reads it before it
     *       parks, or is woken by {@code NOTIFY} or its wake-recheck.</li>
     *   <li>Otherwise a permit is stored and handed to a park that registered
     *       meanwhile.</li>
     *   <li>{@code runsHere} is asked again. A run that ended between the first
     *       check and the put may already have passed its own permit cleanup,
     *       so the permit is removed here; a run still registered at this
     *       check removes it itself when it ends (its registration is dropped
     *       before its cleanup runs).</li>
     * </ol>
     *
     * <p>Thread-safe and non-blocking; may be called from any thread, including
     * a network event-loop thread.
     *
     * <p>Each call stores a permit object of its own and step 4 removes only
     * that object, so a wake whose re-check finds the run gone can never remove
     * a permit a later wake stored for a new run of the same workflow id.
     *
     * @param workflowId the business ID of the workflow the key belongs to
     * @param key        the parking key, prefixed {@code workflowId + ":"}
     */
    void wake(String workflowId, String key) {
        var slot = new ParkKey(workflowId, key);
        var waiter = futures.get(slot);
        if (waiter != null) {
            waiter.future().complete(null);
            logger.debug("Woke the waiter parked at key '{}'", key);
            return;
        }
        if (!runsHere.test(workflowId)) {
            logger.debug("No waiter parked at key '{}', and the workflow does not run on this node — "
                    + "no wake permit stored", key);
            return;
        }
        var permit = new Permit(null);
        storePermit(slot, permit);
        if (!runsHere.test(workflowId)) {
            // The run ended between the check above and the put: its
            // clearPending may have run before the put, so drop the permit
            // here — this wake's own permit, and no other.
            pendingWakes.remove(slot, permit);
            logger.debug("The run ended while the wake for key '{}' was being stored — wake permit dropped", key);
        } else {
            logger.debug("No waiter parked at key '{}' yet — stored a wake permit for a run on this node", key);
        }
    }

    /**
     * Ungated test seam: stores a permit whatever the predicate says, owned by
     * the key's text before its first {@code ':'}. Production code calls
     * {@link #wake(String, String)}.
     *
     * <p>Completes the {@link CompletableFuture} associated with the key,
     * causing the parked virtual thread to resume with the given payload. If no
     * thread is currently parked at this key, a one-shot permit is stored
     * (latest payload wins) — the next park on this key returns immediately
     * with that payload.
     *
     * @param key     the parking key
     * @param payload the result to deliver to the parked thread, may be {@code null}
     */
    void unpark(String key, @Nullable Object payload) {
        var slot = new ParkKey(ownerOf(key), key);
        var waiter = futures.get(slot);
        if (waiter != null) {
            waiter.future().complete(payload);
            logger.debug("Unparked workflow at key '{}'", key);
        } else {
            storePermit(slot, new Permit(payload));
            logger.debug("No parked workflow at key '{}' — stored wake permit", key);
        }
    }

    /**
     * Stores a permit at {@code slot} (latest wins), then closes the reverse
     * race: a park may have registered its future after the caller's lookup
     * but before the permit was stored — if so, hand the wake to it now (it
     * may already have consumed the permit itself; {@code remove()} makes the
     * hand-off exactly-once).
     */
    private void storePermit(ParkKey slot, Permit permit) {
        pendingWakes.put(slot, permit);
        var racedWaiter = futures.get(slot);
        if (racedWaiter != null) {
            var raced = pendingWakes.remove(slot);
            if (raced != null) {
                racedWaiter.future().complete(raced.payload);
            }
        }
    }

    /**
     * Test seam: returns whether a virtual thread of any workflow is currently
     * parked at the given key string. A linear scan; production never asks.
     *
     * @param key the parking key
     * @return {@code true} if a thread is parked at this key
     */
    boolean isParked(String key) {
        for (var slot : futures.keySet()) {
            if (slot.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Abandons every waiter because this node is shutting down, and refuses
     * further parks.
     *
     * <p>Each parked thread resumes by throwing {@link ExecutorShutdownException}
     * so it can exit promptly. That exception is the executor's signal to leave
     * the workflow's durable state alone: a workflow parked here keeps its
     * {@code WAITING_*} status, runs no compensation, and is recovered by
     * whichever node starts next.
     *
     * <p>The state is sticky — this lot is not reusable afterwards, which is
     * intentional: a park racing shutdown must fail fast rather than block out
     * its timeout and stall the drain.
     */
    void shutdown() {
        shuttingDown = true;
        futures.forEach((slot, waiter) -> {
            if (waiter.future().completeExceptionally(shutdownSignal(slot.key()))) {
                logger.debug("Abandoned park at key '{}' — executor is shutting down", slot.key());
            }
        });
        pendingWakes.clear();
    }

    /**
     * Abandons every waiter belonging to one workflow because that workflow has
     * been terminated, and refuses further parks for it.
     *
     * <p>The per-workflow analogue of {@link #shutdown()}, and sticky for the
     * same reason: a park that registers between the sweep and the run's unwind
     * must fail fast rather than block out its timeout. Unlike shutdown, the
     * abandoned run is <em>not</em> recoverable — {@code terminateWorkflow} has
     * already written {@code TERMINATED}, so
     * {@link WorkflowTerminatedException} tells the executor to write nothing
     * and run no compensation.
     *
     * <p>Only meaningful on the node that actually runs the workflow. A node
     * that terminates a workflow it does not own has nothing to sweep; the owner
     * converges via the terminal-status guards instead.
     *
     * <p>The poison is dropped by {@link #clearTerminated(String)} once the run
     * has unwound, so a later run of the same {@code workflowId} can park again.
     * A terminate that reaches the lot after the run has already ended (the
     * {@code runsHere} predicate, asked after the sweep, says it no longer runs
     * here) drops the poison itself, because the run's own
     * {@code clearTerminated} may have run before the poison was put.
     *
     * <p>Matches {@code workflowId} exactly: the waiters, permits and later
     * parks of a workflow whose id merely starts with {@code workflowId + ":"}
     * are left alone.
     *
     * @param workflowId the workflow whose parks are abandoned
     * @param reason     the operator-supplied termination reason, or {@code null}
     */
    void abandonWorkflow(String workflowId, @Nullable String reason) {
        terminatedWorkflows.put(workflowId, reason != null ? reason : NO_REASON);
        futures.forEach((slot, waiter) -> {
            if (slot.workflowId().equals(workflowId)
                    && waiter.future().completeExceptionally(terminateSignal(workflowId, reason))) {
                logger.debug("Abandoned park at key '{}' — workflow was terminated", slot.key());
            }
        });
        clearPending(workflowId);
        if (!runsHere.test(workflowId)) {
            // The run ended before or during this sweep — the terminate looked
            // it up, then it ended. Its own clearTerminated may have run before
            // the put above, so drop the poison here: nothing else would. A run
            // still registered at this check clears it itself when it ends (its
            // registration is dropped before its clearTerminated runs).
            terminatedWorkflows.remove(workflowId);
        }
    }

    /**
     * Drops the sticky terminate poison for a workflow, so a future run of the
     * same {@code workflowId} can park again. Called when a run ends, alongside
     * {@link #clearPending(String)}.
     *
     * @param workflowId the workflow whose poison is cleared
     */
    void clearTerminated(String workflowId) {
        terminatedWorkflows.remove(workflowId);
    }

    /**
     * Returns whether a workflow is currently poisoned by a terminate.
     * Primarily for testing.
     *
     * @param workflowId the workflow ID
     * @return {@code true} while parks for this workflow are refused
     */
    boolean isTerminated(String workflowId) {
        return terminatedWorkflows.containsKey(workflowId);
    }

    private static WorkflowTerminatedException terminateSignal(
            String workflowId, @Nullable String reason) {
        return new WorkflowTerminatedException(
                workflowId, NO_REASON.equals(reason) ? null : reason);
    }

    private static ExecutorShutdownException shutdownSignal(String key) {
        return new ExecutorShutdownException(
                "Executor is shutting down — abandoning the park at key '%s'; the workflow keeps its "
                        .formatted(key)
                        + "durable state and is recoverable");
    }

    /**
     * Removes any stored wake permits for a workflow. Called when a run ends
     * so orphaned permits (e.g. from duplicate signal deliveries) cannot
     * accumulate or leak into a future run. Matches {@code workflowId} exactly,
     * never as a key prefix.
     *
     * @param workflowId the workflow whose permits are removed
     */
    void clearPending(String workflowId) {
        pendingWakes.keySet().removeIf(slot -> slot.workflowId().equals(workflowId));
    }

    /**
     * Returns the number of stored wake permits — the source of the
     * {@code maestro.wake.permits} gauge, and read by tests.
     *
     * @return the number of pending-wake entries
     */
    int pendingWakeCount() {
        return pendingWakes.size();
    }

    /**
     * Returns the number of currently parked workflows. Primarily for testing.
     *
     * @return the number of parked entries
     */
    int parkedCount() {
        return futures.size();
    }
}
