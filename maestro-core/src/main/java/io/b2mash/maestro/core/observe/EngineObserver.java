package io.b2mash.maestro.core.observe;

import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Engine-internal observation seam: the engine invokes these callbacks
 * synchronously at execution boundaries. maestro-core has no metrics or
 * tracing dependency — adapters (Micrometer, tracing) live in the Spring
 * Boot starter and implement this interface.
 *
 * <h2>Replay awareness</h2>
 * Callbacks that can fire while the engine replays memoized history carry a
 * {@code replayed} flag. A recovered workflow replaying N steps emits N
 * callbacks with {@code replayed == true}; adapters that count or trace MUST
 * ignore those, or they will double-count (pinned by
 * ObserverReplayNoDoubleCountIT, see the design doc §8).
 *
 * <h2>Thread safety and discipline</h2>
 * Callbacks are invoked concurrently from workflow virtual threads, poller
 * threads, Kafka listener threads and the instance-lock renewer thread.
 * Implementations MUST be thread-safe, MUST return quickly (no I/O, no
 * blocking), and MUST NOT throw. {@link CompositeEngineObserver} contains a
 * misbehaving observer per callback and per delegate: every {@code Throwable}
 * is contained — logged, counted on {@code WorkflowExecutor.observerErrorCount()}
 * and otherwise ignored — except a
 * {@link io.b2mash.maestro.core.exception.MaestroControlFlowError} (the
 * engine's control-flow signals, which observers must never throw) and a
 * {@link VirtualMachineError}, which propagate (Issue 81); from
 * {@link #backgroundLoopError} a {@code VirtualMachineError} is contained too.
 * Every engine type that takes an observer wraps it with
 * {@link CompositeEngineObserver#contain}, so containment holds for every
 * emission site and every observer, including one wired by hand;
 * {@link CompositeEngineObserver#of} wraps the starter's list, a single
 * observer included (coordinator Ruling 4).
 */
public interface EngineObserver {

    /** Canonical no-op instance — engine fields default to this, never null. */
    EngineObserver NOOP = new EngineObserver() {};

    // ── Workflow lifecycle ────────────────────────────────────────────

    /**
     * A new workflow instance was created and its first local run launched.
     *
     * <p>Not called for a start that was deferred (see
     * {@link #workflowStartDeferred}): the run recovery later launches for it
     * reports {@link #workflowResumed}. A started count that must include
     * deferred starts adds the two.
     */
    default void workflowStarted(WorkflowInfo w) {}

    /**
     * A new workflow instance was persisted, but no local run was launched,
     * because its instance lock could not be taken (pilot readiness P3) — the
     * lock was held elsewhere, or the lock backend failed. The recovery poller
     * is asked for a prompt pass and runs it once the lock can be taken; that
     * run reports {@link #workflowResumed}, never {@link #workflowStarted}: no
     * persisted marker tells recovery that a row was never started, so a
     * start event emitted then could not be made exactly-once. Called on the
     * starting thread, once per deferred start. (Without a running recovery
     * poller the start is refused instead, and nothing is called.)
     *
     * @param w                 identity of the deferred workflow
     * @param lockHeldElsewhere {@code true} when the lock was held elsewhere;
     *                          {@code false} when the lock backend failed
     */
    default void workflowStartDeferred(WorkflowInfo w, boolean lockHeldElsewhere) {}

    /** A local run was launched in replay mode (recovery, resume, admin retry). */
    default void workflowResumed(WorkflowInfo w) {}

    /** This node won the terminal transition to {@code COMPLETED}. */
    default void workflowCompleted(WorkflowInfo w) {}

    /**
     * This node won the terminal transition to {@code FAILED}.
     *
     * @param exceptionType fully-qualified class name of the failing exception —
     *        for logging/audit observers; adapters must not use it as a tag
     *        (open-ended cardinality)
     */
    default void workflowFailed(WorkflowInfo w, String exceptionType) {}

    /**
     * This node won the terminal transition, and this is how long the workflow
     * took from start to that outcome.
     *
     * <p>Fired only by the node that won the terminal transition, directly
     * after {@link #workflowCompleted} or {@link #workflowFailed}, on the same
     * thread. Never fired for a converged loser, a stand-down, an abandoned run
     * ({@link #runAbandoned}) or a terminate ({@link #workflowTerminated}).
     * It fires once per terminal transition, not once per workflow: a
     * workflow that failed, was manually retried and then completed fires it
     * once with each status.
     *
     * @param w       identity of the workflow that finished
     * @param status  the outcome this node recorded:
     *                {@link WorkflowStatus#COMPLETED} or {@link WorkflowStatus#FAILED}
     * @param elapsed now minus the instance row's {@code startedAt}, clamped at
     *                {@link Duration#ZERO}. It is end-to-end wall time, not
     *                execution time: it includes park time (signals, timers)
     *                and any downtime before a recovery adopted the workflow
     */
    default void workflowFinished(WorkflowInfo w, WorkflowStatus status, Duration elapsed) {}

    /** Saga compensation is starting for this workflow. */
    default void workflowCompensating(WorkflowInfo w) {}

    /** An operator's terminate command won the CAS to {@code TERMINATED}. */
    default void workflowTerminated(WorkflowInfo w) {}

    // ── Run-segment boundaries (drive segment spans, §3) ──────────────

    /** The workflow thread is about to park (live path only, never replay). */
    default void workflowParked(WorkflowInfo w, ParkKind kind) {}

    /** The workflow thread resumed from a live park on this node. */
    default void workflowUnparked(WorkflowInfo w, ParkKind kind) {}

    // ── Activities ────────────────────────────────────────────────────

    /** Live execution is starting (never fired on replay). */
    default void activityStarted(ActivityInfo a) {}

    /**
     * Wraps one live activity attempt before it is handed to its own virtual
     * thread.
     *
     * <p>Called on the <b>workflow thread</b>, after {@link #activityStarted}
     * and once per attempt (retries included), just before the attempt is
     * started on a fresh virtual thread bounded by {@code startToCloseTimeout};
     * the returned callable runs on that <b>attempt thread</b>. Thread-local
     * state the workflow thread holds — a tracing span or observation put in
     * scope by {@code activityStarted} — is therefore not visible to activity
     * code unless the observer re-establishes it here: capture it on the
     * calling thread and scope it around {@code attempt.call()} in the returned
     * callable, closing the scope on every exit path. Observers that only count
     * (metrics) ignore this hook.
     *
     * <p>The returned callable must call {@code attempt} exactly once and
     * propagate its result or exception unchanged, including {@link Error}s;
     * it must not throw on its own account — anything it throws is
     * indistinguishable from an activity failure. Never fired on replay.
     *
     * @param <T>     the attempt's result type
     * @param a       the activity the attempt belongs to
     * @param attempt the attempt to run
     * @return the callable to run on the attempt thread — {@code attempt}
     *         itself by default; never {@code null}
     */
    default <T> Callable<T> wrapActivityAttempt(ActivityInfo a, Callable<T> attempt) {
        return attempt;
    }

    /**
     * An activity call yielded a successful result.
     *
     * @param duration wall time of the live execution, or {@link Duration#ZERO}
     *        when {@code replayed}
     * @param replayed {@code true} when the result came from the memoization log
     */
    default void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {}

    /**
     * An activity call yielded a failure (retries exhausted, or a memoized
     * failure replayed).
     *
     * @param duration      wall time of the live execution, or
     *                      {@link Duration#ZERO} when {@code replayed}
     * @param exceptionType fully-qualified class name of the failing exception
     * @param replayed      {@code true} when the failure came from the
     *                      memoization log
     */
    default void activityFailed(ActivityInfo a, Duration duration,
                                String exceptionType, boolean replayed) {}

    /**
     * The {@code exceptionType} {@link #activityResultUnreadable} carries when
     * a live result <em>was</em> read back, but as another class than the
     * object the activity returned.
     */
    String READ_BACK_TYPE_MISMATCH = "maestro:read-back-type-mismatch";

    /**
     * A live activity's result was persisted, and either cannot be read back
     * as the method's declared return type, or reads back as another class
     * than the object the activity returned. Either way the run continues with
     * the object the activity returned, and the live run and a replay of this
     * step will not agree:
     * <ul>
     *   <li><b>Cannot be read back</b> ({@code exceptionType} is the
     *       deserialization root cause's class name): a replay of this step
     *       will stand the run down as {@code unknown_event_payload}, so every
     *       call is a leading indicator of a stand-down at the run's next
     *       recovery.</li>
     *   <li><b>Reads back as another class</b> ({@code exceptionType} is
     *       {@link #READ_BACK_TYPE_MISMATCH}) — a subtype under a declared
     *       supertype, a value under {@code Object} or a type variable, a
     *       {@code Long} under {@code Number}: a replay of this step hands
     *       workflow code the read-back class instead. A later recorded step
     *       whose arguments depend on the difference stands the recovered run
     *       down as {@code nondeterministic_history}; workflow code that
     *       branches on it can also fail or complete differently.</li>
     * </ul>
     * Only the top-level class is compared, never the elements of a
     * collection or map, and a container that reads back as another
     * container of the same kind (both a {@code List}, a {@code Set}, a
     * {@code Queue} or a {@code Map}) is not a class difference.
     *
     * <p>Fired live only, on the workflow thread, after the outcome append and
     * before {@link #activityCompleted}. Never fired on replay.
     *
     * @param a             the activity whose result could not be read back as
     *                      what it returned
     * @param exceptionType fully-qualified class name of the deserialization
     *                      root cause, or {@link #READ_BACK_TYPE_MISMATCH}; never
     *                      use it as a metric tag
     */
    default void activityResultUnreadable(ActivityInfo a, String exceptionType) {}

    // ── Signals ───────────────────────────────────────────────────────

    /** A signal row was durably persisted (delivery side, any node). */
    default void signalPersisted(SignalInfo s) {}

    /** A workflow's {@code awaitSignal} consumed (or replayed) a signal. */
    default void signalConsumed(SignalInfo s, boolean replayed) {}

    /**
     * A delivery carried a message id already stored for the same workflow
     * and signal name, so no row was written (delivery side, any node). A
     * transport redelivery, a producer republish or a client retry — the
     * signal itself is already durable. Never fires for a signal without a
     * message id.
     */
    default void signalDuplicateSuppressed(SignalInfo s) {}

    // ── Timers ────────────────────────────────────────────────────────

    /** A durable timer was scheduled (or its scheduling replayed). */
    default void timerScheduled(TimerInfo t, boolean replayed) {}

    /** A timer fire was observed by the owning workflow (or replayed). */
    default void timerFired(TimerInfo t, boolean replayed) {}

    /** A timer cancellation was observed by the owning workflow (or replayed). */
    default void timerCancelled(TimerInfo t, boolean replayed) {}

    // ── Instance lock ─────────────────────────────────────────────────

    /** The per-instance distributed lock was acquired by this node. */
    default void instanceLockAcquired(String workflowId) {}

    /** A renewal attempt failed transiently (backend error; handle kept). */
    default void instanceLockRenewFailed(String workflowId) {}

    /** Ownership was lost (renew returned false; handle dropped). */
    default void instanceLockLost(String workflowId) {}

    // ── Recovery ──────────────────────────────────────────────────────

    /** One recovery pass finished (startup or poller cycle). */
    default void recoveryPass(int scanned, int adopted) {}

    // ── Retention ─────────────────────────────────────────────────────

    /**
     * One retention sweep finished on this node as the elected leader, having
     * purged {@code purged} rows in total across its batches. Not emitted by
     * a node that was not the leader for the sweep; emitted (with an
     * {@link io.b2mash.maestro.core.spi.PurgeResult#EMPTY empty} result) by
     * a leader that found nothing to purge.
     */
    default void retentionPass(io.b2mash.maestro.core.spi.PurgeResult purged) {}

    // ── Background loops ──────────────────────────────────────────────

    /**
     * A background loop (one of {@code timer-poller}, {@code recovery-poller},
     * {@code signal-janitor}, {@code retention-sweeper},
     * {@code instance-lock-renewer}) contained an {@code Error} that is not a
     * {@link io.b2mash.maestro.core.exception.MaestroControlFlowError}, and
     * keeps running. Fires on the loop's own thread, never on a workflow
     * thread. {@code errorType} is the {@code Error}'s class name. Any
     * occurrence means a bug or resource exhaustion on this node.
     *
     * @param loop      the loop's fixed name (bounded: the five names above)
     * @param errorType fully-qualified class name of the contained {@code Error}
     */
    default void backgroundLoopError(String loop, String errorType) {}

    // ── Store faults ──────────────────────────────────────────────────

    /**
     * The engine's store guard retried one store call in place after the store
     * reported itself unavailable ({@code StoreUnavailableException}) — a fault
     * the retries may still absorb (Issue 86). Fires on the calling thread,
     * usually a workflow thread, once per retry, before the retry's wait.
     * Only the idempotent run operations are retried; advisory reads and
     * background-loop store calls never fire it.
     *
     * @param operation the retried operation, bounded: {@code append_event},
     *                  {@code append_event_returning_payload} or
     *                  {@code get_event_by_sequence}
     */
    default void storeRetried(String operation) {}

    // ── Stand-down ────────────────────────────────────────────────────

    /** A local run stood down without recording a workflow outcome. */
    default void standDown(StandDownReason reason, String workflowId,
                           @Nullable String detail) {}

    // ── Run abandonment (design §11, RULING 5) ────────────────────────

    /**
     * A workflow's local run ended <b>on the workflow's own thread</b> without
     * this node recording a terminal outcome, for a reason that is not the
     * workflow's — routine in every case but {@link AbandonReason#NODE_ERROR}
     * (a broken node: alert on it).
     *
     * <p>Deliberately distinct from {@link #standDown}. A stand-down means
     * another runner's durable state governs this workflow; abandonment means
     * this thread stopped running it because the node is shutting down, an
     * operator terminated it, or another writer finalised the row first.
     * Routing these through {@code standDown} would make an ordinary deploy
     * increment a failure-shaped counter — the confusion the engine's
     * control-flow-signal design exists to prevent.
     *
     * <p><b>Why a stateful observer needs this:</b> it is the only callback
     * guaranteed to reach the observer <em>on the workflow thread</em> when the
     * run unwinds through {@link
     * io.b2mash.maestro.core.exception.ExecutorShutdownException} /
     * {@link io.b2mash.maestro.core.exception.WorkflowTerminatedException} or
     * loses the terminal transition. {@link #workflowTerminated} fires on the
     * operator's thread, not the workflow's, so it cannot release per-thread
     * state. Without this callback a span opened on the workflow thread is
     * never closed and therefore never exported.
     *
     * <p>A counting observer should count this under its <b>own</b> meter,
     * never fold {@link AbandonReason#TERMINATED} into a terminate counter:
     * {@link #workflowTerminated} already fires exactly once per terminate, on
     * the operator's thread, so counting {@code TERMINATED} there as well
     * would double-count the terminate.
     *
     * @param w      identity of the workflow whose local run ended
     * @param reason why the run was abandoned
     */
    default void runAbandoned(WorkflowInfo w, AbandonReason reason) {}
}
