package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.core.engine.InstanceStatusWriter;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.ReplayGuard;
import io.b2mash.maestro.core.engine.SequenceSpace;
import io.b2mash.maestro.core.engine.UnknownHistoryGuard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates saga compensation when a workflow fails.
 *
 * <p>When a workflow's execution throws an exception and compensations
 * have been registered on the {@link CompensationStack}, the SagaManager:
 * <ol>
 *   <li>Transitions the workflow to {@link WorkflowStatus#COMPENSATING}.</li>
 *   <li>Appends a {@link EventType#COMPENSATION_STARTED} event.</li>
 *   <li>Executes compensations in LIFO order (or parallel if configured).</li>
 *   <li>Records per-step events for each compensation.</li>
 *   <li>Appends a {@link EventType#COMPENSATION_COMPLETED} event.</li>
 * </ol>
 *
 * <p>The caller ({@code WorkflowExecutor.handleWorkflowFailure}) is
 * responsible for the final transition to {@link WorkflowStatus#FAILED}.
 *
 * <h2>Memoization</h2>
 * <p>A {@code @Compensate}-annotated activity's compensation action calls
 * through the activity proxy, which assigns sequence numbers and persists
 * results — those replay for free. A manually-registered compensation
 * (via {@code workflow.addCompensation(Runnable)}) is <b>not</b> memoized
 * that way, so {@code compensate()} gives every compensation entry — both
 * kinds, in both the sequential and parallel loops — its own reserved
 * sequence block (the same {@link SequenceSpace} partitioning already
 * used for {@code parallel()} branches). Before invoking an entry's action,
 * the block's guard sequence is checked against the store; if an event
 * already exists there, the action is <b>not</b> re-invoked — its outcome
 * (completed or failed) is already durable and is replayed instead. The
 * same check gates {@code COMPENSATION_STARTED}/{@code COMPENSATION_COMPLETED}
 * so a recovery run doesn't just rely on the store's unique-index rejection
 * (a {@link io.b2mash.maestro.core.exception.DuplicateEventException},
 * silently swallowed) to keep those idempotent.
 *
 * <h2>Thread Safety</h2>
 * <p>Each instance is used by a single workflow's virtual thread (plus
 * spawned parallel compensation threads if parallel mode is enabled). A
 * successful parallel branch persists its own
 * {@link EventType#COMPENSATION_STEP_COMPLETED} event from inside its own
 * thread, immediately on completion — not deferred to after all branches
 * join — so that a sibling branch later interrupted by shutdown cannot
 * cause this branch's already-genuine work to go unrecorded and be
 * re-invoked on recovery. Concurrent appends from sibling branches target
 * distinct sequence numbers, so they do not race each other.
 */
public final class SagaManager {

    private static final Logger logger = LoggerFactory.getLogger(SagaManager.class);

    /** Step name of the parallel-compensation fork marker. */
    private static final String PARALLEL_COMPENSATION = "$maestro:parallel-compensation";

    /**
     * Names the sequential compensation layout in a drift detail. Never written
     * as a step name — a sequential compensation records no fork marker.
     */
    private static final String SEQUENTIAL_COMPENSATION = "$maestro:sequential-compensation";

    private final WorkflowStore store;
    private final @Nullable WorkflowMessaging messaging;
    private final PayloadSerializer serializer;
    private final String serviceName;
    private final EngineObserver observer;

    /**
     * Creates a new SagaManager with no observation.
     *
     * @param store       workflow store for persistence
     * @param messaging   optional messaging for lifecycle events
     * @param serializer  Jackson serializer for event payloads
     * @param serviceName the owning service name
     */
    public SagaManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            String serviceName
    ) {
        this(store, messaging, serializer, serviceName, EngineObserver.NOOP);
    }

    /**
     * Creates a new SagaManager with an {@link EngineObserver}.
     *
     * @param store       workflow store for persistence
     * @param messaging   optional messaging for lifecycle events
     * @param serializer  Jackson serializer for event payloads
     * @param serviceName the owning service name
     * @param observer    engine observation seam — fires
     *                    {@code workflowCompensating} when a live (non-replay)
     *                    compensation phase starts; never {@code null} (pass
     *                    {@link EngineObserver#NOOP})
     */
    public SagaManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            String serviceName,
            EngineObserver observer
    ) {
        this.store = store;
        this.messaging = messaging;
        this.serializer = serializer;
        this.serviceName = serviceName;
        this.observer = observer;
    }

    /**
     * Runs the compensation phase for a failed workflow.
     *
     * <p>Compensations are drained from the stack and executed. If
     * {@code parallelCompensation} is {@code true}, all compensations
     * run concurrently on virtual threads; otherwise, they execute
     * sequentially in LIFO order.
     *
     * <p>If a compensation fails, it is logged and recorded as a
     * {@link EventType#COMPENSATION_STEP_FAILED} event, but remaining
     * compensations continue.
     *
     * <p>Before anything durable changes — before the {@code COMPENSATING}
     * transition and before {@code COMPENSATION_STARTED} — the per-entry
     * block arithmetic is pre-checked with {@link SequenceSpace#nextParentSeq}.
     * If the blocks cannot fit in the 32-bit sequence space (typically a
     * workflow that already forked twice), compensation cannot be sequenced at
     * all, and running it anyway would place actions at int-wrapped sequence
     * numbers. The stack is already drained at that point, so this method logs
     * the drained entries' step names at {@code ERROR} (the operator's manual
     * compensation list) before rethrowing; the caller
     * ({@code WorkflowExecutor.handleWorkflowFailure}) logs at {@code ERROR}
     * that no compensation ran and still finishes the workflow {@code FAILED}.
     *
     * @param ctx                   the workflow context
     * @param instance              the workflow instance
     * @param stack                 the compensation stack to unwind
     * @param parallelCompensation  whether to run compensations in parallel
     * @throws io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException
     *         if the compensation blocks would overflow the sequence space;
     *         thrown before any status write, event append or action
     */
    public void compensate(
            WorkflowContext ctx,
            WorkflowInstance instance,
            CompensationStack stack,
            boolean parallelCompensation
    ) {
        var entries = stack.unwind();
        if (entries.isEmpty()) {
            return;
        }

        // The anchor is the seq COMPENSATION_STARTED will consume; parallel
        // compensation forks one seq later.
        var anchor = ctx.currentSequence() + 1;

        // Anchor replay read (R19), deliberately FIRST — before the pre-check
        // and before the COMPENSATING transition. A foreign or unknown event
        // here stands the run down, and a stand-down writes nothing: reading
        // it after the transition would durably flip a RUNNING instance to
        // COMPENSATING (a status the machine only leaves for FAILED) before
        // standing down. The live emit/append below stay where they are.
        var startedRecorded = isRecordedMarker(ctx, anchor, EventType.COMPENSATION_STARTED);

        // Sequence-space pre-check (C4), deliberately BEFORE the COMPENSATING
        // transition: if the per-entry block arithmetic cannot fit in the int
        // sequence space, nothing durable may change — no status write, no
        // COMPENSATION_STARTED, no action run at a wrapped (negative) sequence.
        // WorkflowExecutor.handleWorkflowFailure turns the throw into a loud
        // FAILED with an ERROR log that no compensation ran.
        try {
            SequenceSpace.nextParentSeq(ctx.workflowId(), parallelCompensation ? anchor + 1 : anchor, entries.size());
        } catch (SequenceSpaceExhaustedException noRoom) {
            // The stack is already drained, so this is the last place that knows
            // what an operator must now undo by hand.
            logger.error("Workflow '{}': none of its {} compensation(s) can run (sequence space exhausted) — "
                            + "compensate manually, in this order: {}",
                    ctx.workflowId(), entries.size(),
                    entries.stream().map(CompensationEntry::stepName).toList());
            throw noRoom;
        }

        logger.info("Running {} compensation(s) for workflow '{}' (parallel={})",
                entries.size(), ctx.workflowId(), parallelCompensation);

        // Transition to COMPENSATING
        transitionToCompensating(ctx, instance);

        // Record COMPENSATION_STARTED — replay-skip guarded like every other
        // event-emitting path, rather than relying on the store's unique
        // index to silently reject the duplicate on a recovery re-run. The
        // seq this call consumes doubles as the anchor for the sequential
        // loop's per-entry sequence blocks (see executeSequential). Its slot
        // was already read and guarded above, before anything durable changed.
        var startedSeq = ctx.nextSequence();
        if (startedRecorded) {
            logger.debug("Replaying COMPENSATION_STARTED at seq {} for workflow '{}' — already recorded",
                    startedSeq, ctx.workflowId());
        } else {
            // Live only — guarded by the COMPENSATION_STARTED replay-skip
            // above, so a recovery re-run does not double-count the phase.
            // Emitted BEFORE the append: unlike the executor's appendEvent,
            // this one rethrows DuplicateEventException so the run stands
            // down, and emitting after it would report a compensation phase
            // that started as a STALE_RUN stand-down instead. Contained, so a
            // throwing observer cannot abort a compensation phase that must
            // run (it would escape compensate(), skip the FAILED transition,
            // and leave the instance stuck in COMPENSATING).
            emit("workflowCompensating", ctx.workflowId(), () -> observer.workflowCompensating(
                    new WorkflowInfo(ctx.workflowId(), ctx.workflowType(), serviceName)));

            appendEvent(ctx, startedSeq, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
            publishLifecycleEvent(ctx, "$maestro:compensation", LifecycleEventType.COMPENSATION_STARTED);
        }

        // Execute compensations
        List<String> failedCompensations;
        if (parallelCompensation) {
            failedCompensations = executeParallel(ctx, entries, startedSeq);
        } else {
            failedCompensations = executeSequential(ctx, entries, startedSeq);
        }

        // Record COMPENSATION_COMPLETED — same replay-skip guard.
        var completedSeq = ctx.nextSequence();
        if (isRecordedMarker(ctx, completedSeq, EventType.COMPENSATION_COMPLETED)) {
            logger.debug("Replaying COMPENSATION_COMPLETED at seq {} for workflow '{}' — already recorded",
                    completedSeq, ctx.workflowId());
        } else {
            var completionPayload = failedCompensations.isEmpty()
                    ? null
                    : serializer.serialize(new CompensationSummary(entries.size(), failedCompensations));
            appendEvent(ctx, completedSeq, EventType.COMPENSATION_COMPLETED, "$maestro:compensation", completionPayload);
            publishLifecycleEvent(ctx, "$maestro:compensation", LifecycleEventType.COMPENSATION_COMPLETED);
        }

        if (failedCompensations.isEmpty()) {
            logger.info("All {} compensation(s) completed for workflow '{}'",
                    entries.size(), ctx.workflowId());
        } else {
            logger.warn("{} of {} compensation(s) failed for workflow '{}': {}",
                    failedCompensations.size(), entries.size(),
                    ctx.workflowId(), failedCompensations);
            throw new CompensationException(ctx.workflowId(), failedCompensations);
        }
    }

    /**
     * The replay-skip read for a compensation phase marker
     * ({@code COMPENSATION_STARTED} / {@code COMPENSATION_COMPLETED}).
     *
     * <p>Returns {@code true} when the slot already holds <em>this</em> marker
     * (the phase boundary is durable — replay it) and {@code false} when the
     * slot is empty (record it live). Any other event there stands the run down
     * before anything is invoked or appended: an unknown type as
     * {@code UNKNOWN_EVENT_TYPE}, a known event of another operation as
     * {@code NONDETERMINISTIC_HISTORY} (R19). The code now reaches compensation
     * at a different point than the recorded run did, so treating the foreign
     * event as "already recorded" would run (or finalise) the compensation
     * phase on another operation's slot.
     */
    private boolean isRecordedMarker(WorkflowContext ctx, int seq, EventType marker) {
        var stored = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), seq), ctx.workflowId());
        stored.ifPresent(event -> ReplayGuard.requireMatch(event, ctx.workflowId(),
                "$maestro:compensation", marker));
        return stored.isPresent();
    }

    // ── Sequential execution ──────────────────────────────────────────

    /**
     * Runs compensations one at a time, in LIFO order.
     *
     * <p>Each entry gets its own reserved sequence block —
     * {@code SequenceSpace.branchBase(anchorSeq, i)} — the same isolation
     * scheme {@code executeParallel} uses for branches. The block's base
     * (the guard sequence) is checked against the store before the entry's
     * action runs; if an event already exists there, the entry already ran
     * to completion (or failure) on a prior attempt and is <b>not</b>
     * re-invoked. Reserving a whole block, rather than a single sequence
     * number, means a skipped entry doesn't need to know how many sequence
     * numbers its action would have consumed internally (e.g. a
     * {@code @Compensate} activity call nested inside a manually-registered
     * compensation) — the next entry's block base is always deterministic.
     *
     * <p>The action runs under {@link WorkflowContext#bounded(WorkflowContext, int, int, int)}
     * — a child context limited to the entry's block and sharing {@code ctx}'s
     * operations — so an entry may take at most 999 sequence slots; the 1000th
     * throws {@link io.b2mash.maestro.core.exception.BranchSequenceExhaustedException},
     * which fails that entry ({@code COMPENSATION_STEP_FAILED}) instead of
     * spilling into the next entry's guard slot and silently skipping it. The
     * per-entry outcome events are written through {@code ctx} at the block base.
     *
     * @param ctx       the workflow context
     * @param entries   the compensation entries, in LIFO execution order
     * @param anchorSeq the {@code COMPENSATION_STARTED} event's own sequence
     *                  number, reused (not re-consumed) as the block-math anchor
     * @return the step names of entries whose action failed (live or replayed)
     */
    private List<String> executeSequential(WorkflowContext ctx, List<CompensationEntry> entries, int anchorSeq) {
        var failures = new ArrayList<String>();

        // Mode-flip guard (C10): a sequential compensation never writes
        // anchorSeq + 1 — its entries live in their blocks — but a parallel one
        // records its fork marker exactly there. An event at that slot means
        // @Saga(parallelCompensation) was flipped since the history was written;
        // without this, entry 0's (empty) block would run an entry the parallel
        // run may already have reversed.
        ReplayGuard.requireAbsent(UnknownHistoryGuard.requireKnown(
                        store.getEventBySequence(ctx.workflowInstanceId(), anchorSeq + 1), ctx.workflowId()),
                ctx.workflowId(), SEQUENTIAL_COMPENSATION);

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var stepBaseSeq = SequenceSpace.branchBase(anchorSeq, i);

            // Replay-skip guard: don't re-invoke an action whose outcome is
            // already durable. A manually-registered compensation isn't
            // memoized the way an activity call is, so without this check
            // it would run again on every recovery replay.
            var storedEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), stepBaseSeq),
                    ctx.workflowId());
            if (storedEvent.isPresent()) {
                // Drift (C10): only THIS entry's outcome means "already done". A
                // renamed or reordered entry used to be skipped on another
                // entry's record — and so never compensated at all.
                ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), entry.stepName(),
                        EventType.COMPENSATION_STEP_COMPLETED, EventType.COMPENSATION_STEP_FAILED);
                var eventType = storedEvent.get().eventType();
                logger.debug("Replaying compensation {} '{}' at seq {} ({}) for workflow '{}' — not re-invoking",
                        i, entry.stepName(), stepBaseSeq, eventType, ctx.workflowId());
                if (eventType == EventType.COMPENSATION_STEP_FAILED) {
                    failures.add(entry.stepName());
                }
                continue;
            }

            // Live path: run the action under a context bounded to this
            // entry's reserved block, so any sequence numbers it consumes
            // internally (e.g. a nested @Compensate activity call) are
            // numbered from stepBaseSeq + 1 and can never reach the next
            // entry's guard sequence: the 1000th throws
            // BranchSequenceExhaustedException, recorded below as this
            // entry's COMPENSATION_STEP_FAILED — exactly how executeParallel's
            // branches behave. Unbounded, a 1000th call landed on entry i+1's
            // guard slot and entry i+1 was silently skipped as "already done".
            // The child shares ctx's operations (the action runs on the
            // workflow thread, so sleep/awaitSignal/parallel stay available);
            // the outcome events below are still written through ctx.
            ctx.setReplaying(false);
            var entryCtx = WorkflowContext.bounded(ctx, i, stepBaseSeq,
                    stepBaseSeq + SequenceSpace.BRANCH_MULTIPLIER);

            try {
                // catch (Exception e) deliberately does not catch the engine's
                // control-flow signals — every MaestroControlFlowError extends
                // Error, not Exception, so no rethrow arm is needed here for
                // them. A compensation action that parks (sleep()/awaitSignal())
                // and is abandoned by a shutdown or a terminate, or that reads
                // history this build cannot interpret and stands down,
                // propagates out of this loop uncaught, so no step is recorded
                // failed. For a shutdown the remaining (not-yet-run)
                // compensations are left for a recovering node, which replays
                // this one's memoized side effects and continues; for a
                // terminate they are simply never run, which is terminate's
                // contract; for a stand-down an upgraded node finishes the job.
                // Any entry already recorded COMPLETED earlier in this same
                // call (i.e. before the entry that threw) stays durable —
                // its append already happened, in an earlier loop iteration.
                ScopedValue.where(WorkflowContext.scopedValue(), entryCtx).run(entry.action());
                logger.debug("Compensation {} '{}' completed for workflow '{}'",
                        i, entry.stepName(), ctx.workflowId());
                appendEvent(ctx, stepBaseSeq, EventType.COMPENSATION_STEP_COMPLETED, entry.stepName(), null);
                publishLifecycleEvent(ctx, entry.stepName(),
                        LifecycleEventType.COMPENSATION_STEP_COMPLETED);
            } catch (DuplicateEventException | StaleRunException staleRun) {
                // Issue 18: an append collision here means a concurrent runner
                // owns this workflow's compensation progress (the replay-skip
                // guard above read the store before the winner's event landed).
                // That is not a step failure — recording COMPENSATION_STEP_FAILED
                // would durably contradict the winner's COMPLETED entry at the
                // same sequence. Rethrow so the whole attempt stands down
                // (WorkflowExecutor leaves the instance COMPENSATING and
                // recoverable; the winner's run finishes the job) — the exact
                // treatment ExecutorShutdownException gets here, and ahead of
                // catch (Exception e) because both are MaestroExceptions. The
                // collision may come from the action itself: since C5 a
                // compensation activity that loses its append race propagates
                // the duplicate instead of adopting the winner's result. A
                // StaleRunException (a status write inside the action finding
                // the row finalised by another runner) is the same signal.
                throw staleRun;
            } catch (Exception e) {
                logger.error("Compensation {} '{}' failed for workflow '{}': {}",
                        i, entry.stepName(), ctx.workflowId(), e.getMessage(), e);
                recordStepFailure(ctx, stepBaseSeq, entry.stepName(), e);
                failures.add(entry.stepName());
            }
        }

        // Advance past every entry's reserved block so COMPENSATION_COMPLETED
        // gets a deterministic sequence, independent of how many internal
        // sequence numbers each entry's action actually consumed.
        ctx.setSequence(SequenceSpace.nextParentSeq(ctx.workflowId(), anchorSeq, entries.size()));

        return failures;
    }

    // ── Parallel execution ────────────────────────────────────────────

    /**
     * Runs all compensations concurrently, one virtual thread per branch.
     *
     * <p>Each branch's guard sequence — {@code branchBaseSeq}, the value its
     * {@code branchCtx} is seeded with — is checked against the store
     * <em>before</em> the branch thread is even spawned. A branch whose
     * outcome is already durable (completed or failed on a prior attempt) is
     * not re-invoked; its recorded outcome is reused instead. This mirrors
     * {@code executeSequential}'s per-entry guard, applied per-branch.
     *
     * <p>The fork point itself is replay-guarded like {@code COMPENSATION_STARTED},
     * because {@code appendEvent} rethrows {@code DuplicateEventException} and an
     * unguarded re-append on recovery stood the run down forever (C2).
     *
     * <p>Every replay read — the fork marker, the sequential layout's entry-0
     * slot, and every branch's guard slot — is drift-checked (C10) <em>before</em>
     * the marker is appended or any branch is spawned, so a stand-down leaves
     * nothing behind and nothing run.
     *
     * @param ctx       the workflow context
     * @param entries   the compensation entries, one per branch
     * @param anchorSeq the {@code COMPENSATION_STARTED} event's sequence number,
     *                  used only to locate the sequential layout's entry-0 slot
     * @return the step names of entries whose action failed (live or replayed)
     */
    private List<String> executeParallel(WorkflowContext ctx, List<CompensationEntry> entries, int anchorSeq) {
        var failures = new ArrayList<String>();
        var errors = new ArrayList<AtomicReference<Throwable>>(entries.size());
        // Replay-skip guard state, indexed like entries/errors: true for a
        // branch whose outcome was already durable and was therefore never
        // spawned this call — its outcome must not be recorded again.
        var replaySkipped = new boolean[entries.size()];

        var latch = new CountDownLatch(entries.size());

        for (int i = 0; i < entries.size(); i++) {
            errors.add(new AtomicReference<>());
        }

        // The fork point gives each branch a deterministic sequence space
        var parentSeq = ctx.nextSequence();
        var marker = UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), parentSeq), ctx.workflowId());
        if (marker.isPresent()) {
            // Drift (C10): the marker must be this fork's, over the same number
            // of entries. ParallelCompensationDetail's field is named
            // branchCount, so the parallel() arity check applies unchanged.
            ReplayGuard.requireMatch(marker.get(), ctx.workflowId(), PARALLEL_COMPENSATION,
                    EventType.SIDE_EFFECT);
            ReplayGuard.requireBranchCount(marker.get(), ctx.workflowId(), entries.size());
        }
        // Mode-flip guard (C10): a parallel compensation never writes the
        // sequential layout's entry-0 slot (its branch blocks start one block
        // later), so an event there means @Saga(parallelCompensation) was
        // flipped since a sequential run reversed that entry.
        ReplayGuard.requireAbsent(UnknownHistoryGuard.requireKnown(
                        store.getEventBySequence(ctx.workflowInstanceId(), SequenceSpace.branchBase(anchorSeq, 0)),
                        ctx.workflowId()),
                ctx.workflowId(), PARALLEL_COMPENSATION);

        // Replay-skip guard reads for EVERY branch, before the marker append and
        // before any branch thread is spawned: a manually-registered
        // compensation isn't memoized the way an activity call is, so without
        // this check a branch that already completed (or failed) durably
        // before some OTHER branch was interrupted by shutdown would be
        // re-invoked on recovery. Reading all of them first means a drifted
        // slot at branch i stands the run down before branch 0 has run.
        var recorded = new ArrayList<Optional<WorkflowEvent>>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            var storedEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), SequenceSpace.branchBase(parentSeq, i)),
                    ctx.workflowId());
            if (storedEvent.isPresent()) {
                // Only THIS entry's outcome means "already done" (C10).
                ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), entries.get(i).stepName(),
                        EventType.COMPENSATION_STEP_COMPLETED, EventType.COMPENSATION_STEP_FAILED);
            }
            recorded.add(storedEvent);
        }

        if (marker.isEmpty()) {
            appendEvent(ctx, parentSeq, EventType.SIDE_EFFECT, PARALLEL_COMPENSATION,
                    serializer.serialize(new ParallelCompensationDetail(entries.size())));
        } else {
            logger.debug("Replaying parallel-compensation fork at seq {} for workflow '{}' — already recorded",
                    parentSeq, ctx.workflowId());
        }

        for (int i = 0; i < entries.size(); i++) {
            var index = i;
            var entry = entries.get(i);

            // Each branch gets its own isolated sequence space
            var branchBaseSeq = SequenceSpace.branchBase(parentSeq, index);

            var storedEvent = recorded.get(index);
            if (storedEvent.isPresent()) {
                var eventType = storedEvent.get().eventType();
                logger.debug("Replaying compensation branch {} '{}' at seq {} ({}) for workflow '{}' "
                                + "— not re-invoking",
                        index, entry.stepName(), branchBaseSeq, eventType, ctx.workflowId());
                replaySkipped[index] = true;
                if (eventType == EventType.COMPENSATION_STEP_FAILED) {
                    failures.add(entry.stepName());
                }
                latch.countDown();
                continue;
            }

            Thread.ofVirtual()
                    .name("maestro-compensation-%s-%s-%d".formatted(
                            ctx.workflowType(), ctx.workflowId(), index))
                    .start(() -> {
                        // Create a branch context with its own sequence counter
                        // for deterministic replay
                        var branchCtx = WorkflowContext.bounded(ctx, index, branchBaseSeq,
                                branchBaseSeq + SequenceSpace.BRANCH_MULTIPLIER,
                                null); // no operations needed — compensations call through proxy directly
                        WorkflowMDC.populate(branchCtx);
                        try {
                            ScopedValue.where(WorkflowContext.scopedValue(), branchCtx)
                                    .run(() -> {
                                        try {
                                            entry.action().run();
                                            logger.debug("Compensation {} '{}' completed for workflow '{}'",
                                                    index, entry.stepName(), ctx.workflowId());
                                            // Persisted immediately, from inside this branch's own
                                            // thread — not deferred until after all branches join —
                                            // so a sibling branch later interrupted by shutdown
                                            // cannot cause this branch's already-genuine work to go
                                            // unrecorded and be re-invoked on recovery. Concurrent
                                            // appends from sibling branches target distinct sequence
                                            // numbers, so they do not race each other.
                                            appendEvent(branchCtx, branchBaseSeq,
                                                    EventType.COMPENSATION_STEP_COMPLETED, entry.stepName(), null);
                                            publishLifecycleEvent(ctx, entry.stepName(),
                                                    LifecycleEventType.COMPENSATION_STEP_COMPLETED);
                                        } catch (Throwable t) {
                                            // Caught broadly (including the engine's control-flow
                                            // signals, which are Errors) so the branch thread always
                                            // counts down the latch instead of dying uncaught; the
                                            // check below tells a shutdown or a terminate apart from
                                            // a real compensation failure before anything is recorded.
                                            errors.get(index).set(t);
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
            logger.warn("Parallel compensation interrupted for workflow '{}'", ctx.workflowId());
        }

        // Advance parent sequence past all branch spaces
        var nextParentSeq = SequenceSpace.nextParentSeq(ctx.workflowId(), parentSeq, entries.size());
        ctx.setSequence(nextParentSeq);

        // A shutdown in any branch takes priority over recording outcomes: the
        // whole compensation attempt is being abandoned for a later node to
        // finish, so nothing here — including other branches that may have
        // failed for unrelated reasons in the same instant — is recorded as a
        // compensation failure. Rethrowing propagates to WorkflowExecutor,
        // which leaves the instance COMPENSATING and recoverable. (Branches
        // this call replay-skipped never ran, so they cannot be the source
        // of a shutdown here.)
        for (var errorRef : errors) {
            // One check covers all three engine control-flow signals — a
            // shutdown, an operator terminate, or a stand-down because a branch
            // read history this build cannot interpret. None of them is a
            // branch FAILURE, and recording one as COMPENSATION_STEP_FAILED
            // would durably claim a reversal was attempted and failed when
            // nothing ran at all. Enumerating the types individually here is
            // what let the third one slip through when it was added, which is
            // why MaestroControlFlowError exists.
            if (errorRef.get() instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            // Issue 18: a branch whose append collided with a concurrent
            // runner's event means the whole attempt is stale — a peer owns
            // this workflow's compensation progress. Not a step failure;
            // recording it would contradict the winner's durable entry.
            // Rethrow so WorkflowExecutor stands the run down (instance stays
            // COMPENSATING, recoverable; the winner finishes the job). A
            // StaleRunException — a branch's status write finding the row
            // finalised by another runner — is the same signal.
            if (errorRef.get() instanceof DuplicateEventException staleRun) {
                throw staleRun;
            }
            if (errorRef.get() instanceof StaleRunException staleRun) {
                throw staleRun;
            }
        }

        // Collect failures from branches that genuinely ran this call.
        // Replay-skipped branches were already accounted for above (their
        // outcome is durable from a prior attempt — appending again would
        // duplicate the event this guard exists to prevent).
        for (int i = 0; i < entries.size(); i++) {
            if (replaySkipped[i]) {
                continue;
            }
            var error = errors.get(i).get();
            if (error != null) {
                var entry = entries.get(i);
                var branchBaseSeq = SequenceSpace.branchBase(parentSeq, i);
                logger.error("Compensation {} '{}' failed for workflow '{}': {}",
                        i, entry.stepName(), ctx.workflowId(), error.getMessage(), error);
                recordStepFailure(ctx, branchBaseSeq, entry.stepName(),
                        error instanceof Exception ex ? ex : new RuntimeException(error));
                failures.add(entry.stepName());
            }
        }

        return failures;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Writes {@code COMPENSATING}, standing down against a freshly-read
     * terminal status instead of overwriting it — the same guard
     * {@link io.b2mash.maestro.core.engine.InstanceStatusWriter} applies to a
     * workflow's own {@code WAITING_*}/{@code RUNNING} writes, and the same
     * bounded retry it uses to resolve a lost compare-and-set
     * ({@link InstanceStatusWriter#STATUS_WRITE_ATTEMPTS} attempts, immediate,
     * no backoff — shared with {@code WorkflowExecutor.transitionToTerminal},
     * which bounds the same conflict on the same row).
     *
     * <p>{@code WorkflowExecutor.terminateWorkflow} can write {@code TERMINATED}
     * from any node, including one racing this failing run's compensation
     * phase. The read and the compare-and-set below are not atomic, so a
     * terminate can land in the gap between them: the guard's read still sees a
     * non-terminal status, but the write that follows loses its optimistic-lock
     * check against the now-{@code TERMINATED} row. Formerly (Issue 22) that
     * lost compare-and-set was swallowed and the method returned normally,
     * letting {@link #compensate} carry on and run compensations against a
     * workflow an operator had already stopped. It is now retried against a
     * fresh read instead, and the terminal guard is re-evaluated on
     * <b>every</b> attempt — not just the first — so a {@code TERMINATED} that
     * appears between attempts is caught before compensation ever starts:
     * <ul>
     *   <li>A freshly-read {@code TERMINATED}, on any attempt, throws
     *       {@link WorkflowTerminatedException} instead of writing anything.
     *       It propagates out of {@link #compensate} uncaught — an
     *       {@code Error}, not caught by this method's own retry loop — for
     *       {@code WorkflowExecutor.executeWorkflow}'s
     *       {@code catch (WorkflowTerminatedException)} to unwind without
     *       recording a failure.</li>
     *   <li>A freshly-read {@code COMPLETED}/{@code FAILED} means another
     *       runner already finalised the instance; this run has nothing to add
     *       and throws {@link StaleRunException} rather than returning
     *       normally — a stale run must never proceed to compensate a workflow
     *       a peer already finished (that peer's own failure path ran, or
     *       skipped, compensations per its own outcome). It propagates out of
     *       {@link #compensate} to {@code WorkflowExecutor}'s stale-run
     *       stand-down ({@code STALE_RUN}), which writes nothing. (It used to
     *       be an {@code InvalidStateTransitionException}, which no executor
     *       arm caught: thrown from the failure path, it escaped
     *       {@code executeWorkflow} and killed the workflow thread uncaught —
     *       C5.)</li>
     *   <li>A lost compare-and-set ({@link OptimisticLockException}) is
     *       retried against a fresh read, up to the shared budget.</li>
     * </ul>
     *
     * <p><b>Every exit is either a confirmed write or a throw — never a
     * silent return.</b> {@link #compensate} calls this method and, on
     * normal return, proceeds straight to running compensations; it does not
     * (and must not need to) inspect any outcome value. That is safe only
     * because every branch that must not let compensation start throws
     * instead of returning: this method returns normally if and only if
     * {@code store.updateInstance} actually persisted the {@code COMPENSATING}
     * row (including the recovery re-entry case, where the row already reads
     * {@code COMPENSATING} from a prior crash and the write is a genuine,
     * confirmed no-op transition to the same status with a bumped version).
     *
     * <p><b>Exhaustion policy (deliberately different from
     * {@code InstanceStatusWriter}).</b> {@code InstanceStatusWriter} stands
     * down on exhaustion and lets the run continue, because every status it
     * writes is non-terminal and advisory — a lost write there costs nothing.
     * This write gates <em>entry</em> into the compensation phase: proceeding
     * to compensate against a row this run could not read-confirm is exactly
     * the defect being fixed, since the unconfirmed writer might be a
     * terminate. So on exhaustion this logs an error and rethrows the last
     * {@link OptimisticLockException} — thrown directly from inside the retry
     * loop's last iteration, not stashed in a nullable local for a
     * post-loop throw — instead of falling through to compensate. Nothing
     * terminal is written; the instance keeps its prior active status, so
     * recovery re-runs this workflow, replays up to this point, and
     * re-attempts the transition with a fresh read.
     *
     * <p>A non-{@code OptimisticLockException} store failure (a store outage,
     * not a lost CAS) is treated the same way: logged at {@code ERROR} and
     * rethrown, so the method never returns without a confirmed write and
     * {@link #compensate} can never proceed against an unconfirmed status.
     */
    private void transitionToCompensating(WorkflowContext ctx, WorkflowInstance instance) {
        for (var attempt = 1; attempt <= InstanceStatusWriter.STATUS_WRITE_ATTEMPTS; attempt++) {
            var latest = store.getInstance(ctx.workflowId()).orElse(instance);
            if (latest.status() == WorkflowStatus.TERMINATED) {
                logger.info("Workflow '{}' is TERMINATED — not starting compensation; abandoning this run",
                        ctx.workflowId());
                throw new WorkflowTerminatedException(ctx.workflowId(), null);
            }
            if (latest.status().isTerminal()) {
                logger.warn("Workflow '{}' is already {} — another runner finalised it first; "
                                + "abandoning this stale run without compensating",
                        ctx.workflowId(), latest.status());
                throw new StaleRunException(ctx.workflowId(), latest.status(), "COMPENSATING write");
            }
            try {
                var compensating = latest.toBuilder()
                        .status(WorkflowStatus.COMPENSATING)
                        .updatedAt(Instant.now())
                        .version(latest.version() + 1)
                        .build();
                store.updateInstance(compensating);
                return;
            } catch (OptimisticLockException e) {
                if (attempt == InstanceStatusWriter.STATUS_WRITE_ATTEMPTS) {
                    // Exhaustion: this write gates ENTRY into the compensation
                    // phase, so unlike InstanceStatusWriter we must not proceed
                    // against a row we could not read-confirm — the row is
                    // being written by someone whose intent we keep missing,
                    // and that someone may be a terminate. Abandon the local
                    // run: nothing terminal was written, the instance stays
                    // active, and recovery re-attempts compensation from a
                    // fresh read.
                    logger.error("Could not transition workflow '{}' to COMPENSATING after {} attempts — "
                                    + "abandoning this run without compensating; recovery will retry",
                            ctx.workflowId(), InstanceStatusWriter.STATUS_WRITE_ATTEMPTS);
                    throw e;
                }
                logger.debug("Lost COMPENSATING CAS for workflow '{}' (attempt {}/{}) — re-reading",
                        ctx.workflowId(), attempt, InstanceStatusWriter.STATUS_WRITE_ATTEMPTS);
            } catch (Exception e) {
                logger.error("Failed to update workflow '{}' status to COMPENSATING — "
                                + "abandoning this run without compensating against an unconfirmed status",
                        ctx.workflowId(), e);
                throw e;
            }
        }
    }

    /**
     * Records a compensation step's failure at its reserved guard sequence
     * (the same sequence a successful outcome would use for
     * {@link EventType#COMPENSATION_STEP_COMPLETED}) — so a recovery replay
     * finds exactly one outcome event per entry, whichever it turned out to be.
     *
     * @param ctx       the workflow context
     * @param seq       the entry's guard sequence ({@code stepBaseSeq} /
     *                  {@code branchBaseSeq} from the caller)
     * @param stepName  the compensation step name
     * @param exception the failure
     */
    private void recordStepFailure(WorkflowContext ctx, int seq, String stepName, Exception exception) {
        try {
            var errorPayload = serializer.serialize(new StepFailure(
                    stepName,
                    exception.getClass().getName(),
                    exception.getMessage()
            ));
            appendEvent(ctx, seq, EventType.COMPENSATION_STEP_FAILED, stepName, errorPayload);
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.COMPENSATION_STEP_FAILED);
        } catch (DuplicateEventException staleRun) {
            // Issue 18: the winner already recorded an outcome at this
            // sequence — this run's view of the step is stale. Stand down
            // rather than mislabelling the collision as a recorded failure.
            throw staleRun;
        } catch (Exception e) {
            logger.warn("Failed to record compensation step failure for '{}' in workflow '{}'",
                    stepName, ctx.workflowId(), e);
        }
    }

    /**
     * Invokes one observer callback, containing a misbehaving observer.
     *
     * <p>Since coordinator Ruling 4, containment is structural at the seam:
     * {@link io.b2mash.maestro.core.observe.CompositeEngineObserver#of} always
     * wraps. This guard stays as depth — the constructors accept <em>any</em>
     * {@code EngineObserver}, so nothing forces an embedder or a test wiring
     * the engine by hand through {@code of(...)}, and an escape here would
     * abort a compensation phase and strand the instance in
     * {@code COMPENSATING}. {@code RuntimeException} only: {@code Error}s (the
     * engine's control-flow signals) always propagate, so a shutdown or
     * terminate landing mid-compensation still unwinds this thread.
     */
    private void emit(String callback, String workflowId, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException e) {
            logger.warn("EngineObserver.{} threw for workflow '{}' — ignoring: {}",
                    callback, workflowId, e.toString());
        }
    }

    private void appendEvent(WorkflowContext ctx, int seq, EventType type,
                             String stepName, @Nullable JsonNode payload) {
        try {
            var event = new WorkflowEvent(
                    UUID.randomUUID(),
                    ctx.workflowInstanceId(),
                    seq,
                    type,
                    stepName,
                    payload,
                    Instant.now()
            );
            store.appendEvent(event);
        } catch (DuplicateEventException staleRun) {
            // Issue 18: a compensation-phase append that hits the store's
            // (instance, sequence) unique guard means a concurrent runner owns
            // this workflow's compensation progress — swallowing it here (as
            // this method does for other append problems) would let a stale
            // run keep executing compensation ACTIONS the winner also runs.
            // Propagate so the whole attempt stands down at the executor.
            throw staleRun;
        } catch (Exception e) {
            logger.warn("Failed to append {} event for workflow '{}'", type, ctx.workflowId(), e);
        }
    }

    private void publishLifecycleEvent(WorkflowContext ctx, String stepName,
                                       LifecycleEventType eventType) {
        if (messaging == null) return;
        try {
            messaging.publishLifecycleEvent(new WorkflowLifecycleEvent(
                    ctx.workflowInstanceId(),
                    ctx.workflowId(),
                    ctx.workflowType(),
                    serviceName,
                    ctx.taskQueue(),
                    eventType,
                    stepName,
                    null,
                    Instant.now()
            ));
        } catch (Exception e) {
            logger.warn("Failed to publish {} lifecycle event for workflow '{}'",
                    eventType, ctx.workflowId(), e);
        }
    }

    // ── Payload records ───────────────────────────────────────────────

    private record CompensationSummary(int totalCompensations, List<String> failedSteps) {}
    private record StepFailure(String stepName, String exceptionType, @Nullable String message) {}
    private record ParallelCompensationDetail(int branchCount) {}
}
