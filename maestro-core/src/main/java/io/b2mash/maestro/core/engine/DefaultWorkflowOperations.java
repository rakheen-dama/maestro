package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.NodeErrors;
import io.b2mash.maestro.core.exception.RetryExhaustedException;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.TimerCancelledException;
import io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.ParkKind;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.retry.RetryUntilOptions;
import io.b2mash.maestro.core.saga.CompensationStack;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Default implementation of {@link WorkflowOperations} providing durable
 * sleep, signal await, parallel execution, and deterministic side-effects.
 *
 * <p>Each operation follows the hybrid memoization pattern:
 * <ol>
 *   <li>Check the event log for a stored result at the current sequence number.</li>
 *   <li><b>Replay path:</b> If found, return the stored value immediately.</li>
 *   <li><b>Live path:</b> If not found, execute the operation, persist the
 *       result, and return it.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>Each instance is used by a single workflow virtual thread. Infrastructure
 * dependencies (store, lock, parking lot) are thread-safe.
 *
 * @see WorkflowOperations
 * @see WorkflowContext
 */
public final class DefaultWorkflowOperations implements WorkflowOperations {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWorkflowOperations.class);

    /** Step-name prefix recorded on {@link EventType#VERSION_MARKER} events. */
    private static final String VERSION_STEP_PREFIX = "$maestro:version:";

    /** Step name of a sleep's {@code TIMER_FIRED} outcome event. */
    private static final String TIMER_FIRED_STEP = "$maestro:timer-fired";

    /** Step name of a sleep's {@code TIMER_CANCELLED} outcome event. */
    private static final String TIMER_CANCELLED_STEP = "$maestro:timer-cancelled";

    /**
     * The compensation buffer of the {@code parallel()} branch running on this
     * thread — bound only inside a branch's virtual thread (scoped values are
     * not inherited by {@code Thread.ofVirtual().start()}, so each branch, at
     * every nesting level, binds its own). While bound,
     * {@link #addCompensation} appends here instead of pushing onto the run's
     * stack; the fork flushes every branch's buffer in branch-index order once
     * all branches have joined. See {@link #parallel(List)}.
     */
    private static final ScopedValue<List<BufferedCompensation>> BRANCH_COMPENSATIONS =
            ScopedValue.newInstance();

    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final @Nullable WorkflowMessaging messaging;
    private final PayloadSerializer serializer;
    private final ParkingLot parkingLot;
    private final CompensationStack compensationStack;
    private final SignalManager signalManager;
    private final Duration wakeRecheckInterval;
    private final EngineObserver observer;
    private final TaskQueueGovernor governor;

    /**
     * Version decisions already resolved in this run, {@code changeId → version}.
     *
     * <p>Lifetime is exactly one local run — the executor builds a fresh
     * operations instance per launch — so recovery re-resolves from the durable
     * marker rather than from memory. Concurrent because parallel branches share
     * the instance.
     */
    private final ConcurrentHashMap<String, Integer> versionCache = new ConcurrentHashMap<>();

    /**
     * Creates workflow operations with the default wake re-check interval
     * (30s — the same default {@code SignalManager} uses).
     *
     * @param store             workflow store for event persistence and signal management
     * @param distributedLock   optional distributed lock backend
     * @param messaging         optional messaging for lifecycle event publishing
     * @param serializer        Jackson serializer for payloads
     * @param parkingLot        virtual thread parking mechanism
     * @param compensationStack LIFO compensation stack (shared with WorkflowExecutor)
     * @param signalManager     signal lifecycle manager for await/consume operations
     */
    public DefaultWorkflowOperations(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            CompensationStack compensationStack,
            SignalManager signalManager
    ) {
        this(store, distributedLock, messaging, serializer, parkingLot, compensationStack,
                signalManager, SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL);
    }

    /**
     * Creates workflow operations with an explicit wake re-check interval.
     *
     * @param store               workflow store for event persistence and signal management
     * @param distributedLock     optional distributed lock backend
     * @param messaging           optional messaging for lifecycle event publishing
     * @param serializer          Jackson serializer for payloads
     * @param parkingLot          virtual thread parking mechanism
     * @param compensationStack   LIFO compensation stack (shared with WorkflowExecutor)
     * @param signalManager       signal lifecycle manager for await/consume operations
     * @param wakeRecheckInterval how often a live {@link #sleep(Duration)} park
     *                            re-reads the durable timer row for a fire or
     *                            cancel that happened on another node — the
     *                            same interval {@code SignalManager} uses for
     *                            missed signal wakes
     *                            ({@code maestro.signal.wake-recheck-interval})
     */
    public DefaultWorkflowOperations(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            CompensationStack compensationStack,
            SignalManager signalManager,
            Duration wakeRecheckInterval
    ) {
        this(store, distributedLock, messaging, serializer, parkingLot, compensationStack,
                signalManager, wakeRecheckInterval, EngineObserver.NOOP);
    }

    /**
     * Creates workflow operations with an explicit wake re-check interval and
     * an {@link EngineObserver}.
     *
     * @param store               workflow store for event persistence and signal management
     * @param distributedLock     optional distributed lock backend
     * @param messaging           optional messaging for lifecycle event publishing
     * @param serializer          Jackson serializer for payloads
     * @param parkingLot          virtual thread parking mechanism
     * @param compensationStack   LIFO compensation stack (shared with WorkflowExecutor)
     * @param signalManager       signal lifecycle manager for await/consume operations
     * @param wakeRecheckInterval how often a live {@link #sleep(Duration)} park
     *                            re-reads the durable timer row for a fire or
     *                            cancel that happened on another node
     * @param observer            engine observation seam — fires
     *                            {@code timerScheduled}/{@code Fired}/{@code Cancelled}
     *                            and {@code workflowParked}/{@code workflowUnparked(TIMER)};
     *                            never {@code null} (pass {@link EngineObserver#NOOP})
     */
    public DefaultWorkflowOperations(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            CompensationStack compensationStack,
            SignalManager signalManager,
            Duration wakeRecheckInterval,
            EngineObserver observer
    ) {
        this(store, distributedLock, messaging, serializer, parkingLot, compensationStack,
                signalManager, wakeRecheckInterval, observer, TaskQueueGovernor.UNBOUNDED);
    }

    /**
     * Creates workflow operations with an explicit wake re-check interval, an
     * {@link EngineObserver} and a {@link TaskQueueGovernor}.
     *
     * @param store               workflow store for event persistence and signal management
     * @param distributedLock     optional distributed lock backend
     * @param messaging           optional messaging for lifecycle event publishing
     * @param serializer          Jackson serializer for payloads
     * @param parkingLot          virtual thread parking mechanism
     * @param compensationStack   LIFO compensation stack (shared with WorkflowExecutor)
     * @param signalManager       signal lifecycle manager for await/consume operations
     * @param wakeRecheckInterval how often a live {@link #sleep(Duration)} park
     *                            re-reads the durable timer row for a fire or
     *                            cancel that happened on another node
     * @param observer            engine observation seam; never {@code null}
     *                            (pass {@link EngineObserver#NOOP})
     * @param governor            per-task-queue concurrency limits — every park of
     *                            a {@link #sleep(Duration)} gives the run's
     *                            workflow slot back for its duration
     *                            ({@link TaskQueueGovernor#parked}); pass
     *                            {@link TaskQueueGovernor#UNBOUNDED} for none
     */
    public DefaultWorkflowOperations(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            CompensationStack compensationStack,
            SignalManager signalManager,
            Duration wakeRecheckInterval,
            EngineObserver observer,
            TaskQueueGovernor governor
    ) {
        this.store = store;
        this.distributedLock = distributedLock;
        this.messaging = messaging;
        this.serializer = serializer;
        this.parkingLot = parkingLot;
        this.compensationStack = compensationStack;
        this.signalManager = signalManager;
        this.wakeRecheckInterval = wakeRecheckInterval;
        this.observer = observer;
        this.governor = governor;
    }

    // ── sleep ──────────────────────────────────────────────────────────

    @Override
    public void sleep(Duration duration) {
        var ctx = WorkflowContext.current();
        // A run already known to be stale (R33) — one of its appends collided,
        // or a fresh read found the row finalised, and workflow code swallowed
        // the exception — stands down here, before it saves a timer row the
        // winner never scheduled.
        ctx.cancellation().throwIfStale(ctx.workflowId());
        var seq = ctx.nextSequence();
        var stepName = "$maestro:sleep";

        // Replay check: look for TIMER_SCHEDULED at this sequence
        var storedEvent = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
        if (storedEvent.isPresent()) {
            // Any present event that is not this sleep's TIMER_SCHEDULED is
            // drift (C10): stand down HERE, before the live path's
            // store.saveTimer below. Falling through used to insert a PENDING
            // timer row and only then collide on the append — one leaked row
            // per recovery pass, misreported as STALE_RUN.
            ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName,
                    EventType.TIMER_SCHEDULED);
            var timerId = extractTimerId(storedEvent.get().payload());
            // The scheduling is memoized history — re-observed with
            // replayed=true so counting adapters can skip it.
            observer.timerScheduled(observedTimer(ctx, timerId), true);
            // Check whether the outcome at the next sequence is already
            // memoized. Pure deterministic re-derivation from the event
            // log — no writes, no timer-row read — for both terminal outcomes.
            var nextSeq = seq + 1;
            var nextEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), nextSeq),
                    ctx.workflowId());
            // R19: the wake slot is a replay read too. Only this sleep's own
            // outcome may sit there — TIMER_FIRED or TIMER_CANCELLED, under the
            // step names recordTimerFired/recordTimerCancelled write. Anything
            // else is drift: stand down HERE, before the timer-row lookup and
            // re-park below (which would wait on a timer that does not exist
            // and then collide appending the outcome, misreported as stale_run).
            nextEvent.ifPresent(wake -> requireOwnWakeOutcome(wake, ctx.workflowId()));
            if (nextEvent.isPresent() && nextEvent.get().eventType() == EventType.TIMER_FIRED) {
                // Both events exist — skip the sleep entirely
                ctx.nextSequence(); // advance past TIMER_FIRED
                observer.timerFired(observedTimer(ctx, timerId), true);
                logger.debug("Replaying completed sleep at seq {} (skipped)", seq);
                return;
            }
            if (nextEvent.isPresent() && nextEvent.get().eventType() == EventType.TIMER_CANCELLED) {
                ctx.nextSequence(); // advance past TIMER_CANCELLED
                observer.timerCancelled(observedTimer(ctx, timerId), true);
                logger.debug("Replaying cancelled sleep at seq {} (skipped)", seq);
                throw new TimerCancelledException(ctx.workflowId(), timerId);
            }
            // TIMER_SCHEDULED exists but no terminal event does yet. Either
            // the timer really is still pending, or the node that owned
            // this workflow died inside the window between the timer row
            // transitioning and this thread appending the terminal event.
            // The durable row is what tells the two apart: only a PENDING
            // timer will ever be handed to a poller again, so re-parking
            // on a FIRED or CANCELLED one would wait forever.
            var rowStatus = store.findTimer(ctx.workflowInstanceId(), timerId)
                    .map(WorkflowTimer::status)
                    .orElse(null);

            if (rowStatus == TimerStatus.CANCELLED) {
                logger.debug("Replaying sleep at seq {} whose timer '{}' was cancelled — "
                        + "healing the missing TIMER_CANCELLED event instead of re-parking",
                        seq, timerId);
                recordTimerCancelled(ctx, timerId);
                updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_CANCELLED);
                throw new TimerCancelledException(ctx.workflowId(), timerId);
            }

            if (rowStatus == TimerStatus.FIRED) {
                logger.debug("Replaying sleep at seq {} whose timer '{}' already fired — "
                        + "healing the missing TIMER_FIRED event instead of re-parking",
                        seq, timerId);
            } else {
                logger.debug("Replaying pending sleep at seq {} — re-parking", seq);
                observer.workflowParked(observed(ctx), ParkKind.TIMER);
                parkForTimer(ctx, timerId);
                observer.workflowUnparked(observed(ctx), ParkKind.TIMER);
                // The wake that ends the re-park can be a fire or a
                // cancel racing in after this node came back up — the row
                // decides, exactly as it does on the live path below.
                recordWakeOutcome(ctx, stepName, timerId);
                return;
            }
            // Timer fired — record the TIMER_FIRED event
            recordTimerFired(ctx, timerId);
            // Restore RUNNING status (matches the live path)
            updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_FIRED);
            return;
        }

        // Live path: create and persist the timer
        ctx.setReplaying(false);
        var timerId = "sleep-" + seq;
        var timer = new WorkflowTimer(
                UUID.randomUUID(),
                ctx.workflowInstanceId(),
                ctx.workflowId(),
                timerId,
                Instant.now().plus(duration),
                TimerStatus.PENDING,
                Instant.now()
        );
        store.saveTimer(timer);

        // Append TIMER_SCHEDULED event
        var timerPayload = serializer.serialize(new TimerDetail(timerId, duration.toString()));
        appendEvent(ctx, seq, EventType.TIMER_SCHEDULED, stepName, timerPayload);
        observer.timerScheduled(observedTimer(ctx, timerId), false);

        // Update instance status
        updateInstanceStatus(ctx, WorkflowStatus.WAITING_TIMER);

        // Publish lifecycle event (best-effort)
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_SCHEDULED);

        // Park the virtual thread. Unparked fires only when the thread
        // actually resumes — a shutdown or terminate propagating out of the
        // park abandons the run and emits neither boundary.
        logger.debug("Workflow '{}' sleeping for {} (timerId={})", ctx.workflowId(), duration, timerId);
        observer.workflowParked(observed(ctx), ParkKind.TIMER);
        parkForTimer(ctx, timerId);
        observer.workflowUnparked(observed(ctx), ParkKind.TIMER);

        // Timer fired or cancelled — the row decides which (§3.1)
        recordWakeOutcome(ctx, stepName, timerId);
    }

    /**
     * Parks the workflow's virtual thread until its timer fires or is
     * cancelled — including on another node (Issue 17).
     *
     * <p>{@code TimerPoller} runs on the elected leader only, and
     * {@code fireTimer}/{@code cancelTimer} unpark through the per-JVM
     * {@link ParkingLot} — a no-op when the parked thread lives elsewhere. So
     * this parks in wake-recheck-interval chunks, mirroring
     * {@code SignalManager.awaitSignal}: every chunk expiry re-reads the
     * durable timer row, and a row a remote node already transitioned wakes
     * this thread within one interval instead of never. A local unpark
     * remains the instant fast path — when leader == owner the chunking is
     * unobservable.
     *
     * <p>On each expiry, before consulting the row, the instance is checked
     * for a cross-node terminate (same stand-down {@code awaitSignal}
     * performs per chunk): {@link WorkflowTerminatedException} propagates so
     * the run stops without writing anything. A row that is no longer
     * {@code PENDING} — or is defensively absent, since an absent row can
     * never be handed to a poller — ends the park; the caller's
     * {@code recordWakeOutcome} then reads the row and decides fired
     * vs. cancelled, exactly as it does after a local unpark.
     * {@code ExecutorShutdownException} from the park itself propagates
     * unchanged (an {@code Error} — the caller must not see it as a wake).
     *
     * @param ctx     the current workflow context
     * @param timerId the logical timer ID being slept on
     */
    private void parkForTimer(WorkflowContext ctx, String timerId) {
        var parkKey = ctx.workflowId() + ":timer:" + timerId;
        while (true) {
            try {
                // F8: the run gives its task-queue workflow slot back while
                // parked and takes one again — blocking if the queue is full —
                // when the chunk ends in a wake or an expiry. A control-flow
                // Error from the park (shutdown, terminate) propagates without
                // reacquiring.
                //
                // The per-CHUNK give is load-bearing for parallel(): a branch
                // that woke may take the run's slot back and finish while this
                // branch stays parked, so the run holds a slot with nothing
                // executing — bounded only because this loop's next chunk gives
                // it again. Wrapping the whole loop once instead would make that
                // over-hold permanent, deadlocking concurrency=1 whenever the
                // wake depends on another run of the same queue.
                governor.parked(ctx.workflowId(), () -> {
                    parkingLot.parkWithTimeout(parkKey, wakeRecheckInterval);
                    return null;
                });
                // Locally unparked — a fire or cancel; the row decides which.
                // But a row another runner finalised while this run was parked
                // must not get a TIMER_FIRED from it (R33): stand down first.
                standDownIfTerminated(ctx);
                return;
            } catch (TimeoutException e) {
                // No local wake this interval — fall through to the durable re-check
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // Unreachable: parkWithTimeout throws no other checked exception.
                throw new IllegalStateException("Unexpected exception while parked at key: " + parkKey, e);
            }

            // Terminate outranks a wake: a terminate usually lands on a node
            // that is not the owner, so nothing local unparks this thread. So
            // does a row another runner finalised (R33).
            standDownIfTerminated(ctx);

            // Advisory probe (Issue 20 / Ruling 5): a store exception here
            // must not tear down a healthy parked sleep — fall back to
            // PENDING (unchanged) so the loop simply re-parks and tries the
            // durable row again next interval.
            var rowStatus = ParkProbe.read("sleep.findTimer", ctx.workflowId(),
                    () -> store.findTimer(ctx.workflowInstanceId(), timerId)
                            .map(WorkflowTimer::status)
                            .orElse(null),
                    TimerStatus.PENDING);
            if (rowStatus != TimerStatus.PENDING) {
                logger.debug("Workflow '{}' timer '{}' transitioned to {} without a local wake — "
                                + "resuming from the durable row (cross-node fire/cancel)",
                        ctx.workflowId(), timerId, rowStatus);
                return;
            }
        }
    }

    /**
     * Stands the current run down if the workflow was terminated while parked
     * on its timer — the timer analogue of the per-chunk check
     * {@code SignalManager.awaitSignal} performs, bounding cross-node
     * terminate convergence for a parked {@code sleep()} to one
     * wake-recheck interval.
     *
     * <p>Called on every wake of the park — a local unpark as well as each
     * recheck-interval expiry — so the same read also catches the stale-run
     * case (R33, the timer analogue of {@code SignalManager}'s M7 check): a
     * row another runner finalised as {@code COMPLETED} or {@code FAILED}
     * while this run was parked. Without it the wake would memoize
     * {@code TIMER_FIRED} before the {@code RUNNING} status write's fresh read
     * stood the run down. The stand-down is sticky: the run's
     * {@code RunCancellation} is set to {@code STALE} first.
     *
     * <p>The read itself is advisory (Issue 20 / Ruling 5, via {@link ParkProbe}):
     * if the store is unreachable this interval, that means "can't prove
     * finalised" — fail open and keep parking rather than propagate the
     * store's exception. The instance is only ever actually stood down once a
     * probe succeeds and observes a terminal status.
     *
     * @param ctx the parked run's context
     * @throws WorkflowTerminatedException if the instance row is {@code TERMINATED}
     * @throws StaleRunException           if the instance row is any other
     *                                     terminal status — another runner
     *                                     finalised it
     */
    private void standDownIfTerminated(WorkflowContext ctx) {
        var workflowId = ctx.workflowId();
        @Nullable WorkflowStatus status = ParkProbe.<@Nullable WorkflowStatus>read(
                "sleep.standDownIfTerminated", workflowId,
                () -> store.getInstance(workflowId)
                        .map(WorkflowInstance::status)
                        .orElse(null),
                null);
        if (status == WorkflowStatus.TERMINATED) {
            logger.info("Workflow '{}' was terminated while parked on a timer — abandoning this run",
                    workflowId);
            throw new WorkflowTerminatedException(workflowId, null);
        }
        if (status != null && status.isTerminal()) {
            var detail = "timer wake while parked";
            logger.warn("Workflow '{}' is already {} — another runner finalised it while this run was "
                    + "parked on a timer; standing this stale run down without recording the wake",
                    workflowId, status);
            ctx.cancellation().requestStale(detail);
            throw new StaleRunException(workflowId, status, detail);
        }
    }

    /**
     * After a park on the timer's key resolves — whether from a fresh sleep
     * or a heal re-park — decides fired vs. cancelled from the durable timer
     * row, never from the in-memory unpark payload: the row is the single
     * source of truth for the outcome. Records the corresponding
     * {@code seq+1} event, restores {@code RUNNING} status, publishes the
     * matching lifecycle event, and either returns normally (fired) or
     * throws {@link TimerCancelledException} (cancelled) — the same outcome
     * is reproduced on every later replay because it is the event this call
     * appends that replay reads back.
     *
     * @param ctx      the current workflow context
     * @param stepName the step name for the lifecycle event
     * @param timerId  the logical timer ID that was just woken
     * @throws TimerCancelledException if the timer's durable row is {@code CANCELLED}
     */
    private void recordWakeOutcome(WorkflowContext ctx, String stepName, String timerId) {
        if (isCancelled(ctx, timerId)) {
            recordTimerCancelled(ctx, timerId);
            updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_CANCELLED);
            logger.debug("Workflow '{}' woke from sleep — timer '{}' was cancelled",
                    ctx.workflowId(), timerId);
            throw new TimerCancelledException(ctx.workflowId(), timerId);
        }
        // FIRED, or defensively absent — preserves today's behaviour
        // bit-for-bit for every workflow whose timers are never cancelled.
        recordTimerFired(ctx, timerId);
        updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_FIRED);
        logger.debug("Workflow '{}' woke from sleep (timerId={})", ctx.workflowId(), timerId);
    }

    /**
     * Whether this workflow's timer has been cancelled durably — used by
     * {@link #recordWakeOutcome} after a wake, when either outcome (fired or
     * cancelled) is still possible.
     *
     * <p>Answers "has the wake already happened and been lost?" for the
     * cancelled case — the question the event log alone cannot answer,
     * because the {@code TIMER_CANCELLED} event is appended by the workflow
     * thread <em>after</em> the row transitions.
     *
     * @param ctx     the current workflow context
     * @param timerId the logical timer ID that was just woken
     * @return {@code true} if the store holds a CANCELLED timer with that ID
     */
    private boolean isCancelled(WorkflowContext ctx, String timerId) {
        return store.findTimer(ctx.workflowInstanceId(), timerId)
                .map(timer -> timer.status() == TimerStatus.CANCELLED)
                .orElse(false);
    }

    /**
     * Appends the {@code TIMER_FIRED} event and observes the fire live.
     * This workflow-side record is the single place a fire is observed
     * exactly once, regardless of which node's poller fired the row — the
     * poller itself is deliberately not instrumented.
     */
    private void recordTimerFired(WorkflowContext ctx, String timerId) {
        var firedSeq = ctx.nextSequence();
        appendEvent(ctx, firedSeq, EventType.TIMER_FIRED, TIMER_FIRED_STEP, null);
        observer.timerFired(observedTimer(ctx, timerId), false);
    }

    /**
     * Stands the run down unless {@code wake} — the event at a replayed
     * {@code sleep()}'s outcome slot — is that sleep's own outcome: a
     * {@code TIMER_FIRED} recorded as {@value #TIMER_FIRED_STEP} or a
     * {@code TIMER_CANCELLED} recorded as {@value #TIMER_CANCELLED_STEP}.
     */
    private static void requireOwnWakeOutcome(WorkflowEvent wake, String workflowId) {
        if (wake.eventType() == EventType.TIMER_CANCELLED) {
            ReplayGuard.requireMatch(wake, workflowId, TIMER_CANCELLED_STEP, EventType.TIMER_CANCELLED);
        } else {
            // TIMER_FIRED, or a foreign event: the fired outcome is what the
            // detail names as expected.
            ReplayGuard.requireMatch(wake, workflowId, TIMER_FIRED_STEP, EventType.TIMER_FIRED);
        }
    }

    /** Appends the {@code TIMER_CANCELLED} event and observes the cancel live. */
    private void recordTimerCancelled(WorkflowContext ctx, String timerId) {
        var cancelledSeq = ctx.nextSequence();
        appendEvent(ctx, cancelledSeq, EventType.TIMER_CANCELLED, TIMER_CANCELLED_STEP, null);
        observer.timerCancelled(observedTimer(ctx, timerId), false);
    }

    /** Builds the identity-only timer observation record. */
    private static TimerInfo observedTimer(WorkflowContext ctx, String timerId) {
        return new TimerInfo(ctx.workflowId(), ctx.workflowType(), timerId);
    }

    /** Builds the identity-only workflow observation record. */
    private static WorkflowInfo observed(WorkflowContext ctx) {
        return new WorkflowInfo(ctx.workflowId(), ctx.workflowType(), ctx.serviceName());
    }

    private String extractTimerId(@Nullable JsonNode payload) {
        if (payload != null && payload.has("timerId")) {
            var id = payload.get("timerId").stringValue();
            if (id != null) return id;
        }
        return "unknown";
    }

    // ── awaitSignal ────────────────────────────────────────────────────

    @Override
    public <T> T awaitSignal(String signalName, Class<T> type, Duration timeout) {
        var ctx = WorkflowContext.current();
        return signalManager.awaitSignal(ctx, signalName, type, timeout);
    }

    // ── collectSignals ─────────────────────────────────────────────────

    @Override
    public <T> List<T> collectSignals(String signalName, Class<T> type, int count, Duration timeout) {
        var results = new ArrayList<T>(count);
        // Use memoized currentTime() for deterministic deadline on replay
        var deadline = currentTime().plus(timeout);

        for (int i = 0; i < count; i++) {
            var remaining = Duration.between(currentTime(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                var ctx = WorkflowContext.current();
                throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
            }
            results.add(awaitSignal(signalName, type, remaining));
        }

        return List.copyOf(results);
    }

    // ── parallel ───────────────────────────────────────────────────────

    @Override
    public <T> List<T> parallel(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        if (tasks.size() == 1) {
            try {
                return List.of(tasks.getFirst().call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Parallel branch 0 failed", e);
            }
        }

        var ctx = WorkflowContext.current();

        // Pre-check before any sequence is consumed: a fork whose branch blocks
        // would not fit in the int sequence space fails the workflow with a
        // typed SequenceSpaceExhaustedException and leaves the counter (and the
        // event log) untouched. The result is discarded — the real value is
        // computed from the consumed parentSeq below.
        SequenceSpace.nextParentSeq(ctx.workflowId(), ctx.currentSequence() + 1, tasks.size());

        var parentSeq = ctx.nextSequence();
        var branchCount = tasks.size();
        var nextParentSeq = SequenceSpace.nextParentSeq(ctx.workflowId(), parentSeq, branchCount);

        var results = new ArrayList<AtomicReference<T>>(branchCount);
        var errors = new ArrayList<AtomicReference<Throwable>>(branchCount);
        // One compensation buffer per branch, each written only by its own
        // branch thread and read here only after the join (the latch is the
        // happens-before edge).
        var compensationBuffers = new ArrayList<List<BufferedCompensation>>(branchCount);
        var joined = false;

        // The advance runs in finally so a branch failure leaves the counter past
        // the branch space — SagaManager.compensate() anchors from ctx.nextSequence(),
        // and anchoring at p+1 would make entry i's block coincide with branch i+1's
        // base (C1). Applies to the forked path (n >= 2); the empty and single-task
        // fast paths return before any sequence is consumed.
        try {
            // Replay check: look for existing parallel fork event at this sequence.
            // Guarded first: an unknown type here would otherwise fail the SIDE_EFFECT
            // comparison, fall through to the live path, re-fork every branch and
            // stand down only on the duplicate append — as STALE_RUN, after the
            // branches have already touched the outside world.
            var storedEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), parentSeq), ctx.workflowId());
            if (storedEvent.isPresent()) {
                // Drift (C10): a present event must be this fork's marker AND
                // record the same arity — checked before any branch thread is
                // spawned. A different count means the recorded branch blocks
                // belong to a different fork shape; forking anyway would replay
                // the wrong blocks (or run new branches live).
                ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), "$maestro:parallel",
                        EventType.SIDE_EFFECT);
                ReplayGuard.requireBranchCount(storedEvent.get(), ctx.workflowId(), branchCount);
            } else {
                // Live path: record the parallel fork point
                ctx.setReplaying(false);
                appendEvent(ctx, parentSeq, EventType.SIDE_EFFECT, "$maestro:parallel",
                        serializer.serialize(new ParallelDetail(branchCount)));
            }

            var latch = new CountDownLatch(branchCount);

            for (int i = 0; i < branchCount; i++) {
                results.add(new AtomicReference<>());
                errors.add(new AtomicReference<>());
                // Copy-on-write: written only by its own branch thread, but read
                // by describeDropped() while that thread may still be running
                // when the join was interrupted.
                compensationBuffers.add(new CopyOnWriteArrayList<>());
            }

            for (int i = 0; i < branchCount; i++) {
                var branchIndex = i;
                var task = tasks.get(i);

                // Each branch gets its own sequence space
                var branchBaseSeq = SequenceSpace.branchBase(parentSeq, branchIndex);

                Thread.ofVirtual()
                        .name("maestro-workflow-%s-%s-branch-%d".formatted(
                                ctx.workflowType(), ctx.workflowId(), branchIndex))
                        .start(() -> {
                            // Create a branch context with its own sequence counter
                            // but sharing the same operations instance so branches
                            // can call sleep/awaitSignal if needed
                            var branchCtx = WorkflowContext.bounded(ctx, branchIndex, branchBaseSeq,
                                    branchBaseSeq + SequenceSpace.BRANCH_MULTIPLIER,
                                    DefaultWorkflowOperations.this);
                            WorkflowMDC.populate(branchCtx);
                            try {
                                ScopedValue.where(WorkflowContext.scopedValue(), branchCtx)
                                        .where(BRANCH_COMPENSATIONS, compensationBuffers.get(branchIndex))
                                        .run(() -> {
                                            try {
                                                results.get(branchIndex).set(task.call());
                                            } catch (Throwable t) {
                                                errors.get(branchIndex).set(t);
                                            }
                                        });
                            } finally {
                                WorkflowMDC.clear();
                                latch.countDown();
                            }
                        });
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel execution interrupted", e);
            }
            joined = true;

            // Control-flow first (CLAUDE.md): an engine control-flow signal
            // (MaestroControlFlowError: shutdown, terminate, unknown-history
            // stand-down) in ANY branch outranks a plain failure in an earlier
            // one. The index-order loop below would otherwise rethrow branch 0's
            // exception and turn a routine deploy — or a mixed-version window —
            // into a recorded failure with compensations.
            for (int i = 0; i < branchCount; i++) {
                if (errors.get(i).get() instanceof MaestroControlFlowError signal) {
                    throw signal;
                }
            }

            // Node-level errors next (R33): a LinkageError or a
            // VirtualMachineError other than StackOverflowError in ANY branch
            // means this node is broken. Rethrown raw so executeWorkflow's
            // Error arm abandons the run as NODE_ERROR — index order would let
            // an earlier branch's plain failure record FAILED and compensate.
            for (int i = 0; i < branchCount; i++) {
                if (errors.get(i).get() instanceof Error nodeError && NodeErrors.isNodeError(nodeError)) {
                    throw nodeError;
                }
            }

            // Stale-run signals next (R27): a DuplicateEventException or
            // StaleRunException means another runner owns this workflow's
            // progress, so this run must stand down — not record FAILED and
            // compensate because an earlier branch happened to throw first.
            for (int i = 0; i < branchCount; i++) {
                var error = errors.get(i).get();
                if (error instanceof DuplicateEventException || error instanceof StaleRunException) {
                    throw (RuntimeException) error;
                }
            }

            // Otherwise fail fast with the first error, in branch-index order.
            // Any other Error is rethrown as-is too: wrapping it in a plain
            // RuntimeException would change what executeWorkflow's Error arm
            // sees (e.g. a node-level error recorded as a workflow failure).
            for (int i = 0; i < branchCount; i++) {
                var error = errors.get(i).get();
                if (error != null) {
                    if (error instanceof Error err) throw err;
                    if (error instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Parallel branch " + i + " failed", error);
                }
            }

        } finally {
            ctx.setSequence(nextParentSeq);
            // Branch compensations are registered in branch-index order, never
            // in finish order: finish order is a thread race a recovery replay
            // need not reproduce, and SagaManager matches each entry to its
            // recorded guard slot by name (C10) — finish-order registration
            // stood a correct saga down as nondeterministic_history. Flushed in
            // this finally so a branch that registered a compensation before a
            // sibling failed still has it unwound. Only after a full join: an
            // interrupted await leaves branches still writing their buffers.
            if (joined) {
                for (var buffer : compensationBuffers) {
                    for (var buffered : buffer) {
                        register(buffered.stepName(), buffered.action());
                    }
                }
            } else {
                // Not silently: these compensations will not be on the stack if
                // this run goes on to fail. (Empty when the fork threw before
                // spawning any branch — e.g. a drift stand-down — so no WARN.)
                var dropped = describeDropped(compensationBuffers);
                if (!dropped.isEmpty()) {
                    logger.warn("Workflow '{}': parallel() at seq {} did not join (interrupted) — "
                                    + "dropping the compensations its branches registered so far, "
                                    + "which will NOT run if this run fails: {}",
                            ctx.workflowId(), parentSeq, dropped);
                }
            }
        }

        // Collect results in order
        var resultList = new ArrayList<T>(branchCount);
        for (int i = 0; i < branchCount; i++) {
            resultList.add(results.get(i).get());
        }
        return List.copyOf(resultList);
    }

    // ── currentTime ────────────────────────────────────────────────────

    @Override
    public Instant currentTime() {
        var ctx = WorkflowContext.current();
        var seq = ctx.nextSequence();

        // Replay check
        var storedEvent = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
        if (storedEvent.isPresent()) {
            // Any present event that is not this side effect is drift (C10):
            // stand down before the live append below could collide with it.
            ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), "$maestro:currentTime",
                    EventType.SIDE_EFFECT);
            return UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                    "memoized currentTime()",
                    () -> serializer.deserialize(storedEvent.get().payload(), Instant.class));
        }

        // Live path
        ctx.setReplaying(false);
        var now = Instant.now();
        appendEvent(ctx, seq, EventType.SIDE_EFFECT, "$maestro:currentTime", serializer.serialize(now));
        return now;
    }

    // ── randomUUID ─────────────────────────────────────────────────────

    @Override
    public String randomUUID() {
        var ctx = WorkflowContext.current();
        var seq = ctx.nextSequence();

        // Replay check
        var storedEvent = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
        if (storedEvent.isPresent()) {
            // Any present event that is not this side effect is drift (C10):
            // stand down before the live append below could collide with it.
            ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), "$maestro:randomUUID",
                    EventType.SIDE_EFFECT);
            return UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                    "memoized randomUUID()",
                    () -> serializer.deserialize(storedEvent.get().payload(), String.class));
        }

        // Live path
        ctx.setReplaying(false);
        var uuid = UUID.randomUUID().toString();
        appendEvent(ctx, seq, EventType.SIDE_EFFECT, "$maestro:randomUUID", serializer.serialize(uuid));
        return uuid;
    }

    // ── version ────────────────────────────────────────────────────────

    @Override
    public int version(String changeId, int minSupported, int maxSupported) {
        if (changeId == null || changeId.isBlank()) {
            throw new IllegalArgumentException("changeId must not be blank");
        }
        if (maxSupported < 0) {
            throw new IllegalArgumentException(
                    "maxSupported must not be negative for change '%s', got %d"
                            .formatted(changeId, maxSupported));
        }
        if (minSupported > maxSupported) {
            throw new IllegalArgumentException(
                    "minSupported (%d) must not exceed maxSupported (%d) for change '%s'"
                            .formatted(minSupported, maxSupported, changeId));
        }

        var ctx = WorkflowContext.current();

        // Repeated calls with the same changeId in one run resolve once: the
        // original run wrote a single marker, so later call sites must neither
        // consume a sequence slot nor write. Branches share this cache because
        // they share the operations instance — see version()'s Javadoc for the
        // "resolve before forking" rule that follows from it.
        var cached = versionCache.get(changeId);
        if (cached != null) {
            return guardVersion(ctx, changeId, cached, minSupported, maxSupported);
        }

        // PEEK — read the slot without consuming it. A non-matching event there
        // means this history predates the change, and consuming the slot would
        // shift every later replay lookup by one and corrupt the run.
        // Deliberately NOT ReplayGuard-checked: a non-marker event at the peek is
        // "this history predates the change" by design, not drift
        // (ReplayDriftStandDownTest.versionPeekOverForeignEvent_isNotDrift pins it).
        var peekSeq = ctx.currentSequence() + 1;
        // Guarded BEFORE the predates-the-change classification below: an event
        // of a type this build does not know is not evidence that the history
        // predates the change, it is evidence that this node cannot read the
        // history at all. Classifying it as "predates" would silently take the
        // pre-change branch.
        var stored = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), peekSeq), ctx.workflowId());

        int resolved;
        if (stored.isPresent()) {
            var recorded = recordedVersion(stored.get(), changeId, ctx.workflowId(), peekSeq);
            if (recorded != null) {
                ctx.nextSequence(); // the marker is ours — consume the slot
                resolved = recorded;
                logger.debug("Replaying version {} for change '{}' at seq {}",
                        resolved, changeId, peekSeq);
            } else {
                resolved = WorkflowContext.DEFAULT_VERSION;
                logger.debug("No version marker at seq {} for change '{}' — history "
                        + "predates the change, resolving to DEFAULT_VERSION", peekSeq, changeId);
            }
        } else {
            // Live frontier: record the decision durably, then return it.
            var seq = ctx.nextSequence();
            ctx.setReplaying(false);
            appendEvent(ctx, seq, EventType.VERSION_MARKER, VERSION_STEP_PREFIX + changeId,
                    serializer.serialize(new VersionDetail(changeId, maxSupported)));
            resolved = maxSupported;
            logger.debug("Recorded version {} for change '{}' at seq {}", resolved, changeId, seq);
        }

        var checked = guardVersion(ctx, changeId, resolved, minSupported, maxSupported);
        versionCache.put(changeId, checked);
        return checked;
    }

    /**
     * Returns the version recorded by {@code event} for {@code changeId}, or
     * {@code null} if the event is genuine evidence that <em>this history
     * predates the change</em>. A {@code null} means the caller must resolve to
     * {@code DEFAULT_VERSION} <em>without</em> consuming the slot, because the
     * event found there belongs to the workflow's next step.
     *
     * <p>Three outcomes, deliberately kept apart:
     * <ul>
     *   <li><b>Not a marker at all</b> → {@code null}. The workflow's next step
     *       is sitting in that slot, which is exactly what a history written
     *       before the change looks like.</li>
     *   <li><b>Another change's marker</b> (readable, different {@code changeId})
     *       → {@code null}, for the same reason: a marker that demonstrably
     *       belongs to a different change is evidence that <em>this</em> change
     *       had not been introduced when the history was written.</li>
     *   <li><b>A marker this build cannot interpret</b> — no payload, no
     *       readable {@code changeId}, or this change's {@code changeId} with a
     *       {@code version} that is missing or not a number → <b>stand down</b>
     *       ({@link UnknownWorkflowHistoryException.Kind#UNKNOWN_EVENT_PAYLOAD}).</li>
     * </ul>
     *
     * <p>The third case used to return {@code null} along with the first two,
     * and that collapse was a silent-wrong-branch bug: a marker a newer node
     * reshaped ({@code "version":"3"} as a string) resolved to
     * {@code DEFAULT_VERSION}, so the instance took the <em>pre</em>-change
     * branch of a workflow whose durable history says it took the post-change
     * one — and, because the slot was not consumed, the stranded marker shifted
     * every later replay lookup by one. That surfaced (if at all) as an
     * unrelated {@code IllegalStateException} at the next activity slot, or
     * never, when the marker was the last event. A marker whose payload cannot
     * be read is not a decision this node is entitled to guess at.
     *
     * @param event      the peeked event
     * @param changeId   the change being resolved
     * @param workflowId the workflow, for the stand-down message
     * @param peekSeq    the peeked sequence, for the stand-down message
     * @return the recorded version, or {@code null} if the history predates the change
     * @throws UnknownWorkflowHistoryException if the event is a marker this
     *                                         build cannot interpret
     */
    private static @Nullable Integer recordedVersion(WorkflowEvent event, String changeId,
                                                     String workflowId, int peekSeq) {
        if (event.eventType() != EventType.VERSION_MARKER) {
            return null;
        }
        var payload = event.payload();
        if (payload == null) {
            throw UnknownHistoryGuard.payloadStandDown(workflowId, peekSeq,
                    "a VERSION_MARKER with no payload, so it can be attributed to no change");
        }
        var recordedChangeId = payload.path("changeId").asString(null);
        if (recordedChangeId == null) {
            throw UnknownHistoryGuard.payloadStandDown(workflowId, peekSeq,
                    "a VERSION_MARKER whose changeId is missing or not a string");
        }
        if (!changeId.equals(recordedChangeId)) {
            return null;
        }
        var version = payload.path("version");
        if (!version.isNumber()) {
            throw UnknownHistoryGuard.payloadStandDown(workflowId, peekSeq,
                    ("the VERSION_MARKER for change '%s' records a version this build cannot "
                            + "read (%s)").formatted(changeId, version));
        }
        return version.intValue();
    }

    /**
     * Enforces the floor: a recorded version below {@code minSupported} means
     * the running code no longer carries the branch this instance needs.
     */
    private static int guardVersion(WorkflowContext ctx, String changeId, int resolved,
                                    int minSupported, int maxSupported) {
        if (resolved < minSupported) {
            throw new UnsupportedWorkflowVersionException(
                    ctx.workflowId(), changeId, resolved, minSupported, maxSupported);
        }
        return resolved;
    }

    // ── retryUntil ──────────────────────────────────────────────────────

    @Override
    public <T> T retryUntil(Supplier<T> supplier, Predicate<T> predicate, RetryUntilOptions options) {
        // Use memoized currentTime() for deterministic deadline on replay
        var deadline = currentTime().plus(options.maxDuration());
        var attemptsCompleted = 0;

        for (int attempt = 0; attempt < options.maxAttempts(); attempt++) {
            // Check deadline before each new attempt (skip first — we always try at least once)
            if (attempt > 0 && !currentTime().isBefore(deadline)) {
                break;
            }

            T result = supplier.get(); // Activity call — memoized by the activity proxy
            attemptsCompleted++;

            if (predicate.test(result)) {
                return result;
            }

            // Check remaining budget and cap the sleep accordingly
            var remaining = Duration.between(currentTime(), deadline);
            if (!remaining.isPositive()) {
                break;
            }

            // Don't sleep after the last attempt
            if (attempt < options.maxAttempts() - 1) {
                var backoff = calculateRetryBackoff(options, attempt);
                // Cap backoff to remaining budget so we don't overshoot the deadline
                sleep(backoff.compareTo(remaining) < 0 ? backoff : remaining);
            }
        }

        var ctx = WorkflowContext.current();
        throw new RetryExhaustedException(ctx.workflowId(), attemptsCompleted);
    }

    private static Duration calculateRetryBackoff(RetryUntilOptions options, int attempt) {
        // Use double arithmetic throughout to avoid long overflow on large attempt counts.
        // Math.pow(2.0, 62) * 5000 exceeds Long.MAX_VALUE — capping in double space first
        // ensures Math.min produces the correct result before the cast to long.
        double delayMs = options.initialInterval().toMillis()
                * Math.pow(options.backoffMultiplier(), attempt);
        double cappedMs = Math.min(delayMs, options.maxInterval().toMillis());
        return Duration.ofMillis(Math.max((long) cappedMs, 0L));
    }

    // ── addCompensation ────────────────────────────────────────────────

    @Override
    public void addCompensation(Runnable compensation) {
        register(null, compensation);
    }

    @Override
    public void addCompensation(String stepName, Runnable compensation) {
        register(stepName, compensation);
    }

    /**
     * Routes a registration to the current {@code parallel()} branch's buffer
     * when called on a branch thread — including a fork's own flush, so a
     * nested fork's entries land in its enclosing branch's buffer — and onto
     * the run's stack otherwise. An unnamed entry is numbered only when it
     * reaches the stack, so its {@code $compensation:N} name is deterministic too.
     */
    private void register(@Nullable String stepName, Runnable action) {
        if (BRANCH_COMPENSATIONS.isBound()) {
            BRANCH_COMPENSATIONS.get().add(new BufferedCompensation(stepName, action));
        } else if (stepName == null) {
            compensationStack.push(action);
        } else {
            compensationStack.push(stepName, action);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private void appendEvent(WorkflowContext ctx, int seq, EventType type,
                             String stepName, @Nullable JsonNode payload) {
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
        } catch (DuplicateEventException staleRun) {
            // Another runner owns this slot: the run stands down, and stays
            // stale even if workflow code swallows the duplicate (R33).
            ctx.cancellation().requestStale("append collision at sequence " + seq);
            throw staleRun;
        }
    }

    private void updateInstanceStatus(WorkflowContext ctx, WorkflowStatus newStatus) {
        InstanceStatusWriter.write(store, ctx, newStatus);
    }

    private void publishLifecycleEvent(WorkflowContext ctx, String stepName, LifecycleEventType eventType) {
        if (messaging == null) return;
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
            logger.warn("Failed to publish {} lifecycle event for workflow '{}'", eventType, ctx.workflowId(), e);
        }
    }

    // ── Data records for event payloads ────────────────────────────────

    private record TimerDetail(String timerId, String duration) {}
    private record ParallelDetail(int branchCount) {}

    /**
     * A compensation registered inside a {@code parallel()} branch, held until
     * the fork joins. Package-private only so the dropped-registration WARN's
     * wording ({@link #describeDropped}) can be pinned.
     */
    record BufferedCompensation(@Nullable String stepName, Runnable action) {}

    /**
     * Names the registrations a {@code parallel()} drops when it cannot flush
     * (its join was interrupted), grouped by branch index; branches with none
     * are omitted. Package-private so the wording is pinned without an SLF4J
     * binding.
     */
    static String describeDropped(List<? extends List<BufferedCompensation>> buffers) {
        var out = new StringJoiner("; ");
        for (int i = 0; i < buffers.size(); i++) {
            var names = List.copyOf(buffers.get(i)).stream()
                    .map(b -> b.stepName() != null ? b.stepName() : "<unnamed>")
                    .toList();
            if (!names.isEmpty()) {
                out.add("branch " + i + ": " + names);
            }
        }
        return out.toString();
    }

    /** Payload of a {@link EventType#VERSION_MARKER} event. */
    private record VersionDetail(String changeId, int version) {}
}
