package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowContextInternals;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK dynamic proxy {@link InvocationHandler} that implements hybrid memoization
 * for activity method calls.
 *
 * <p>This is the core execution mechanism of Maestro. Every activity method
 * call is intercepted by this handler, which:
 * <ol>
 *   <li>Reads the current sequence number from {@link WorkflowContext}.</li>
 *   <li>Checks the {@link WorkflowStore} for a stored result at that sequence.</li>
 *   <li><b>Replay path:</b> If found, deserializes and returns the stored result.</li>
 *   <li><b>Live path:</b> If not found, executes the activity with retries,
 *       persists the result, and returns it as read back from the persisted
 *       payload.</li>
 * </ol>
 *
 * <h2>Argument verification</h2>
 * <p>A stored outcome is matched to the current call by sequence number and
 * step name ({@link ReplayGuard#requireMatch}) <em>and</em> by a fingerprint
 * of the arguments ({@link ArgumentFingerprint}, stored as
 * {@link WorkflowEvent#argsHash()}). A replay whose arguments fingerprint
 * differently from the recorded run's stands down as
 * {@code NONDETERMINISTIC_HISTORY} ({@link ReplayGuard#requireArgumentsMatch})
 * instead of returning the recorded result for an input that run never saw —
 * the silent failure mode of an activity input derived from a {@code HashMap}'s
 * iteration order, the clock or a per-node config value. The check runs before
 * the payload is returned and before any {@code @Compensate} entry is
 * registered, so compensation calls are covered too. A missing fingerprint on
 * either side (a row written before the column existed or by an older node, or
 * arguments the mapper cannot serialize) is <em>unverified</em>, never a
 * mismatch. {@code verifyArguments=false} skips the comparison but still
 * writes fingerprints on live outcomes.
 *
 * <h2>Live results are read back</h2>
 * <p>Once a live outcome's append has succeeded, the handler deserializes the
 * payload it persisted with the function and generic return type a replay
 * uses, and returns that value instead of the activity's own object, so
 * workflow code — and a {@code @Compensate} method that takes the result —
 * sees on the live run exactly what it sees after any recovery. What is
 * written does not change: the payload is still the serialized result, and a
 * later call's argument fingerprint moves only where the result did not
 * round-trip, to what a replay computes. A store fault, a fenced write or
 * a lost append race on the outcome append keeps its own handling and never
 * reaches the read-back, whose {@code try} holds no store call and catches
 * {@link SerializationException} only. A result that was written but cannot be
 * read back never fails the run: the observer's
 * {@link EngineObserver#activityResultUnreadable} fires, a WARN names the step
 * and the redacted cause, and the activity's own object is returned, while a
 * replay of that step will stand the run down as {@code UNKNOWN_EVENT_PAYLOAD}.
 *
 * <h2>Idempotency</h2>
 * <p>The Postgres unique constraint on {@code (workflow_instance_id, sequence_number)}
 * is the primary idempotency guard: at most one outcome is ever persisted per
 * sequence. The memoization lookup above makes a recovery run replay what is
 * already there, so a live append only collides when <em>another runner</em>
 * wrote the slot between this run's lookup and its append — two nodes
 * executing the same workflow in the lost-lock window (Issue 11).
 *
 * <p><b>The stand-down contract.</b> On a {@link DuplicateEventException} from
 * the append — of this attempt's {@code ACTIVITY_COMPLETED} or of its
 * {@code ACTIVITY_FAILED} alike — the handler accepts the winner's
 * <em>record</em> as the truth and writes nothing that contradicts it: no
 * lifecycle event, no failure surfaced to workflow code. It re-reads the
 * winner only to check this build can interpret its type (an unknown-typed
 * winner stands down as {@code UNKNOWN_EVENT_TYPE}, RULING 9), logs at WARN,
 * and rethrows the duplicate, which unwinds to {@code WorkflowExecutor}'s
 * {@code STALE_RUN} stand-down. The observer still sees the attempt this node
 * genuinely executed — it measures executions, not the durable record.
 *
 * <p>The stand-down is <b>sticky</b>: before rethrowing, the handler sets the
 * run's {@code RunCancellation} to {@code STALE}. Should workflow code catch
 * and swallow the {@link DuplicateEventException}, the run's next live
 * activity throws {@code StaleRunException} before it acquires a lock,
 * publishes a start or executes anything (R29). Replayed activities are not
 * affected — they execute nothing.
 *
 * <p><b>Why not adopt the winner's value.</b> This handler used to return the
 * winner's stored payload and let the workflow carry on. But the winner is
 * alive and ahead: every later live activity of the loser would either collide
 * again or — worse — run a side effect the winner also runs, and a failure the
 * loser surfaced would contradict a completion the winner recorded. Nothing the
 * loser does after the collision is needed; standing down is the only move that
 * cannot double a side effect (C5).
 *
 * <p><b>Store faults.</b> The store this handler is given is wrapped in
 * {@code StoreFaultGuard} by {@link ActivityProxyFactory}: the memo lookup and
 * the outcome append are retried briefly, and a final
 * {@code StoreUnavailableException} latches the run's {@code STORE_UNAVAILABLE}
 * stand-down and propagates — never recorded as the activity's failure, never
 * a reason to retry the activity itself. Like the stale case it is sticky: the
 * run's next live activity throws it again before executing anything, and
 * {@code WorkflowExecutor} abandons the run for recovery to resume. The
 * activity body itself runs in the guard's pass-through scope: a store call it
 * makes on its own account (a {@code MaestroClient} call for another workflow)
 * is the activity's own failure, retried under its {@code RetryPolicy}.
 *
 * <h2>Lock Strategy</h2>
 * <p>The distributed lock is an optimization to prevent duplicate execution, not
 * a correctness requirement. If the lock backend is unavailable, the handler
 * proceeds without the lock — the Postgres unique constraint prevents corruption.
 *
 * <h2>Timeouts</h2>
 * <p>{@code startToCloseTimeout} bounds each live <em>attempt</em> on the wall
 * clock. Every attempt runs on its own virtual thread — carrying a copy of the
 * caller's MDC, its {@link WorkflowContext} binding and whatever the
 * {@link EngineObserver} re-establishes through
 * {@link EngineObserver#wrapActivityAttempt} (the tracing adapter's activity
 * span), but no other thread-local state of the workflow thread — while the
 * workflow thread waits at most the timeout for it. {@code @Compensate}
 * methods run through this same path and are bounded by the same timeout.
 * An attempt that overruns is cancelled — its
 * thread is interrupted — and fails with an {@link ActivityTimeoutException},
 * which the {@link RetryPolicy} treats like any other failure: retried by
 * default, terminal if listed in {@code nonRetryableExceptions}. The workflow
 * thread does not wait for a cancelled attempt to stop, and a value it
 * produces after the deadline is discarded, never memoized. Replays are never
 * bounded: nothing executes. The attempt counter is in-memory; a crash
 * mid-retry restarts the schedule at attempt 1, and the durable guarantee is
 * only that a <em>completed</em> attempt is never re-executed.
 *
 * <p><b>Activity code must not call workflow APIs</b> ({@code sleep},
 * {@code awaitSignal}, {@code parallel}, {@code addCompensation}, ...) or other
 * activity proxies — that is unsupported. The {@code WorkflowContext} is
 * re-bound on the attempt thread for compatibility only, so such code keeps
 * its pre-C11 behaviour where it can; the branch-compensation buffer of a
 * {@code parallel()} branch ({@code BRANCH_COMPENSATIONS}) is <em>not</em>
 * re-bound, and an attempt that outlives its timeout still holds the context.
 *
 * <h2>Thread Safety</h2>
 * <p>Each handler instance is associated with one activity interface but may be
 * shared across workflow instances (the per-instance state comes from
 * {@link WorkflowContext} on the current thread). All per-run mutable state is
 * thread-local via the context; the only handler-level state, the set of
 * methods whose unreadable result has been WARNed, is a concurrent set.
 *
 * @see ActivityProxyFactory
 * @see WorkflowContext
 */
public final class ActivityInvocationHandler implements InvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivityInvocationHandler.class);

    private final Object activityImpl;
    private final String activityName;
    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final LifecycleEventSink lifecycleEventSink;
    private final boolean verifyArguments;
    private final RetryPolicy retryPolicy;
    private final Duration startToCloseTimeout;
    private final PayloadSerializer serializer;
    private final RetryExecutor retryExecutor;
    private final String lockKeyPrefix;
    private final EngineObserver observer;
    private final TaskQueueGovernor governor;
    /** Activity methods whose unreadable result has already been WARNed by this handler. */
    private final Set<Method> warnedUnreadable = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new handler with the default lock key prefix
     * ({@code maestro:lock:}) — the same default the instance lock uses.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle event publishing
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor
    ) {
        this(activityImpl, activityName, store, distributedLock, messaging, retryPolicy,
                startToCloseTimeout, serializer, retryExecutor,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX);
    }

    /**
     * Creates a new handler with an explicit lock key prefix.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle event publishing
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     * @param lockKeyPrefix       prefix for the per-activity distributed lock key
     *                            (e.g. {@code maestro:lock:}) — the same prefix
     *                            configured for the instance lock via
     *                            {@code maestro.lock.key-prefix}
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor,
            String lockKeyPrefix
    ) {
        this(activityImpl, activityName, store, distributedLock, messaging, retryPolicy,
                startToCloseTimeout, serializer, retryExecutor, lockKeyPrefix,
                EngineObserver.NOOP);
    }

    /**
     * Creates a new handler with an explicit lock key prefix and an
     * {@link EngineObserver}.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle event publishing
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     * @param lockKeyPrefix       prefix for the per-activity distributed lock key
     *                            (e.g. {@code maestro:lock:})
     * @param observer            engine observation seam — fires
     *                            {@code activityStarted} (live only) and
     *                            {@code activityCompleted}/{@code activityFailed}
     *                            (live and replay, with the {@code replayed}
     *                            flag); never {@code null} (pass
     *                            {@link EngineObserver#NOOP}); also wraps each
     *                            live attempt via
     *                            {@link EngineObserver#wrapActivityAttempt}
     * @throws IllegalArgumentException if {@code startToCloseTimeout} is zero or
     *                                  negative — every attempt would time out
     *                                  before it could run
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor,
            String lockKeyPrefix,
            EngineObserver observer
    ) {
        this(activityImpl, activityName, store, distributedLock, messaging, retryPolicy,
                startToCloseTimeout, serializer, retryExecutor, lockKeyPrefix, observer,
                TaskQueueGovernor.UNBOUNDED);
    }

    /**
     * Creates a new handler with an explicit lock key prefix, an
     * {@link EngineObserver} and a {@link TaskQueueGovernor}.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle event publishing
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     * @param lockKeyPrefix       prefix for the per-activity distributed lock key
     *                            (e.g. {@code maestro:lock:})
     * @param observer            engine observation seam; never {@code null}
     *                            (pass {@link EngineObserver#NOOP})
     * @param governor            bounds live executions per task queue: a live
     *                            execution holds one activity slot of its run's
     *                            queue from the dedup lock through every retry
     *                            attempt to its persisted outcome; a replay takes
     *                            none. Pass {@link TaskQueueGovernor#UNBOUNDED} for none.
     * @throws IllegalArgumentException if {@code startToCloseTimeout} is zero or
     *                                  negative — every attempt would time out
     *                                  before it could run
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor,
            String lockKeyPrefix,
            EngineObserver observer,
            TaskQueueGovernor governor
    ) {
        this(activityImpl, activityName, store, distributedLock, retryPolicy, startToCloseTimeout,
                serializer, retryExecutor, lockKeyPrefix, observer, governor,
                LifecycleEventSink.direct(messaging));
    }

    /**
     * Creates a new handler with every setting of the governor constructor,
     * publishing lifecycle events through an explicit {@link LifecycleEventSink}
     * instead of inline on a {@link WorkflowMessaging}. Pass
     * {@link WorkflowExecutor#lifecycleEventSink()} so {@code ACTIVITY_*}
     * events never block the workflow thread on the transport — a blocking
     * producer used to cost every activity call up to {@code max.block.ms}
     * twice.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     * @param lockKeyPrefix       prefix for the per-activity distributed lock key
     * @param observer            engine observation seam; never {@code null}
     * @param governor            bounds live executions per task queue; pass
     *                            {@link TaskQueueGovernor#UNBOUNDED} for none
     * @param lifecycleEventSink  where {@code ACTIVITY_*} events go; never
     *                            {@code null} (pass {@link LifecycleEventSink#NOOP})
     * @throws IllegalArgumentException if {@code startToCloseTimeout} is zero or negative
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor,
            String lockKeyPrefix,
            EngineObserver observer,
            TaskQueueGovernor governor,
            LifecycleEventSink lifecycleEventSink
    ) {
        this(activityImpl, activityName, store, distributedLock, retryPolicy, startToCloseTimeout,
                serializer, retryExecutor, lockKeyPrefix, observer, governor, lifecycleEventSink, true);
    }

    /**
     * Creates a new handler with every collaborator explicit, including
     * whether replays verify the activity's arguments.
     *
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout wall-clock bound for each live attempt (enforced); also the
     *                            base of the per-activity lock TTL
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     * @param lockKeyPrefix       prefix for the per-activity distributed lock key
     * @param observer            engine observation seam; never {@code null}
     * @param governor            bounds live executions per task queue; pass
     *                            {@link TaskQueueGovernor#UNBOUNDED} for none
     * @param lifecycleEventSink  where {@code ACTIVITY_*} events go; never
     *                            {@code null} (pass {@link LifecycleEventSink#NOOP})
     * @param verifyArguments     whether a replay compares the fingerprint of the
     *                            current call's arguments with the one the recorded
     *                            outcome carries and stands down on a difference
     *                            ({@code maestro.replay.verify-arguments}). Live
     *                            outcomes carry a fingerprint either way, so turning
     *                            verification back on later covers the rows written
     *                            while it was off.
     * @throws IllegalArgumentException if {@code startToCloseTimeout} is zero or negative
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor,
            String lockKeyPrefix,
            EngineObserver observer,
            TaskQueueGovernor governor,
            LifecycleEventSink lifecycleEventSink,
            boolean verifyArguments
    ) {
        Objects.requireNonNull(startToCloseTimeout, "startToCloseTimeout must not be null");
        if (startToCloseTimeout.isZero() || startToCloseTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "startToCloseTimeout must be positive for activity '%s', was %s"
                            .formatted(activityName, startToCloseTimeout));
        }
        this.activityImpl = activityImpl;
        this.activityName = activityName;
        this.store = store;
        this.distributedLock = distributedLock;
        this.lifecycleEventSink = lifecycleEventSink;
        this.retryPolicy = retryPolicy;
        this.startToCloseTimeout = startToCloseTimeout;
        this.serializer = serializer;
        this.retryExecutor = retryExecutor;
        this.lockKeyPrefix = lockKeyPrefix;
        this.observer = observer;
        this.governor = governor;
        this.verifyArguments = verifyArguments;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
            throws Throwable {

        // Pass-through for Object methods
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        var ctx = WorkflowContext.current();
        var seq = WorkflowContextInternals.nextSequence(ctx);
        var stepName = activityName + "." + method.getName();

        // Activity-specific MDC keys (base workflow keys are set by the scoped executor)
        MDC.put("activityName", stepName);
        MDC.put("sequence", String.valueOf(seq));

        try {
            // The arguments' fingerprint: compared with the recorded outcome's on
            // replay and written with this call's outcome when live. Computed
            // once, here, when verifying — before the lookup, so a replay
            // compares what the caller actually passed; with verification off it
            // is computed only if the call goes live (the write still happens).
            var argsHash = verifyArguments ? ArgumentFingerprint.of(serializer, args) : null;

            // ── Memoization lookup ────────────────────────────────────
            var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);

            if (storedEvent.isPresent()) {
                // A type this build does not know means a newer node wrote this
                // history: stand the run down BEFORE deciding replay-vs-live.
                UnknownHistoryGuard.requireKnown(storedEvent.get(), ctx.workflowId());
                // Then the slot must hold THIS step's outcome (C10): a renamed or
                // reordered activity, or any other operation's event at this
                // sequence, stands down here — before the impl could run and
                // before another step's payload could be returned.
                ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName,
                        EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);
                // And the step must be asked the same question: an input that
                // drifted (map order, clock, per-node config) must not replay
                // the recorded answer. Before the payload is returned and before
                // a @Compensate entry is registered for it. A null on either
                // side is unverified (pre-V7 row, older node, unhashable args).
                if (verifyArguments) {
                    ReplayGuard.requireArgumentsMatch(storedEvent.get(), ctx.workflowId(), stepName, argsHash);
                }
                var info = new ActivityInfo(ctx.workflowId(), ctx.workflowType(), stepName, seq);
                if (storedEvent.get().eventType() == EventType.ACTIVITY_FAILED) {
                    // handleReplay throws for a memoized failure — observe it
                    // first, flagged replayed so adapters do not re-count it.
                    observer.activityFailed(info, Duration.ZERO,
                            storedExceptionType(storedEvent.get()), true);
                }
                var result = handleReplay(storedEvent.get(), method, stepName, ctx, seq);
                // Register compensation on successful replay (rebuilds stack during recovery)
                if (storedEvent.get().eventType() == EventType.ACTIVITY_COMPLETED) {
                    observer.activityCompleted(info, Duration.ZERO, true);
                    registerCompensationIfAnnotated(proxy, method, args, result, ctx);
                }
                return result;
            }

            // ── Live execution ────────────────────────────────────────
            WorkflowContextInternals.setReplaying(ctx, false);
            if (!verifyArguments) {
                argsHash = ArgumentFingerprint.of(serializer, args);
            }
            var result = executeLive(method, args, ctx, seq, stepName, argsHash);
            // Register compensation after successful live execution
            registerCompensationIfAnnotated(proxy, method, args, result, ctx);
            return result;

        } finally {
            MDC.remove("activityName");
            MDC.remove("sequence");
        }
    }

    // ── Replay path ───────────────────────────────────────────────────

    /**
     * Handles a memoized (replayed) activity result.
     */
    private @Nullable Object handleReplay(
            WorkflowEvent event, Method method, String stepName,
            WorkflowContext ctx, int seq
    ) throws ActivityExecutionException {
        return switch (event.eventType()) {
            case ACTIVITY_COMPLETED -> {
                logger.debug("Replaying completed activity '{}' at seq {}", stepName, seq);
                // A stored payload this build cannot read is the payload-flavoured
                // half of the same problem the type sentinel covers: a newer node
                // shaped it. Stand down instead of failing the workflow. Only the
                // REPLAY caller is wrapped — a live-path serialization failure is
                // an ordinary workflow failure and must stay one.
                yield UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                        "result of activity '%s'".formatted(stepName),
                        () -> deserializeResult(event.payload(), method));
            }
            case ACTIVITY_FAILED -> {
                logger.debug("Replaying failed activity '{}' at seq {}", stepName, seq);
                throw reconstructFailure(event, stepName, ctx.workflowId());
            }
            // Unreachable: ReplayGuard.requireMatch admits only ACTIVITY_COMPLETED
            // and ACTIVITY_FAILED before handleReplay is called. Kept so the
            // switch stays total without a silent fall-through.
            default -> throw new IllegalStateException(
                    ("Unreachable: event type %s at seq %d for activity '%s' in workflow '%s' "
                            + "should have been rejected by ReplayGuard")
                            .formatted(event.eventType(), seq, stepName, ctx.workflowId()));
        };
    }

    /**
     * Deserializes a stored activity result to the method's return type.
     */
    private @Nullable Object deserializeResult(@Nullable JsonNode payload, Method method) {
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            return null;
        }
        return serializer.deserialize(payload, method.getGenericReturnType());
    }

    /**
     * The value a live call returns once its outcome is durable: {@code stored}
     * read back by {@link #deserializeResult}, exactly as a replay of the step
     * reads it.
     *
     * <p>A result the mapper wrote but cannot read back never fails the run
     * (D9): the observer's {@link EngineObserver#activityResultUnreadable} fires,
     * {@link #unreadableResultWarning} is logged — at WARN the first time per
     * activity method of this handler, at DEBUG after that — and the activity's
     * own object is returned. The event stays durable as written; a replay of
     * the step stands the run down as {@code UNKNOWN_EVENT_PAYLOAD}.
     *
     * <p>The {@code try} holds no store call and catches
     * {@link SerializationException} only, so an {@link Error} (a control-flow
     * signal included) and a store fault are never swallowed here.
     *
     * @param stored   the payload the outcome append persisted
     * @param raw      the object the activity returned
     * @return the read-back value, or {@code raw} if it cannot be read back
     */
    private @Nullable Object readBack(@Nullable JsonNode stored, Method method, @Nullable Object raw,
                                      ActivityInfo info, WorkflowContext ctx, int seq, String stepName) {
        try {
            return deserializeResult(stored, method);
        } catch (SerializationException unreadable) {
            var root = unreadable.getCause() != null ? unreadable.getCause() : unreadable;
            observer.activityResultUnreadable(info, root.getClass().getName());
            var warning = unreadableResultWarning(ctx.workflowId(), stepName, seq,
                    method.getGenericReturnType(), root);
            if (warnedUnreadable.add(method)) {
                logger.warn("{}", warning);
            } else {
                logger.debug("{}", warning);
            }
            return raw;
        }
    }

    /**
     * The fixed WARN copy for a live result that cannot be read back. It names
     * the cause by class and structural JSON path only
     * ({@link UnknownHistoryGuard#redactedCause}) — never Jackson's message,
     * which quotes stored values — and never includes a payload value.
     *
     * @param workflowId the run's workflow id
     * @param stepName   {@code <ActivityName>.<method>}
     * @param seq        the step's sequence number
     * @param declared   the method's generic return type
     * @param cause      the deserialization root cause
     * @return the log line
     */
    static String unreadableResultWarning(String workflowId, String stepName, int seq,
                                          Type declared, Throwable cause) {
        return ("Workflow '%s': the result of activity '%s' at sequence %d is recorded but cannot be read back "
                + "as %s (%s); this run continues with the object the activity returned, and a replay of this "
                + "step will stand the run down as unknown_event_payload — make the type deserializable "
                + "(docs/pitfalls.md, rule 12)")
                .formatted(workflowId, stepName, seq, declared.getTypeName(),
                        UnknownHistoryGuard.redactedCause(cause));
    }

    /**
     * Reconstructs an {@link ActivityExecutionException} from a stored failure event.
     *
     * <p>The cause is rebuilt by {@link FailureReconstruction#reconstruct}: the
     * original type only via a public {@code (String)} constructor, otherwise a
     * {@code ReconstructedActivityFailure} naming the original class.
     */
    private ActivityExecutionException reconstructFailure(
            WorkflowEvent event, String stepName, String workflowId
    ) {
        var message = "Replayed failure";
        var exceptionType = (String) null;

        if (event.payload() != null) {
            if (event.payload().has("message")) {
                var text = event.payload().get("message").stringValue();
                if (text != null && !text.isEmpty()) {
                    message = text;
                }
            }
            if (event.payload().has("exceptionType")) {
                exceptionType = event.payload().get("exceptionType").stringValue();
            }
        }

        var cause = FailureReconstruction.reconstruct(exceptionType, message);
        return new ActivityExecutionException(workflowId, stepName, cause);
    }

    // ── Live execution path ───────────────────────────────────────────

    /**
     * Executes the activity live — with optional locking, retries, persistence,
     * and lifecycle event publishing.
     */
    private @Nullable Object executeLive(
            Method method, @Nullable Object @Nullable [] args,
            WorkflowContext ctx, int seq, String stepName, @Nullable String argsHash
    ) throws Throwable {
        // F8: a live execution holds one activity slot of its run's task queue
        // — blocking while the queue is at its activity-concurrency limit —
        // from here through the lock, every retry attempt and backoff, to the
        // persisted outcome. Replays never reach this method, so never take one.
        var waited = governor.acquireActivitySlot(ctx.taskQueue());
        try {
            if (waited) {
                // R47: a run that queued for its slot has not started this
                // activity, so a stop requested meanwhile — shutdown included,
                // which is otherwise not checked before an attempt so that
                // in-flight work drains — stands it down here, before any
                // side effect. Terminate and stale are checked first thing in
                // executeLiveHoldingSlot, so only shutdown needs a check here.
                if (ctx.cancellation().cause() == RunCancellation.Cause.SHUTDOWN) {
                    throw new ExecutorShutdownException(("Executor is shutting down — activity '%s' of "
                            + "workflow '%s' got its task-queue slot only after shutdown began; it did not "
                            + "run and the workflow stays recoverable").formatted(stepName, ctx.workflowId()));
                }
            }
            return executeLiveHoldingSlot(method, args, ctx, seq, stepName, argsHash);
        } finally {
            governor.releaseActivitySlot(ctx.taskQueue());
        }
    }

    /** {@link #executeLive}'s body, run while holding the activity slot. */
    private @Nullable Object executeLiveHoldingSlot(
            Method method, @Nullable Object @Nullable [] args,
            WorkflowContext ctx, int seq, String stepName, @Nullable String argsHash
    ) throws Throwable {
        // A terminated run stops here (checked after the slot wait, so one
        // terminated while it waited for a slot stops too) — before the lock,
        // the ACTIVITY_STARTED lifecycle event and observer.activityStarted —
        // so it never opens an
        // activity span (or publishes a start) that no completion would close.
        // So does a run already known to be stale (R29): one of its appends
        // collided with another runner's event, and the author caught and
        // carried on past the DuplicateEventException — this activity is a
        // side effect the winner also runs. So does a run whose store fault
        // was latched (R2): the author caught the StoreUnavailableException of
        // an earlier call — this one's memo lookup, even — and carried on, so
        // this activity would be a side effect taken down a path the durable
        // history never chose (a fallback for a primary that may have run).
        // RetryExecutor repeats these checks before every attempt; this only
        // moves the first ahead of the side effects.
        ctx.cancellation().throwIfTerminated(ctx.workflowId());
        ctx.cancellation().throwIfStale(ctx.workflowId());
        ctx.cancellation().throwIfStoreUnavailable(ctx.workflowId());

        LockHandle lockHandle = null;
        var info = new ActivityInfo(ctx.workflowId(), ctx.workflowType(), stepName, seq);

        try {
            // Acquire distributed lock (optional — Postgres unique constraint is the real guard)
            lockHandle = acquireLock(ctx, seq, stepName);

            // Publish ACTIVITY_STARTED lifecycle event (best-effort)
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_STARTED);
            observer.activityStarted(info);

            // Execute activity with retry, measuring the wall time of the
            // live execution (including its retry attempts) for the observer.
            Object result;
            var startNanos = System.nanoTime();
            try {
                result = retryExecutor.executeWithRetry(
                        retryPolicy,
                        () -> invokeActivityBounded(method, args, ctx, stepName, info),
                        stepName,
                        ctx.workflowId(),
                        ctx.cancellation()
                );
            } catch (ActivityExecutionException e) {
                var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                // Retries exhausted — persist the failure.
                try {
                    persistFailure(ctx, seq, stepName, argsHash, e);
                } catch (DuplicateEventException | StoreUnavailableException notRecorded) {
                    // A concurrent runner already recorded an outcome at this
                    // sequence (Review Focus 4), or the store could not take
                    // the failure memo (the guard has latched the run). Either
                    // propagates INSTEAD of e: this run neither publishes
                    // ACTIVITY_FAILED nor surfaces a failure that is not
                    // durably recorded — it stands down / is abandoned. The
                    // observer still sees the failure this node genuinely
                    // executed: it measures this node's executions, not the
                    // durable record.
                    observer.activityFailed(info, elapsed, failureExceptionType(e), false);
                    throw notRecorded;
                }
                publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_FAILED);
                // (An Error — e.g. a shutdown signal — never reaches this catch
                // and is never observed as a failure.)
                observer.activityFailed(info, elapsed, failureExceptionType(e), false);
                throw e;
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            // Persist success. A result the mapper cannot write is an ordinary
            // live failure, as it always was: it propagates from here.
            var payload = serializer.serialize(result);
            try {
                appendEventSafe(ctx, seq, EventType.ACTIVITY_COMPLETED, stepName, payload, argsHash);
            } catch (DuplicateEventException | StoreUnavailableException notRecorded) {
                // The winner is alive and ahead: stand down rather than adopt
                // its value (class Javadoc, "Idempotency"). Or the store could
                // not take the outcome even after the guard's retries (R3): the
                // run is latched and is abandoned, and recovery re-runs this
                // step. No ACTIVITY_COMPLETED lifecycle event — this run did not
                // persist one — but the observer sees the attempt this node
                // genuinely executed (and a tracing adapter closes its span).
                observer.activityCompleted(info, elapsed, false);
                throw notRecorded;
            }

            // The outcome is durable: hand workflow code what a replay of this
            // step will hand it (class Javadoc, "Live results are read back").
            var value = readBack(payload, method, result, info, ctx, seq, stepName);
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_COMPLETED);
            observer.activityCompleted(info, elapsed, false);
            return value;

        } finally {
            releaseLock(lockHandle, stepName, seq);
        }
    }

    /**
     * Runs one attempt of the activity on its own virtual thread, bounded by
     * {@link #startToCloseTimeout} (C11).
     *
     * <p>The attempt thread gets a copy of the caller's MDC, so the activity
     * logs under the same {@code workflowId}/{@code runId}/{@code activityName}
     * keys it did when it ran on the workflow thread, and the caller's
     * {@link WorkflowContext} is re-bound for the attempt's duration (for
     * compatibility only — see the class Javadoc). The attempt body is passed
     * through {@link EngineObserver#wrapActivityAttempt} on this (the workflow)
     * thread, so an observer can re-establish thread-local context — the
     * tracing adapter's activity span — on the attempt thread (R26).
     *
     * <p>On timeout the attempt is cancelled with {@code cancel(true)} (its
     * thread is interrupted) and an {@link ActivityTimeoutException} is thrown
     * at once, without waiting for the attempt to stop. The attempt's result
     * only ever reaches the memoization log by being returned from here (the
     * workflow thread persists it), so a late value from an attempt that
     * ignored the interrupt is discarded with its {@code FutureTask} and can
     * never be memoized. If the attempt finished in the instant between the
     * deadline and the cancel ({@code cancel} returns {@code false}), its
     * outcome is used instead of being discarded — discarding a completed
     * attempt would only guarantee its re-execution.
     *
     * <p>An attempt that fails is unwrapped in the same order as
     * {@link #invokeActivity}: an {@link Error} (a control-flow signal among
     * them) is rethrown first and intact, then an {@link Exception}; anything
     * else is wrapped in a {@code RuntimeException}.
     */
    private @Nullable Object invokeActivityBounded(
            Method method, @Nullable Object @Nullable [] args,
            WorkflowContext ctx, String stepName, ActivityInfo info
    ) throws Exception {
        // Re-bind the caller's WorkflowContext: before C11 the activity ran on
        // the workflow thread and could see it, and code reached through it (a
        // park that a shutdown or terminate unwinds) keeps behaving as it did.
        // The workflow thread is blocked in get() meanwhile, so the context is
        // never used from two live threads — except by an attempt that ignores
        // the timeout's interrupt, whose result is discarded regardless.
        // The body itself runs in StoreFaultGuard's pass-through scope, bound
        // here on the attempt thread: a store call it makes on its own account
        // (a MaestroClient start or signal of another workflow) must fail this
        // attempt under the retry policy, not latch the calling run. The
        // handler's own memo lookup, outcome append and lock calls run outside
        // this scope, on the workflow thread, and stay guarded.
        Callable<@Nullable Object> body = () -> ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                .call(() -> StoreFaultGuard.passThrough(() -> invokeActivity(method, args)));
        var wrapped = observer.wrapActivityAttempt(info, body);
        var callerMdc = MDC.getCopyOfContextMap();
        var attempt = new FutureTask<@Nullable Object>(() -> {
            if (callerMdc != null) {
                MDC.setContextMap(callerMdc);
            }
            try {
                return wrapped.call();
            } finally {
                MDC.clear();
            }
        });
        Thread.ofVirtual()
                .name("maestro-activity-" + ctx.workflowId() + "-" + stepName)
                .start(attempt);

        try {
            return attempt.get(saturatedNanos(startToCloseTimeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (!attempt.cancel(true)) {
                // Completed between the timed get() and the cancel: the attempt
                // did finish, so honour its outcome; get() cannot block now.
                // Not unit-pinned: the window cannot be forced deterministically
                // without a test-only seam in this method.
                return completedOutcome(attempt);
            }
            logger.warn("Activity '{}' in workflow '{}' exceeded its startToCloseTimeout of {}; "
                    + "attempt cancelled", stepName, ctx.workflowId(), startToCloseTimeout);
            throw new ActivityTimeoutException(ctx.workflowId(), stepName, startToCloseTimeout);
        } catch (ExecutionException e) {
            throw rethrowAttemptFailure(e);
        } catch (InterruptedException e) {
            // The engine never interrupts a workflow thread; a foreign interrupt
            // used to reach the activity itself (it ran on this thread), so pass
            // it on to the attempt and restore the flag, as before.
            attempt.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** The outcome of an attempt already known to be done and not cancelled. */
    private static @Nullable Object completedOutcome(FutureTask<@Nullable Object> attempt) throws Exception {
        try {
            return attempt.get();
        } catch (ExecutionException e) {
            throw rethrowAttemptFailure(e);
        }
    }

    /**
     * Rethrows an attempt's failure: an {@link Error} first — see
     * {@link #invokeActivity}: a control-flow signal must not be re-wrapped
     * into a catchable, retryable {@code RuntimeException} — then an
     * {@link Exception}, else a {@code RuntimeException} wrapping the cause.
     * Never returns normally; declared to return an exception so call sites
     * can {@code throw} it.
     */
    private static Exception rethrowAttemptFailure(ExecutionException e) throws Exception {
        var cause = e.getCause();
        if (cause instanceof Error err) {
            throw err;
        }
        if (cause instanceof Exception ex) {
            throw ex;
        }
        throw new RuntimeException(cause);
    }

    /** {@code d} in nanoseconds, saturating at {@code Long.MAX_VALUE} for a practically-infinite timeout. */
    private static long saturatedNanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Invokes the actual activity method on the implementation.
     * Unwraps {@link InvocationTargetException} to expose the real cause.
     */
    private @Nullable Object invokeActivity(Method method, @Nullable Object @Nullable [] args)
            throws Exception {
        try {
            return method.invoke(activityImpl, args);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            // An Error (e.g. ExecutorShutdownException, thrown as a control-flow
            // signal — see its Javadoc) must not be re-wrapped as a RuntimeException:
            // that would let it be retried or recorded as an activity failure
            // instead of reaching WorkflowExecutor's shutdown handling intact.
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    // ── Event persistence ─────────────────────────────────────────────

    /**
     * Appends an event to the memoization log; a lost append race stands this
     * run down.
     *
     * @throws FencedWriteException    if the instance's lease epoch has moved
     *                                 past this run's (another node adopted the
     *                                 workflow) — nothing written, the run stands
     *                                 down as {@code LEASE_FENCED}
     * @throws DuplicateEventException if a concurrent runner already persisted
     *                                 an event at {@code seq} — rethrown after
     *                                 the winner's type has been checked, so
     *                                 the run stands down as {@code STALE_RUN}
     * @throws io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException
     *                                 if the winner's event type is one this
     *                                 build does not define (RULING 9) — the
     *                                 run stands down as {@code UNKNOWN_EVENT_TYPE}
     */
    private void appendEventSafe(
            WorkflowContext ctx, int seq, EventType type,
            String stepName, @Nullable JsonNode payload, @Nullable String argsHash
    ) {
        var event = new WorkflowEvent(
                UUID.randomUUID(),
                ctx.workflowInstanceId(),
                seq,
                type,
                stepName,
                payload,
                Instant.now(),
                argsHash
        );

        try {
            store.appendEvent(event, ctx.lease());
        } catch (FencedWriteException fenced) {
            // Issue 11: the instance row's lease epoch has moved past this
            // run's — another node adopted the workflow (this node lost or
            // outlived its instance lock) and the store refused to persist this
            // result. Nothing was written. Sticky, like the collision below, so
            // workflow code that swallows it still stands down at its next
            // live step; the executor reports LEASE_FENCED.
            logger.warn("Workflow '{}': {} append for '{}' at seq {} was fenced — another node has adopted "
                            + "this workflow; standing this run down ({})",
                    ctx.workflowId(), type, stepName, seq, fenced.staleDetail());
            ctx.cancellation().requestStale(fenced.staleDetail());
            throw fenced;
        } catch (DuplicateEventException e) {
            // Lost the append race: a concurrent runner already persisted an
            // outcome at this sequence (C5). Its payload is never adopted — the
            // winner is alive and ahead — but its TYPE is still checked first
            // (RULING 9): reached with no author error, from the engine's own
            // tolerated state (a current lease but a peer's event already in
            // the slot), the winner may be a newer node whose event type this
            // build does not define, and that stands down as UNKNOWN_EVENT_TYPE
            // — the reason an operator needs to see — rather than as a plain
            // stale run.
            UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
            logger.warn("Workflow '{}': {} append for '{}' at seq {} collided with a concurrent runner's "
                            + "event — not adopting it; standing this stale run down",
                    ctx.workflowId(), type, stepName, seq);
            // Sticky (R29): should workflow code catch and swallow the
            // duplicate, the run's next live activity still stands down
            // (StaleRunException) before executing anything.
            ctx.cancellation().requestStale("append collision at sequence " + seq);
            throw e;
        }
    }

    /**
     * Persists an ACTIVITY_FAILED event with error details in the payload.
     *
     * @throws DuplicateEventException if a concurrent runner already persisted
     *                                 an outcome at {@code seq} — see
     *                                 {@link #appendEventSafe}
     */
    private void persistFailure(
            WorkflowContext ctx, int seq, String stepName, @Nullable String argsHash,
            ActivityExecutionException exception
    ) {
        JsonNode errorPayload;
        try {
            var errorDetail = new ErrorDetail(
                    exception.getCause() != null ? exception.getCause().getClass().getName() : "unknown",
                    exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage()
            );
            errorPayload = serializer.serialize(errorDetail);
        } catch (SerializationException e) {
            logger.warn("Failed to serialize error detail for '{}' at seq {}", stepName, seq, e);
            errorPayload = null;
        }

        appendEventSafe(ctx, seq, EventType.ACTIVITY_FAILED, stepName, errorPayload, argsHash);
    }

    /**
     * Structured error detail stored in the payload of ACTIVITY_FAILED events.
     */
    private record ErrorDetail(String exceptionType, @Nullable String message) {}

    /**
     * The exception type recorded for a live failure — the cause's class when
     * present (mirrors {@link ErrorDetail}), else the wrapper's.
     */
    private static String failureExceptionType(ActivityExecutionException exception) {
        return exception.getCause() != null
                ? exception.getCause().getClass().getName()
                : exception.getClass().getName();
    }

    /**
     * The exception type stored in a memoized {@code ACTIVITY_FAILED} payload,
     * or {@code "unknown"} when the payload does not carry one.
     */
    private static String storedExceptionType(WorkflowEvent event) {
        if (event.payload() != null && event.payload().has("exceptionType")) {
            var type = event.payload().get("exceptionType").stringValue();
            if (type != null && !type.isEmpty()) {
                return type;
            }
        }
        return "unknown";
    }

    // ── Lock management ───────────────────────────────────────────────

    /**
     * Attempts to acquire a distributed lock for this activity step.
     * Returns null if the lock backend is unavailable or the lock is already held.
     */
    private @Nullable LockHandle acquireLock(WorkflowContext ctx, int seq, String stepName) {
        if (distributedLock == null) {
            return null;
        }

        var lockKey = lockKeyPrefix + "activity:%s:%d".formatted(ctx.workflowId(), seq);
        var lockTtl = startToCloseTimeout.plusSeconds(10);

        try {
            var handle = distributedLock.tryAcquire(lockKey, lockTtl);
            if (handle.isEmpty()) {
                logger.warn("Could not acquire lock for activity '{}' at seq {}, proceeding with DB dedup only",
                        stepName, seq);
            }
            return handle.orElse(null);
        } catch (Exception e) {
            logger.warn("Lock backend error for activity '{}' at seq {}, proceeding without lock",
                    stepName, seq, e);
            return null;
        }
    }

    /**
     * Releases a distributed lock, logging but not propagating any failure.
     */
    private void releaseLock(@Nullable LockHandle lockHandle, String stepName, int seq) {
        if (lockHandle != null && distributedLock != null) {
            try {
                distributedLock.release(lockHandle);
            } catch (Exception e) {
                logger.warn("Failed to release lock for activity '{}' at seq {}", stepName, seq, e);
            }
        }
    }

    // ── Lifecycle event publishing ────────────────────────────────────

    /**
     * Publishes a lifecycle event for observability. The sink never blocks
     * and never throws, so this can never interrupt — or delay — the activity.
     */
    private void publishLifecycleEvent(
            WorkflowContext ctx, String stepName, LifecycleEventType eventType
    ) {
        lifecycleEventSink.publish(new WorkflowLifecycleEvent(
                ctx.workflowInstanceId(),
                ctx.workflowId(),
                ctx.workflowType(),
                ctx.serviceName(),
                ctx.taskQueue(),
                eventType,
                stepName,
                null,
                Instant.now()
        ));
    }

    // ── @Compensate registration ────────────────────────────────

    /**
     * If the activity method is annotated with {@link Compensate}, registers
     * a compensation action on the workflow's compensation stack.
     *
     * <p>The compensation action calls the compensation method through
     * the same proxy, so it is memoized, retriable, and replayable.
     */
    private void registerCompensationIfAnnotated(
            Object proxy, Method method,
            @Nullable Object @Nullable [] args,
            @Nullable Object result,
            WorkflowContext ctx
    ) {
        var compensate = method.getAnnotation(Compensate.class);
        if (compensate == null) {
            return;
        }

        var compensationMethodName = compensate.value();
        var compensationMethod = findCompensationMethod(method.getDeclaringClass(), compensationMethodName);
        var compensationArgs = resolveCompensationArgs(compensationMethod, method, args, result);
        var compensationStepName = activityName + "." + compensationMethodName;

        ctx.addCompensation(compensationStepName, () -> {
            try {
                compensationMethod.invoke(proxy, compensationArgs);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                // See invokeActivity(): an Error (ExecutorShutdownException) must
                // reach SagaManager's compensation loop intact, not re-wrapped.
                if (cause instanceof Error err) throw err;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException("Compensation '%s' failed".formatted(compensationStepName), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Compensation '%s' inaccessible".formatted(compensationStepName), e);
            }
        });
    }

    /**
     * Finds the compensation method by name on the activity interface.
     */
    private static Method findCompensationMethod(Class<?> activityInterface, String methodName) {
        for (var m : activityInterface.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        // Should not happen — validated at proxy creation time
        throw new IllegalStateException(
                "Compensation method '%s' not found on %s"
                        .formatted(methodName, activityInterface.getName()));
    }

    /**
     * Resolves the arguments to pass to the compensation method.
     *
     * <p>Convention-based resolution:
     * <ol>
     *   <li>0 params → no args</li>
     *   <li>1 param assignable from the activity's return type → pass the return value</li>
     *   <li>Same parameter types as the activity → pass the original args</li>
     * </ol>
     */
    static @Nullable Object @Nullable [] resolveCompensationArgs(
            Method compensationMethod, Method activityMethod,
            @Nullable Object @Nullable [] activityArgs,
            @Nullable Object activityResult
    ) {
        var compensationParams = compensationMethod.getParameterTypes();

        if (compensationParams.length == 0) {
            return null;
        }

        // Pattern 1: single param matching the activity's return type → pass the return value
        var returnType = activityMethod.getReturnType();
        if (compensationParams.length == 1
                && returnType != void.class
                && returnType != Void.class
                && compensationParams[0].isAssignableFrom(returnType)) {
            return new Object[]{ activityResult };
        }

        // Pattern 2: same parameters as the activity → pass the original args
        var activityParams = activityMethod.getParameterTypes();
        if (java.util.Arrays.equals(compensationParams, activityParams)) {
            return activityArgs != null ? activityArgs.clone() : null;
        }

        // Should not happen — validated at proxy creation time
        throw new IllegalStateException(
                "Cannot resolve arguments for compensation method '%s' on %s"
                        .formatted(compensationMethod.getName(),
                                compensationMethod.getDeclaringClass().getName()));
    }

    // ── Object method pass-through ────────────────────────────────────

    private @Nullable Object handleObjectMethod(
            Object proxy, Method method, @Nullable Object @Nullable [] args
    ) {
        return switch (method.getName()) {
            case "toString" -> "ActivityProxy[%s]".formatted(activityName);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
            default -> throw new UnsupportedOperationException(
                    "Unsupported Object method: " + method.getName());
        };
    }
}
