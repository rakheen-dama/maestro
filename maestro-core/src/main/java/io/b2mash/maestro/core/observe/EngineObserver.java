package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

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
 * misbehaving observer by catching {@code RuntimeException} per callback;
 * {@code Error}s (including the engine's control-flow signals) always
 * propagate. {@link CompositeEngineObserver#of} wraps <em>any</em> non-empty
 * delegate list — including a single observer — so that containment holds for
 * every registration, not only for multi-observer ones (coordinator Ruling 4).
 */
public interface EngineObserver {

    /** Canonical no-op instance — engine fields default to this, never null. */
    EngineObserver NOOP = new EngineObserver() {};

    // ── Workflow lifecycle ────────────────────────────────────────────

    /** A new workflow instance was created and its first local run launched. */
    default void workflowStarted(WorkflowInfo w) {}

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

    // ── Signals ───────────────────────────────────────────────────────

    /** A signal row was durably persisted (delivery side, any node). */
    default void signalPersisted(SignalInfo s) {}

    /** A workflow's {@code awaitSignal} consumed (or replayed) a signal. */
    default void signalConsumed(SignalInfo s, boolean replayed) {}

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

    // ── Stand-down ────────────────────────────────────────────────────

    /** A local run stood down without recording a workflow outcome. */
    default void standDown(StandDownReason reason, String workflowId,
                           @Nullable String detail) {}

    // ── Run abandonment (design §11, RULING 5) ────────────────────────

    /**
     * A workflow's local run ended <b>on the workflow's own thread</b> without
     * this node recording a terminal outcome, for a routine reason.
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
     * <p>Counting observers should <b>not</b> implement this: {@link
     * #workflowTerminated} already fires exactly once for a terminate, so a
     * second emission here would double-count.
     *
     * @param w      identity of the workflow whose local run ended
     * @param reason why the run was abandoned
     */
    default void runAbandoned(WorkflowInfo w, AbandonReason reason) {}
}
