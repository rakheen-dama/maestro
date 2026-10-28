package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.ParkKind;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.TraceContextHolder;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the complete lifecycle of workflow signals: delivery, persistence,
 * await/consume, and orphan adoption.
 *
 * <p>Signal handling involves three phases:
 * <ol>
 *   <li><b>Delivery:</b> External callers send signals via
 *       {@link #deliverSignal}. The signal is persisted immediately and
 *       the target workflow is unparked if it is currently waiting.</li>
 *   <li><b>Await:</b> A running workflow calls {@link #awaitSignal} to
 *       block until a matching signal arrives (or replay the stored result).</li>
 *   <li><b>Adoption:</b> Signals sent before the workflow instance exists
 *       are adopted via {@link #adoptOrphanedSignals} when the instance
 *       is created.</li>
 * </ol>
 *
 * <h2>Self-Recovery</h2>
 * <p>Signals are <b>always</b> persisted to Postgres before in-memory delivery.
 * This guarantees three self-recovery scenarios:
 * <ol>
 *   <li>Signal arrives before {@code awaitSignal()} — stored unconsumed,
 *       consumed when the workflow reaches the await point.</li>
 *   <li>Signal arrives before workflow starts — stored with {@code null}
 *       instance ID, adopted when the instance is created.</li>
 *   <li>Signal arrives while service is down — persisted by a Kafka
 *       consumer on another instance, found during recovery replay.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>All public methods are thread-safe. {@link #deliverSignal} may be
 * called concurrently from any thread. {@link #awaitSignal} is called
 * from the workflow's virtual thread — the {@link WorkflowContext} is
 * passed explicitly rather than read from the thread-local, keeping
 * this class decoupled from thread identity.
 *
 * @see WorkflowExecutor
 * @see DefaultWorkflowOperations
 */
final class SignalManager {

    private static final Logger logger = LoggerFactory.getLogger(SignalManager.class);

    /**
     * How often a parked await re-checks the store for a signal persisted
     * without a notification reaching this instance — e.g. ingested on
     * another node in a deployment with no {@link SignalNotifier} (Kafka
     * messaging without Valkey), or delivered in the narrow window
     * before the park registered. Bounds cross-node signal latency in those
     * cases; with a working notifier the wake is instant and this never fires.
     *
     * <p>Also the default interval at which a parked {@code sleep()} re-reads
     * its durable timer row ({@link DefaultWorkflowOperations}) — a timer
     * fired or cancelled by a remote timer-poller leader has no local unpark
     * either (Issue 17).
     */
    static final Duration DEFAULT_WAKE_RECHECK_INTERVAL = Duration.ofSeconds(30);

    /** Step-name prefix of every event an {@code awaitSignal} call records. */
    static final String STEP_PREFIX = "$maestro:awaitSignal:";

    /**
     * Logical-ID prefix of a parked await's deadline timer row; the suffix is
     * the sequence of the await's {@code TIMER_SCHEDULED} memo.
     */
    static final String TIMER_ID_PREFIX = "await-signal-";

    private final WorkflowStore store;
    private final @Nullable WorkflowMessaging messaging;
    private final @Nullable SignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final ParkingLot parkingLot;
    private final Duration wakeRecheckInterval;
    private final EngineObserver observer;

    /**
     * Ref-counted cross-instance wake subscriptions, keyed by workflow ID.
     *
     * <p>The {@link SignalNotifier} SPI supports one callback per workflow ID,
     * but parallel branches of one workflow may await different signals
     * concurrently — the count ensures the shared subscription is only torn
     * down when the last await finishes.
     *
     * <p>Bookkeeping happens under the map's bin lock, but the notifier
     * subscribe/unsubscribe I/O deliberately happens <em>outside</em> it — a
     * slow notifier must not stall unrelated awaits. The cost is a narrow
     * subscribe-vs-unsubscribe interleaving in which a counted await can end
     * up without an active physical subscription; the periodic store re-check
     * bounds the resulting wake delay (the notifier is an optimisation, not a
     * correctness requirement).
     *
     * <p>Each entry also carries the first waiter's subscribe outcome
     * ({@link WakeSubscription#settled}). A later concurrent waiter of the same
     * workflow joins the count and then waits on it — bounded by
     * {@link #WAKE_SUBSCRIBE_JOIN_TIMEOUT} — so it never parks before the
     * shared subscription has been acknowledged (item 23).
     */
    private final ConcurrentHashMap<String, WakeSubscription> wakeSubscriptions = new ConcurrentHashMap<>();

    /**
     * Longest a concurrent waiter waits for the first waiter's wake subscribe
     * to settle before parking anyway (the periodic re-check covers it). Matches
     * the Valkey notifier's own acknowledgement bound.
     */
    static final Duration WAKE_SUBSCRIBE_JOIN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * One workflow's shared wake subscription: the number of awaits using it,
     * and the future the first waiter completes once its subscribe call
     * returned (acknowledged, timed out, or failed — joiners only need to know
     * it is no longer in flight).
     *
     * <p>{@code count} is read and written only inside
     * {@link ConcurrentHashMap#compute} on {@link #wakeSubscriptions}, whose
     * per-key lock provides the necessary happens-before.
     */
    private static final class WakeSubscription {
        int count = 1;
        final CompletableFuture<Void> settled = new CompletableFuture<>();
    }

    /**
     * Bridge from a deadline-row transition to the parked await it belongs
     * to: key {@code workflowId + ":" + timerId}, value the await's signal
     * park key. Populated only while an await is inside its wait loop on this
     * node, so {@link #onTimerTransition} is a no-op for every other timer.
     */
    private final ConcurrentHashMap<String, String> awaitWakeKeys = new ConcurrentHashMap<>();

    /**
     * Creates a new signal manager with the default wake re-check interval.
     *
     * @param store          workflow store for signal persistence and event memoization
     * @param messaging      optional messaging for lifecycle event publishing
     * @param signalNotifier optional cross-instance signal notification (e.g., Valkey pub/sub)
     * @param serializer     Jackson serializer for signal payloads
     * @param parkingLot     virtual thread parking mechanism for signal await
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            ParkingLot parkingLot
    ) {
        this(store, messaging, signalNotifier, serializer, parkingLot, DEFAULT_WAKE_RECHECK_INTERVAL);
    }

    /**
     * Creates a new signal manager with a custom wake re-check interval
     * (package-private, for tests).
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            Duration wakeRecheckInterval
    ) {
        this(store, messaging, signalNotifier, serializer, parkingLot, wakeRecheckInterval,
                EngineObserver.NOOP);
    }

    /**
     * Creates a new signal manager with a custom wake re-check interval and
     * an {@link EngineObserver}.
     *
     * @param observer engine observation seam — fires {@code signalPersisted},
     *                 {@code signalConsumed} and {@code workflowParked}/
     *                 {@code workflowUnparked(SIGNAL)}; never {@code null}
     *                 (pass {@link EngineObserver#NOOP})
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            Duration wakeRecheckInterval,
            EngineObserver observer
    ) {
        this.store = store;
        this.messaging = messaging;
        this.signalNotifier = signalNotifier;
        this.serializer = serializer;
        this.parkingLot = parkingLot;
        this.wakeRecheckInterval = wakeRecheckInterval;
        this.observer = observer;
    }

    // ── Delivery ────────────────────────────────────────────────────────

    /**
     * Delivers a signal to a workflow.
     *
     * <p>Persists the signal to the store and unparks the workflow if it
     * is currently waiting for this signal. If the workflow hasn't reached
     * the await point yet, the signal is stored and consumed when the
     * workflow calls {@link #awaitSignal}.
     *
     * <p>If the workflow instance does not yet exist, the signal is persisted
     * with a {@code null} instance ID (pre-delivery pattern) and adopted
     * when the instance is created via {@link #adoptOrphanedSignals}.
     *
     * @param workflowId the target workflow's business ID
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     */
    void deliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        // Determine workflow instance ID (may be null for pre-delivery)
        UUID workflowInstanceId = null;
        String workflowType = null;
        var instance = store.getInstance(workflowId);
        if (instance.isPresent()) {
            workflowInstanceId = instance.get().id();
            workflowType = instance.get().workflowType();
        }

        // Capture the calling thread's trace context, if a traced transport
        // put one there (design §4.3(a)). Opaque to the engine: stored and
        // returned verbatim, never parsed. Absence is normal, not an error.
        //
        // The one thing checked is LENGTH, and only to protect the insert. The
        // holder is public API, so a transport or an embedder can put anything
        // there; a value wider than the column would make saveSignal throw,
        // and since delivery runs inside the transport listener that failure
        // means the record is never acked and the signal is eventually
        // dead-lettered — discarded. Decorative metadata must never cost a
        // signal its delivery, so an over-long value degrades to no trace
        // context (RULING 2: absence, never an error). This is a bound on size,
        // not an interpretation of contents.
        var traceContext = TraceContextHolder.current();
        if (traceContext != null && traceContext.length() > TraceContextHolder.MAX_LENGTH) {
            logger.warn("Discarding a {}-character trace context for signal '{}' on workflow "
                            + "'{}' — the limit is {}. The signal is delivered untraced.",
                    traceContext.length(), signalName, workflowId, TraceContextHolder.MAX_LENGTH);
            traceContext = null;
        }

        // Persist the signal — always before in-memory delivery
        var signalPayload = payload != null ? serializer.serialize(payload) : null;
        var signal = new WorkflowSignal(
                UUID.randomUUID(),
                workflowInstanceId,
                workflowId,
                signalName,
                signalPayload,
                false,
                Instant.now(),
                traceContext
        );
        store.saveSignal(signal);
        observer.signalPersisted(new SignalInfo(workflowId, workflowType, signalName, traceContext));

        // Unpark if waiting locally
        var parkKey = workflowId + ":signal:" + signalName;
        parkingLot.unpark(parkKey, signalPayload);

        // Notify other instances (best-effort)
        if (signalNotifier != null) {
            try {
                signalNotifier.publish(workflowId, signalName);
            } catch (Exception e) {
                logger.warn("Failed to publish signal notification for workflow '{}': {}",
                        workflowId, e.getMessage());
            }
        }

        logger.debug("Delivered signal '{}' to workflow '{}'", signalName, workflowId);
    }

    // ── Await ───────────────────────────────────────────────────────────

    /**
     * Waits for a named signal to be delivered to the current workflow.
     *
     * <p>Follows the hybrid memoization pattern. The event layout depends on
     * whether the await had to park (C12 / F4):
     * <table>
     *   <caption>Event layout of one {@code awaitSignal} call</caption>
     *   <tr><th>Path</th><th>Slot {@code seq}</th><th>Slot {@code seq+1}</th><th>Timer row</th></tr>
     *   <tr><td>Signal already unconsumed at entry</td><td>{@code SIGNAL_RECEIVED}</td><td>—</td><td>none</td></tr>
     *   <tr><td>{@code timeout <= 0} and no signal</td><td>{@code SIGNAL_TIMEOUT}</td><td>—</td><td>none</td></tr>
     *   <tr><td>Had to park</td><td>{@code TIMER_SCHEDULED} ({@link AwaitSignalDetail})</td>
     *       <td>{@code SIGNAL_RECEIVED} or {@code SIGNAL_TIMEOUT}</td>
     *       <td>{@code await-signal-<seq>}, {@code fireAt = deadline}</td></tr>
     * </table>
     *
     * <p>Every event is recorded under the step name
     * {@code $maestro:awaitSignal:<signalName>}, and every replayed slot is
     * checked against it ({@link ReplayGuard}, C10).
     *
     * <p>A parked await's deadline is a durable timer row, not an in-memory
     * {@code Instant}: the timer poller fires it like any {@code sleep()}
     * timer, {@code WorkflowExecutor.cancelTimer} on it ends the wait with
     * {@link SignalTimeoutException}, a replay resumes from the memoized
     * deadline (never from {@code Instant.now()}), and
     * {@code TestWorkflowEnvironment.advanceTime()} drives it. See
     * {@link #awaitOutcome} for the one rule that decides the outcome.
     *
     * @param ctx        the current workflow context (passed explicitly, not read from thread-local)
     * @param signalName the signal name to wait for
     * @param type       the expected payload type
     * @param timeout    maximum time to wait; zero or negative means "consume an
     *                   already-arrived signal, otherwise time out now"
     * @param <T>        the payload type
     * @return the deserialized signal payload
     * @throws SignalTimeoutException if the deadline passes before a signal arrives
     */
    <T> T awaitSignal(WorkflowContext ctx, String signalName, Class<T> type, Duration timeout) {
        // A run already known to be stale (R33) — workflow code swallowed an
        // earlier collision or StaleRunException — stands down before it can
        // consume a signal that belongs to the winner's run.
        ctx.cancellation().throwIfStale(ctx.workflowId());
        var seq = ctx.nextSequence();
        var stepName = STEP_PREFIX + signalName;

        var storedEvent = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
        if (storedEvent.isPresent()) {
            // Drift (C10): the slot must hold THIS await's first event. Matching
            // on type alone returned a renamed await's recorded payload as the
            // answer to the new name; any other foreign event fell through to
            // the live path and could consume a waiting signal. Both are
            // unreachable once this throws — before any signal read.
            var stored = ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName,
                    EventType.SIGNAL_RECEIVED, EventType.SIGNAL_TIMEOUT, EventType.TIMER_SCHEDULED);
            if (stored.eventType() == EventType.TIMER_SCHEDULED) {
                return resumeParkedAwait(ctx, seq, stepName, signalName, type, timeout, stored);
            }
            return replayOutcome(ctx, seq, signalName, type, timeout, stored);
        }

        // Live path
        ctx.setReplaying(false);

        // Check for already-arrived signals (self-recovery)
        var unconsumed = store.getUnconsumedSignals(ctx.workflowId(), signalName);
        if (!unconsumed.isEmpty()) {
            var signal = unconsumed.getFirst();
            return consumeSignal(ctx, seq, stepName, signalName, signal, type);
        }

        // Review Focus 2: nothing to wait for. Time out now, memoized, and
        // write no timer row — a fireAt in the past would only be a junk row
        // the next poll fires for nobody.
        if (!timeout.isPositive()) {
            appendTimeout(ctx, seq, stepName, signalName, timeout);
            throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
        }

        // No signal yet — subscribe for cross-instance wake, then park.
        // Subscription is a performance optimisation: if it fails (or a
        // notification slips through before the park registers), the signal
        // is still found via the periodic store re-check in awaitOutcome.
        subscribeForWake(ctx.workflowId());
        try {
            if (signalNotifier != null) {
                // Close the check→subscribe race: a signal persisted on another
                // node before our subscription became active would never notify
                // us. Without a notifier there is no such race to close.
                var raced = store.getUnconsumedSignals(ctx.workflowId(), signalName);
                if (!raced.isEmpty()) {
                    return consumeSignal(ctx, seq, stepName, signalName, raced.getFirst(), type);
                }
            }

            // Schedule the durable deadline. The row is saved BEFORE the memo
            // (mirrors sleep()): a crash between the two leaves an orphan
            // PENDING row that fires for nobody, never a memo whose row is
            // missing. Micros, so the memo and a Postgres timestamptz agree.
            //
            // M2: a row for this timer id may already exist with no memo — a
            // crash between saveTimer and the append, or a runner race. Reuse
            // it and its fireAt rather than writing a second row under the same
            // id (findTimer could then pick either). A non-PENDING leftover
            // means that deadline is already spent (fired, or cancelled by an
            // operator): reusing it makes the await time out unless a signal
            // is visible — the row arbitrates, exactly as on replay.
            var timerId = TIMER_ID_PREFIX + seq;
            var existing = store.findTimer(ctx.workflowInstanceId(), timerId);
            Instant deadline;
            UUID timerDbId;
            if (existing.isPresent()) {
                deadline = existing.get().fireAt();
                timerDbId = existing.get().id();
                logger.debug("Workflow '{}' await of '{}' reuses existing deadline row '{}' ({}, fireAt={})",
                        ctx.workflowId(), signalName, timerId, existing.get().status(), deadline);
            } else {
                var now = Instant.now();
                deadline = now.plus(timeout).truncatedTo(ChronoUnit.MICROS);
                timerDbId = UUID.randomUUID();
                store.saveTimer(new WorkflowTimer(timerDbId, ctx.workflowInstanceId(), ctx.workflowId(),
                        timerId, deadline, TimerStatus.PENDING, now));
            }
            appendEvent(ctx, seq, EventType.TIMER_SCHEDULED, stepName, serializer.serialize(
                    new AwaitSignalDetail(signalName, timerId, timeout.toString(), deadline.toString())));

            var outcomeSeq = ctx.nextSequence();
            logger.debug("Workflow '{}' waiting for signal '{}' (timeout={}, timerId={})",
                    ctx.workflowId(), signalName, timeout, timerId);
            return awaitOutcome(ctx, outcomeSeq, stepName, signalName, type, timerId, timerDbId,
                    deadline, timeout);
        } finally {
            unsubscribeForWake(ctx.workflowId());
        }
    }

    /**
     * Replays a memoized outcome — {@code SIGNAL_RECEIVED} returns its
     * payload, {@code SIGNAL_TIMEOUT} re-raises — from the log alone, with no
     * store read and no signal consumption.
     *
     * <p>Without the timeout branch (Issue 19), a replay whose awaited signal
     * had ARRIVED since the original timeout consumed it and diverged from
     * the original execution — observed as a saga compensating at the wrong
     * gate and leaking its reserved resource after a routine rolling restart.
     * The late signal stays durably unconsumed (never discarded); a later
     * await of the same name will find and consume it. Mirrors Issue 13's
     * {@code TIMER_CANCELLED} memoization.
     */
    private <T> T replayOutcome(WorkflowContext ctx, int seq, String signalName, Class<T> type,
                                Duration timeout, WorkflowEvent outcome) {
        if (outcome.eventType() == EventType.SIGNAL_TIMEOUT) {
            logger.debug("Replaying timed-out await of '{}' at seq {} — re-raising deterministically",
                    signalName, seq);
            throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
        }
        logger.debug("Replaying signal '{}' at seq {}", signalName, seq);
        observer.signalConsumed(
                new SignalInfo(ctx.workflowId(), ctx.workflowType(), signalName, null), true);
        return UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                "payload of signal '%s'".formatted(signalName),
                () -> serializer.deserialize(outcome.payload(), type));
    }

    /**
     * Replays an await that parked: its {@code TIMER_SCHEDULED} memo is at
     * {@code seq}, so the outcome belongs at {@code seq + 1}. A memoized
     * outcome replays; an absent one resumes the durable wait from the
     * memoized deadline and the existing timer row — never re-armed from
     * {@code Instant.now()}, never a second row.
     */
    private <T> T resumeParkedAwait(WorkflowContext ctx, int seq, String stepName, String signalName,
                                    Class<T> type, Duration timeout, WorkflowEvent scheduled) {
        var outcomeSeq = ctx.nextSequence();
        var outcome = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), outcomeSeq), ctx.workflowId());
        if (outcome.isPresent()) {
            // Drift (C10) at the outcome slot: only this await's own outcome
            // may sit there. Anything else stands down HERE, before the signal
            // read below could consume a waiting signal.
            var stored = ReplayGuard.requireMatch(outcome.get(), ctx.workflowId(), stepName,
                    EventType.SIGNAL_RECEIVED, EventType.SIGNAL_TIMEOUT);
            return replayOutcome(ctx, outcomeSeq, signalName, type, timeout, stored);
        }

        // The outcome slot is the live frontier. The detail is only needed
        // from here on: a memoized outcome replays without reading it.
        ctx.setReplaying(false);
        var detail = readAwaitDetail(ctx.workflowId(), seq, signalName, scheduled);
        var deadline = UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                "await deadline of signal '%s'".formatted(signalName),
                () -> parseDeadline(detail.deadline()));
        @Nullable UUID timerDbId = store.findTimer(ctx.workflowInstanceId(), detail.timerId())
                .map(WorkflowTimer::id)
                .orElseGet(() -> rearmDeadlineRow(ctx, detail.timerId(), deadline));
        logger.debug("Replaying parked await of '{}' at seq {} — resuming the durable wait (deadline={})",
                signalName, seq, deadline);
        subscribeForWake(ctx.workflowId());
        try {
            return awaitOutcome(ctx, outcomeSeq, stepName, signalName, type, detail.timerId(), timerDbId,
                    deadline, timeout);
        } finally {
            unsubscribeForWake(ctx.workflowId());
        }
    }

    /**
     * Re-arms the deadline row of a parked await whose memo survived but whose
     * row did not (e.g. deleted out of band). A missing row is not a spent
     * deadline: the memoized wall-clock deadline still governs, so the row is
     * re-created PENDING at exactly that {@code fireAt} — the poller,
     * {@code cancelTimer} and {@code advanceTime()} can drive it again.
     * Best-effort: if the save fails, the await still times out on the
     * memoized deadline by the wall clock.
     *
     * @return the new row's id, or {@code null} if it could not be saved
     */
    private @Nullable UUID rearmDeadlineRow(WorkflowContext ctx, String timerId, Instant deadline) {
        var rowId = UUID.randomUUID();
        try {
            store.saveTimer(new WorkflowTimer(rowId, ctx.workflowInstanceId(), ctx.workflowId(),
                    timerId, deadline, TimerStatus.PENDING, Instant.now()));
            logger.warn("Workflow '{}' await deadline row '{}' was missing on replay — re-armed it at the "
                    + "memoized deadline {}", ctx.workflowId(), timerId, deadline);
            return rowId;
        } catch (RuntimeException e) {
            logger.warn("Workflow '{}' await deadline row '{}' is missing and could not be re-armed — "
                            + "the memoized deadline {} still applies by the wall clock: {}",
                    ctx.workflowId(), timerId, deadline, e.toString());
            return null;
        }
    }

    /**
     * Waits for the outcome of a parked await and records it at
     * {@code outcomeSeq} — shared by the live path and a resumed replay.
     *
     * <p>The one rule, applied on every pass:
     * <ol>
     *   <li>A visible unconsumed signal wins: consume it at the outcome slot,
     *       best-effort cancel the deadline row, return.</li>
     *   <li>Otherwise, if the timer row is no longer {@code PENDING} (fired by
     *       a poller, or cancelled), or the deadline has passed: self-fire a
     *       still-{@code PENDING} row (CAS; the result does not matter), re-read
     *       the signals once more — any wins as in 1 — and otherwise memoize
     *       {@code SIGNAL_TIMEOUT} and throw {@link SignalTimeoutException}.</li>
     *   <li>Otherwise park on the signal key for
     *       {@code min(deadline - now, wakeRecheckInterval)}, then stand down
     *       if the row was terminated or finalised meanwhile, and loop.</li>
     * </ol>
     *
     * <p>So a non-{@code PENDING} row with no signal always means timeout,
     * and a visible signal always wins while the outcome slot is open. The
     * stand-down check runs after each park, before the next signal read, so
     * a wake on a terminated or finalised row never consumes a signal (M7).
     *
     * <p>The per-pass signal and row reads are advisory ({@link ParkProbe}):
     * a store blip reads as "no signal, still pending" and the await keeps
     * parking. A missing row reads as {@code PENDING} too — the memoized
     * deadline then decides by the wall clock. Store errors inside the timeout
     * decision never fail the run either (R39): the self-fire is best-effort,
     * and a failed post-deadline signal re-read parks one more re-check
     * interval and decides again. Only the {@code SIGNAL_TIMEOUT} append keeps
     * the semantics of every other memo append.
     */
    private <T> T awaitOutcome(WorkflowContext ctx, int outcomeSeq, String stepName, String signalName,
                               Class<T> type, String timerId, @Nullable UUID timerDbId,
                               Instant deadline, Duration timeout) {
        var workflowId = ctx.workflowId();
        var parkKey = workflowId + ":signal:" + signalName;
        var wakeKey = workflowId + ":" + timerId;
        awaitWakeKeys.put(wakeKey, parkKey);
        try {
            updateInstanceStatus(ctx, WorkflowStatus.WAITING_SIGNAL);
            // Park boundary. Unparked fires only on the paths where the
            // workflow thread actually resumes: signal consumed or await
            // timeout. A shutdown or terminate emits neither — it emits
            // EngineObserver.runAbandoned instead (design §11, RULING 5), from
            // WorkflowExecutor's handleShutdownSuspension/handleTermination, so
            // a stateful observer still gets exactly one closing callback on
            // this thread. The status write just above can itself raise
            // WorkflowTerminatedException before this emission ever runs,
            // which is precisely why that closing callback is needed.
            observer.workflowParked(observed(ctx), ParkKind.SIGNAL);
            while (true) {
                // 1. A visible signal wins while the outcome slot is open.
                var signals = ParkProbe.read("awaitSignal.getUnconsumedSignals", workflowId,
                        () -> store.getUnconsumedSignals(workflowId, signalName),
                        List.<WorkflowSignal>of());
                if (!signals.isEmpty()) {
                    return consumeOutcome(ctx, outcomeSeq, stepName, signalName, signals.getFirst(), type,
                            timerDbId);
                }

                // 2. The durable row arbitrates the deadline. A blip reads as
                // PENDING, so the await keeps parking until the wall clock
                // passes the memoized deadline. So does a MISSING row (item
                // 4): absence is not a fire or a cancel, and the memoized
                // deadline still governs by the wall clock.
                var rowStatus = ParkProbe.read("awaitSignal.findTimer", workflowId,
                        () -> store.findTimer(ctx.workflowInstanceId(), timerId)
                                .map(WorkflowTimer::status)
                                .orElse(TimerStatus.PENDING),
                        TimerStatus.PENDING);
                var now = Instant.now();
                if (rowStatus != TimerStatus.PENDING || !now.isBefore(deadline)) {
                    if (rowStatus == TimerStatus.PENDING && timerDbId != null) {
                        selfFire(workflowId, signalName, timerDbId);
                    }
                    // R39: a store error on this re-read is a blip, not a
                    // workflow failure — it must not escape into workflow code
                    // (FAILED + compensations). Park one more re-check interval
                    // and decide again; a signal arriving meanwhile wakes it.
                    List<WorkflowSignal> late;
                    try {
                        late = store.getUnconsumedSignals(workflowId, signalName);
                    } catch (RuntimeException e) {
                        logger.warn("Workflow '{}' could not re-read signal '{}' at its deadline — parking "
                                        + "one more re-check interval ({}) before deciding the timeout: {}",
                                workflowId, signalName, wakeRecheckInterval, e.toString());
                        parkChunk(ctx, parkKey, signalName, wakeRecheckInterval);
                        continue;
                    }
                    if (!late.isEmpty()) {
                        return consumeOutcome(ctx, outcomeSeq, stepName, signalName, late.getFirst(), type,
                                timerDbId);
                    }
                    // Memoize the timeout BEFORE throwing (Issue 19): replay
                    // re-raises it instead of consuming a signal that arrived
                    // after the fact. A DuplicateEventException means another
                    // runner owns this slot — it propagates to the executor's
                    // Issue 18 stand-down, like any other stale append.
                    appendTimeout(ctx, outcomeSeq, stepName, signalName, timeout);
                    updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                    observer.workflowUnparked(observed(ctx), ParkKind.SIGNAL);
                    throw new SignalTimeoutException(workflowId, signalName, timeout);
                }

                // 3. Park in re-check-interval chunks. Every wake OR chunk
                // expiry re-reads the store, so a signal persisted without a
                // notification reaching this instance — or a row fired by a
                // remote poller leader — is seen within one interval.
                var remaining = Duration.between(now, deadline);
                var chunk = remaining.compareTo(wakeRecheckInterval) < 0 ? remaining : wakeRecheckInterval;
                parkChunk(ctx, parkKey, signalName, chunk);
            }
        } finally {
            awaitWakeKeys.remove(wakeKey, parkKey);
        }
    }

    /**
     * Parks once on the await's signal key for at most {@code chunk}, then
     * stands down if the instance was terminated or finalised meanwhile.
     */
    private void parkChunk(WorkflowContext ctx, String parkKey, String signalName, Duration chunk) {
        boolean woken;
        try {
            parkingLot.parkWithTimeout(parkKey, chunk);
            woken = true;
        } catch (TimeoutException e) {
            woken = false;
        }

        // Terminate outranks a signal: if the instance is TERMINATED,
        // consuming a signal now would memoize a SIGNAL_RECEIVED event
        // for a run that must not continue. So does a row another
        // runner already finalised (COMPLETED/FAILED): this run is
        // stale and stands down before it can take the signal (M7).
        standDownIfTerminated(ctx);

        if (woken) {
            // A signal, a timer transition — or a spurious wake, e.g. a
            // pub/sub self-echo of a previous delivery arriving after
            // this key was consumed and re-parked (collectSignals awaits
            // the same name repeatedly). The next pass decides.
            logger.debug("Workflow '{}' woke while awaiting signal '{}' — re-checking",
                    ctx.workflowId(), signalName);
        }
    }

    /**
     * Self-fires a still-{@code PENDING} deadline row once the await decides
     * it timed out, so no poller later fires it for nobody. Best-effort, like
     * the cancel in {@link #consumeOutcome}: the {@code SIGNAL_TIMEOUT} memo is
     * the truth, a row left {@code PENDING} only fires later for nobody, and a
     * store blip here must not fail the run (R39).
     */
    private void selfFire(String workflowId, String signalName, UUID timerDbId) {
        try {
            store.markTimerFired(timerDbId);
        } catch (RuntimeException e) {
            logger.warn("Could not self-fire the deadline row of workflow '{}' signal '{}' — the row "
                    + "fires later for nobody: {}", workflowId, signalName, e.toString());
        }
    }

    /**
     * Records a signal as the outcome of a parked await, best-effort cancels
     * the now-moot deadline row (the {@code SIGNAL_RECEIVED} memo is the
     * truth; a row left {@code PENDING} only fires later for nobody), and
     * closes the park.
     */
    private <T> T consumeOutcome(WorkflowContext ctx, int outcomeSeq, String stepName, String signalName,
                                 WorkflowSignal signal, Class<T> type, @Nullable UUID timerDbId) {
        var result = consumeSignal(ctx, outcomeSeq, stepName, signalName, signal, type);
        if (timerDbId != null) {
            try {
                store.markTimerCancelled(timerDbId);
            } catch (RuntimeException e) {
                logger.debug("Could not cancel the deadline row of workflow '{}' signal '{}' after "
                        + "consuming it — the row fires later for nobody: {}",
                        ctx.workflowId(), signalName, e.toString());
            }
        }
        updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
        observer.workflowUnparked(observed(ctx), ParkKind.SIGNAL);
        return result;
    }

    /** Memoizes a {@code SIGNAL_TIMEOUT} outcome at {@code seq}. */
    private void appendTimeout(WorkflowContext ctx, int seq, String stepName, String signalName,
                               Duration timeout) {
        var timeoutDetail = serializer.serialize(new SignalTimeoutDetail(signalName, timeout.toString()));
        appendEvent(ctx, seq, EventType.SIGNAL_TIMEOUT, stepName, timeoutDetail);
    }

    /**
     * Reads a parked await's {@link AwaitSignalDetail}. Unreadable means a
     * newer node shaped it: stand down, never fail (RULING 9).
     */
    private AwaitSignalDetail readAwaitDetail(String workflowId, int seq, String signalName,
                                              WorkflowEvent scheduled) {
        return UnknownHistoryGuard.requireReadablePayload(workflowId, seq,
                "await detail of signal '%s'".formatted(signalName),
                () -> {
                    var detail = serializer.deserialize(scheduled.payload(), AwaitSignalDetail.class);
                    if (detail == null || detail.timerId() == null || detail.deadline() == null) {
                        throw new SerializationException("await detail is missing its timerId or deadline",
                                new IllegalArgumentException(String.valueOf(scheduled.payload())));
                    }
                    return detail;
                });
    }

    private static Instant parseDeadline(String deadline) {
        try {
            return Instant.parse(deadline);
        } catch (DateTimeParseException e) {
            throw new SerializationException("await deadline is not an ISO-8601 instant", e);
        }
    }

    /**
     * Wakes a parked await whose deadline row just transitioned — called by
     * {@code WorkflowExecutor.fireTimer}/{@code cancelTimer} after a
     * successful compare-and-set. A no-op unless an await on this node is
     * waiting on that row; the woken await reads the row and times out.
     *
     * @param workflowId the workflow's business ID
     * @param timerId    the timer's logical ID (e.g. {@code "await-signal-7"})
     */
    void onTimerTransition(String workflowId, String timerId) {
        var parkKey = awaitWakeKeys.get(workflowId + ":" + timerId);
        if (parkKey != null) {
            parkingLot.unpark(parkKey, null);
        }
    }

    // ── Cross-instance wake subscription ────────────────────────────────

    /**
     * Registers (or ref-counts) the cross-instance wake subscription for a
     * workflow. The notifier I/O happens outside the map lock; on subscribe
     * failure the count is rolled back and the await falls back to the
     * periodic store re-check.
     *
     * <p>Only the first concurrent waiter subscribes. A later waiter joins the
     * count and waits — at most {@link #WAKE_SUBSCRIBE_JOIN_TIMEOUT} — for that
     * subscribe to settle, so its own check-then-subscribe race closure holds
     * too: it never parks while the shared subscription is still unacknowledged.
     */
    private void subscribeForWake(String workflowId) {
        if (signalNotifier == null) {
            return;
        }
        var joined = new boolean[1];
        var subscription = wakeSubscriptions.compute(workflowId, (id, existing) -> {
            if (existing == null) {
                return new WakeSubscription();
            }
            existing.count++;
            joined[0] = true;
            return existing;
        });
        if (joined[0]) {
            awaitSharedSubscribe(workflowId, subscription.settled);
            return;
        }
        try {
            signalNotifier.subscribe(workflowId, this::onRemoteSignal);
        } catch (RuntimeException e) {
            logger.warn("Failed to subscribe for cross-instance wake of workflow '{}' — "
                            + "periodic store re-check remains the fallback: {}",
                    workflowId, e.getMessage());
            removeWakeCount(workflowId);
        } finally {
            subscription.settled.complete(null);
        }
    }

    /**
     * Waits for the first waiter's subscribe to settle, bounded by
     * {@link #WAKE_SUBSCRIBE_JOIN_TIMEOUT}. Never throws: on timeout the
     * periodic store re-check is the fallback, and an interrupt is restored.
     */
    private void awaitSharedSubscribe(String workflowId, CompletableFuture<Void> settled) {
        try {
            settled.get(WAKE_SUBSCRIBE_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Shared wake subscription of workflow '{}' did not settle within {} — "
                            + "periodic store re-check remains the fallback",
                    workflowId, WAKE_SUBSCRIBE_JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Unreachable: the future is only ever completed normally.
            logger.debug("Shared wake subscription of workflow '{}' settled exceptionally: {}",
                    workflowId, e.toString());
        }
    }

    /**
     * Decrements the wake-subscription ref-count, unsubscribing (outside the
     * map lock) when the last concurrent await for this workflow finishes.
     */
    private void unsubscribeForWake(String workflowId) {
        if (signalNotifier == null) {
            return;
        }
        if (removeWakeCount(workflowId)) {
            try {
                signalNotifier.unsubscribe(workflowId);
            } catch (Exception e) {
                logger.warn("Failed to unsubscribe cross-instance wake of workflow '{}': {}",
                        workflowId, e.getMessage());
            }
        }
    }

    /**
     * Decrements the wake ref-count for a workflow.
     *
     * @return {@code true} when the count reached zero and the physical
     *         subscription should be torn down
     */
    private boolean removeWakeCount(String workflowId) {
        var reachedZero = new boolean[1];
        wakeSubscriptions.compute(workflowId, (id, subscription) -> {
            if (subscription == null) {
                return null;
            }
            if (subscription.count <= 1) {
                reachedZero[0] = true;
                return null;
            }
            subscription.count--;
            return subscription;
        });
        return reachedZero[0];
    }

    /**
     * Cross-instance notification callback: unparks the local waiter, which
     * then re-reads the signal from the store. May run on a network event
     * loop thread — must stay lightweight and non-blocking. Unparking an
     * absent key (self-echo of a local delivery, or a waiter that already
     * woke) is a no-op.
     */
    private void onRemoteSignal(String workflowId, String signalName) {
        parkingLot.unpark(workflowId + ":signal:" + signalName, null);
    }

    // ── Orphan adoption ─────────────────────────────────────────────────

    /**
     * Adopts orphaned signals by associating them with a workflow instance.
     *
     * <p>Orphaned signals are those delivered before the workflow instance
     * existed (pre-delivery pattern). They are persisted with a {@code null}
     * {@code workflowInstanceId} and need to be linked when the instance
     * is created.
     *
     * @param workflowId the business workflow ID to match signals against
     * @param instanceId the workflow instance UUID to assign
     */
    void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        store.adoptOrphanedSignals(workflowId, instanceId);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private <T> T consumeSignal(
            WorkflowContext ctx, int seq, String stepName,
            String signalName, WorkflowSignal signal, Class<T> type
    ) {
        // Persist the SIGNAL_RECEIVED event BEFORE marking consumed.
        // If appendEvent fails, the signal stays unconsumed and can be
        // retried on recovery. The reverse order would lose the signal.
        // If the CAS below then loses, the appended event is already the
        // durable memoized truth for this sequence — consuming a different
        // row now would contradict the event log on replay, so we proceed.
        appendEvent(ctx, seq, EventType.SIGNAL_RECEIVED, stepName, signal.payload());
        if (!store.markSignalConsumed(signal.id())) {
            logger.warn("Signal '{}' (id={}) for workflow '{}' was already consumed by a "
                            + "concurrent consumer — proceeding with the memoized SIGNAL_RECEIVED "
                            + "event at seq {}",
                    signalName, signal.id(), ctx.workflowId(), seq);
        }
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.SIGNAL_RECEIVED);
        // The durable hop (design §4.3(b)): the row's trace context reaches the
        // observer on the workflow thread — possibly a different thread on a
        // different node from the one that persisted it, which is exactly why
        // the column exists rather than an in-process handoff.
        observer.signalConsumed(
                new SignalInfo(ctx.workflowId(), ctx.workflowType(), signalName,
                        signal.traceContext()), false);
        logger.debug("Consumed signal '{}' for workflow '{}'", signalName, ctx.workflowId());
        // RULING 9: the signal row is persisted state this run did not write —
        // a newer node's producer may have reshaped the payload. Unreadable
        // means stand down, not fail: the SIGNAL_RECEIVED event above is
        // already durable, so an upgraded node replays it (that read is guarded
        // in awaitSignal) and carries on. Recording FAILED here would compensate
        // a workflow whose signal simply arrived in a shape this build is too
        // old to read.
        return UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), seq,
                "payload of consumed signal '%s'".formatted(signalName),
                () -> serializer.deserialize(signal.payload(), type));
    }

    /**
     * Payload of a {@link EventType#SIGNAL_TIMEOUT} event: which signal the
     * await was waiting for and the timeout that elapsed (ISO-8601).
     */
    record SignalTimeoutDetail(String signalName, String timeout) {
    }

    /**
     * Payload of the {@link EventType#TIMER_SCHEDULED} memo a parked
     * {@code awaitSignal} writes at its first slot (C12 / F4): the awaited
     * signal, the logical ID of its deadline timer row
     * ({@code await-signal-<seq>}), the requested timeout and the absolute
     * deadline (both ISO-8601). Replay resumes from {@code deadline} — never
     * from {@code Instant.now()}.
     */
    record AwaitSignalDetail(String signalName, String timerId, String timeout, String deadline) {
    }

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

    /** Builds the identity-only observation record for the current context. */
    private static WorkflowInfo observed(WorkflowContext ctx) {
        return new WorkflowInfo(ctx.workflowId(), ctx.workflowType(), ctx.serviceName());
    }

    /**
     * Stands the current run down if the workflow has been terminated while it
     * was parked.
     *
     * <p>Called once per wake-recheck chunk of {@link #awaitSignal}. A terminate
     * usually arrives on a node that is <em>not</em> the owner, so nothing local
     * unparks this thread; the periodic re-read the await already performs for
     * missed signal notifications is the cheapest place to also notice a
     * terminal row, and it bounds cross-node terminate convergence for a parked
     * await to one interval.
     *
     * <p>The same read also catches the stale-run case (M7): a row another
     * runner finalised as {@code COMPLETED} or {@code FAILED} while this run
     * was parked. Without it the recheck that follows would consume the signal
     * — marking it consumed and memoizing {@code SIGNAL_RECEIVED} — before the
     * {@code RUNNING} status write's fresh read stood the run down, taking a
     * signal from a workflow that no longer runs.
     *
     * <p>The read itself is advisory (Issue 20 / Ruling 5, via {@link ParkProbe}):
     * if the store is unreachable this interval, that means "can't prove
     * finalised" — fail open and keep parking rather than propagate the
     * store's exception. The instance is only ever actually stood down once a
     * probe succeeds and observes a terminal status.
     *
     * @param ctx the parked run's context; set to {@code STALE} before a
     *            {@link StaleRunException} is thrown, so the stand-down is sticky
     * @throws WorkflowTerminatedException if the instance row is {@code TERMINATED}
     * @throws StaleRunException           if the instance row is any other
     *                                     terminal status — another runner
     *                                     finalised it
     */
    private void standDownIfTerminated(WorkflowContext ctx) {
        var workflowId = ctx.workflowId();
        // The same single read as before: it now returns the status rather
        // than a TERMINATED boolean, so no extra store read is added (R30).
        @Nullable WorkflowStatus status = ParkProbe.<@Nullable WorkflowStatus>read(
                "awaitSignal.standDownIfTerminated", workflowId,
                () -> store.getInstance(workflowId)
                        .map(WorkflowInstance::status)
                        .orElse(null),
                null);
        if (status == WorkflowStatus.TERMINATED) {
            logger.info("Workflow '{}' was terminated while parked on a signal — abandoning this run",
                    workflowId);
            throw new WorkflowTerminatedException(workflowId, null);
        }
        if (status != null && status.isTerminal()) {
            logger.warn("Workflow '{}' is already {} — another runner finalised it while this run was "
                    + "parked on a signal; standing this stale run down without consuming it",
                    workflowId, status);
            // Sticky (R33): swallowing the exception cannot let the run carry on.
            ctx.cancellation().requestStale("signal wake while parked");
            throw new StaleRunException(workflowId, status, "signal wake while parked");
        }
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
}
