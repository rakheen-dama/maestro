package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.SerializationException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 *       persists the result, and returns it.</li>
 * </ol>
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
 * {@link WorkflowContext} on the current thread). All mutable state is
 * thread-local via the context.
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
    private final @Nullable WorkflowMessaging messaging;
    private final RetryPolicy retryPolicy;
    private final Duration startToCloseTimeout;
    private final PayloadSerializer serializer;
    private final RetryExecutor retryExecutor;
    private final String lockKeyPrefix;
    private final EngineObserver observer;

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
        this.messaging = messaging;
        this.retryPolicy = retryPolicy;
        this.startToCloseTimeout = startToCloseTimeout;
        this.serializer = serializer;
        this.retryExecutor = retryExecutor;
        this.lockKeyPrefix = lockKeyPrefix;
        this.observer = observer;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
            throws Throwable {

        // Pass-through for Object methods
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        var ctx = WorkflowContext.current();
        var seq = ctx.nextSequence();
        var stepName = activityName + "." + method.getName();

        // Activity-specific MDC keys (base workflow keys are set by the scoped executor)
        MDC.put("activityName", stepName);
        MDC.put("sequence", String.valueOf(seq));

        try {
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
            ctx.setReplaying(false);
            var result = executeLive(method, args, ctx, seq, stepName);
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
            WorkflowContext ctx, int seq, String stepName
    ) throws Throwable {
        // A terminated run stops here — before the lock, the ACTIVITY_STARTED
        // lifecycle event and observer.activityStarted — so it never opens an
        // activity span (or publishes a start) that no completion would close.
        // So does a run already known to be stale (R29): one of its appends
        // collided with another runner's event, and the author caught and
        // carried on past the DuplicateEventException — this activity is a
        // side effect the winner also runs. RetryExecutor repeats both checks
        // before every attempt; this only moves the first ahead of the side
        // effects.
        ctx.cancellation().throwIfTerminated(ctx.workflowId());
        ctx.cancellation().throwIfStale(ctx.workflowId());

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
                    persistFailure(ctx, seq, stepName, e);
                } catch (DuplicateEventException staleRun) {
                    // A concurrent runner already recorded an outcome at this
                    // sequence (Review Focus 4). The duplicate propagates
                    // INSTEAD of e: this run neither publishes ACTIVITY_FAILED
                    // nor surfaces a failure that contradicts the winner's
                    // record — it stands down. The observer still sees the
                    // failure this node genuinely executed: it measures this
                    // node's executions, not the durable record.
                    observer.activityFailed(info, elapsed, failureExceptionType(e), false);
                    throw staleRun;
                }
                publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_FAILED);
                // (An Error — e.g. a shutdown signal — never reaches this catch
                // and is never observed as a failure.)
                observer.activityFailed(info, elapsed, failureExceptionType(e), false);
                throw e;
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            // Persist success.
            try {
                appendEventSafe(ctx, seq, EventType.ACTIVITY_COMPLETED, stepName, serializer.serialize(result));
            } catch (DuplicateEventException staleRun) {
                // The winner is alive and ahead: stand down rather than adopt
                // its value (class Javadoc, "Idempotency"). No ACTIVITY_COMPLETED
                // lifecycle event — this run did not persist one — but the
                // observer sees the attempt this node genuinely executed.
                observer.activityCompleted(info, elapsed, false);
                throw staleRun;
            }

            publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_COMPLETED);
            observer.activityCompleted(info, elapsed, false);
            return result;

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
        Callable<@Nullable Object> body = () -> ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                .call(() -> invokeActivity(method, args));
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
            String stepName, @Nullable JsonNode payload
    ) {
        var event = new WorkflowEvent(
                UUID.randomUUID(),
                ctx.workflowInstanceId(),
                seq,
                type,
                stepName,
                payload,
                Instant.now()
        );

        try {
            store.appendEvent(event);
        } catch (DuplicateEventException e) {
            // Lost the append race: a concurrent runner already persisted an
            // outcome at this sequence (C5). Its payload is never adopted — the
            // winner is alive and ahead — but its TYPE is still checked first
            // (RULING 9): reached with no author error, from the engine's own
            // tolerated state (locks are best-effort), the winner may be a
            // newer node whose event type this build does not define, and that
            // stands down as UNKNOWN_EVENT_TYPE — the reason an operator needs
            // to see — rather than as a plain stale run.
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
            WorkflowContext ctx, int seq, String stepName,
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

        appendEventSafe(ctx, seq, EventType.ACTIVITY_FAILED, stepName, errorPayload);
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
     * Publishes a lifecycle event for observability. Failures are logged but
     * never interrupt workflow execution.
     */
    private void publishLifecycleEvent(
            WorkflowContext ctx, String stepName, LifecycleEventType eventType
    ) {
        if (messaging == null) {
            return;
        }

        try {
            messaging.publishLifecycleEvent(new WorkflowLifecycleEvent(
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
        } catch (Exception e) {
            logger.warn("Failed to publish {} lifecycle event for activity '{}'",
                    eventType, stepName, e);
        }
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
