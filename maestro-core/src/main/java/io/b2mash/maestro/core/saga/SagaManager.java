package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
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
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.UnknownHistoryGuard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * sequence block (mirroring the {@code BRANCH_MULTIPLIER} isolation already
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
     * @param ctx                   the workflow context
     * @param instance              the workflow instance
     * @param stack                 the compensation stack to unwind
     * @param parallelCompensation  whether to run compensations in parallel
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

        logger.info("Running {} compensation(s) for workflow '{}' (parallel={})",
                entries.size(), ctx.workflowId(), parallelCompensation);

        // Transition to COMPENSATING
        transitionToCompensating(ctx, instance);

        // Record COMPENSATION_STARTED — replay-skip guarded like every other
        // event-emitting path, rather than relying on the store's unique
        // index to silently reject the duplicate on a recovery re-run. The
        // seq this call consumes doubles as the anchor for the sequential
        // loop's per-entry sequence blocks (see executeSequential).
        var startedSeq = ctx.nextSequence();
        if (UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), startedSeq),
                ctx.workflowId()).isEmpty()) {
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
        } else {
            logger.debug("Replaying COMPENSATION_STARTED at seq {} for workflow '{}' — already recorded",
                    startedSeq, ctx.workflowId());
        }

        // Execute compensations
        List<String> failedCompensations;
        if (parallelCompensation) {
            failedCompensations = executeParallel(ctx, entries);
        } else {
            failedCompensations = executeSequential(ctx, entries, startedSeq);
        }

        // Record COMPENSATION_COMPLETED — same replay-skip guard.
        var completedSeq = ctx.nextSequence();
        if (UnknownHistoryGuard.requireKnown(
                store.getEventBySequence(ctx.workflowInstanceId(), completedSeq),
                ctx.workflowId()).isEmpty()) {
            var completionPayload = failedCompensations.isEmpty()
                    ? null
                    : serializer.serialize(new CompensationSummary(entries.size(), failedCompensations));
            appendEvent(ctx, completedSeq, EventType.COMPENSATION_COMPLETED, "$maestro:compensation", completionPayload);
            publishLifecycleEvent(ctx, "$maestro:compensation", LifecycleEventType.COMPENSATION_COMPLETED);
        } else {
            logger.debug("Replaying COMPENSATION_COMPLETED at seq {} for workflow '{}' — already recorded",
                    completedSeq, ctx.workflowId());
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

    // ── Sequential execution ──────────────────────────────────────────

    /**
     * Runs compensations one at a time, in LIFO order.
     *
     * <p>Each entry gets its own reserved sequence block — {@code anchorSeq *
     * BRANCH_MULTIPLIER + (i+1) * BRANCH_MULTIPLIER} — the same isolation
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
     * @param ctx       the workflow context
     * @param entries   the compensation entries, in LIFO execution order
     * @param anchorSeq the {@code COMPENSATION_STARTED} event's own sequence
     *                  number, reused (not re-consumed) as the block-math anchor
     * @return the step names of entries whose action failed (live or replayed)
     */
    private List<String> executeSequential(WorkflowContext ctx, List<CompensationEntry> entries, int anchorSeq) {
        var failures = new ArrayList<String>();

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var stepBaseSeq = anchorSeq * BRANCH_MULTIPLIER + (i + 1) * BRANCH_MULTIPLIER;

            // Replay-skip guard: don't re-invoke an action whose outcome is
            // already durable. A manually-registered compensation isn't
            // memoized the way an activity call is, so without this check
            // it would run again on every recovery replay.
            var storedEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), stepBaseSeq),
                    ctx.workflowId());
            if (storedEvent.isPresent()) {
                var eventType = storedEvent.get().eventType();
                logger.debug("Replaying compensation {} '{}' at seq {} ({}) for workflow '{}' — not re-invoking",
                        i, entry.stepName(), stepBaseSeq, eventType, ctx.workflowId());
                if (eventType == EventType.COMPENSATION_STEP_FAILED) {
                    failures.add(entry.stepName());
                }
                continue;
            }

            // Live path: enter this entry's reserved block so any sequence
            // numbers the action consumes internally (e.g. a nested
            // @Compensate activity call) land inside it, not on the next
            // entry's guard sequence.
            ctx.setReplaying(false);
            ctx.setSequence(stepBaseSeq);

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
                entry.action().run();
                logger.debug("Compensation {} '{}' completed for workflow '{}'",
                        i, entry.stepName(), ctx.workflowId());
                appendEvent(ctx, stepBaseSeq, EventType.COMPENSATION_STEP_COMPLETED, entry.stepName(), null);
                publishLifecycleEvent(ctx, entry.stepName(),
                        LifecycleEventType.COMPENSATION_STEP_COMPLETED);
            } catch (DuplicateEventException staleRun) {
                // Issue 18: an append collision here means a concurrent runner
                // owns this workflow's compensation progress (the replay-skip
                // guard above read the store before the winner's event landed).
                // That is not a step failure — recording COMPENSATION_STEP_FAILED
                // would durably contradict the winner's COMPLETED entry at the
                // same sequence. Rethrow so the whole attempt stands down
                // (WorkflowExecutor leaves the instance COMPENSATING and
                // recoverable; the winner's run finishes the job) — the exact
                // treatment ExecutorShutdownException gets here, and ahead of
                // catch (Exception e) because DuplicateEventException is a
                // MaestroException.
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
        ctx.setSequence(anchorSeq * BRANCH_MULTIPLIER + (entries.size() + 1) * BRANCH_MULTIPLIER);

        return failures;
    }

    // ── Parallel execution ────────────────────────────────────────────

    /**
     * Branch multiplier for parallel compensation sequence isolation.
     * Matches {@code DefaultWorkflowOperations.BRANCH_MULTIPLIER}.
     * Each branch gets up to {@code BRANCH_MULTIPLIER - 1} sequence slots.
     */
    private static final int BRANCH_MULTIPLIER = 1000;

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
     * @param ctx     the workflow context
     * @param entries the compensation entries, one per branch
     * @return the step names of entries whose action failed (live or replayed)
     */
    private List<String> executeParallel(WorkflowContext ctx, List<CompensationEntry> entries) {
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

        // Record the fork point so each branch gets a deterministic sequence space
        var parentSeq = ctx.nextSequence();
        appendEvent(ctx, parentSeq, EventType.SIDE_EFFECT, "$maestro:parallel-compensation",
                serializer.serialize(new ParallelCompensationDetail(entries.size())));

        for (int i = 0; i < entries.size(); i++) {
            var index = i;
            var entry = entries.get(i);

            // Each branch gets its own isolated sequence space
            var branchBaseSeq = parentSeq * BRANCH_MULTIPLIER + (index + 1) * BRANCH_MULTIPLIER;

            // Replay-skip guard: a manually-registered compensation isn't
            // memoized the way an activity call is, so without this check a
            // branch that already completed (or failed) durably before some
            // OTHER branch was interrupted by shutdown would be re-invoked
            // on recovery — never spawn its thread at all.
            var storedEvent = UnknownHistoryGuard.requireKnown(
                    store.getEventBySequence(ctx.workflowInstanceId(), branchBaseSeq),
                    ctx.workflowId());
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
                        var branchCtx = new WorkflowContext(
                                ctx.workflowInstanceId(),
                                ctx.workflowId(),
                                ctx.runId(),
                                ctx.workflowType(),
                                ctx.taskQueue(),
                                ctx.serviceName(),
                                branchBaseSeq,
                                ctx.isReplaying(),
                                null // no operations needed — compensations call through proxy directly
                        );
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
        var nextParentSeq = parentSeq * BRANCH_MULTIPLIER + (entries.size() + 1) * BRANCH_MULTIPLIER;
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
            // COMPENSATING, recoverable; the winner finishes the job).
            if (errorRef.get() instanceof DuplicateEventException staleRun) {
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
                var branchBaseSeq = parentSeq * BRANCH_MULTIPLIER + (i + 1) * BRANCH_MULTIPLIER;
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
     * workflow's own {@code WAITING_*}/{@code RUNNING} writes.
     *
     * <p>{@code WorkflowExecutor.terminateWorkflow} can write {@code TERMINATED}
     * from any node, including one racing this failing run's compensation
     * phase. Without this check, the read-then-write below would blindly
     * overwrite that {@code TERMINATED} row with {@code COMPENSATING} — briefly
     * resurrecting a workflow an operator already stopped, and letting
     * compensations neither the operator nor the row's own history called for
     * start running. A freshly-read {@code TERMINATED} therefore throws
     * {@link WorkflowTerminatedException} instead of writing anything, which
     * propagates out of {@link #compensate} uncaught — an {@code Error}, not
     * caught by this method's own {@code catch (Exception e)} below — for
     * {@code WorkflowExecutor.executeWorkflow}'s
     * {@code catch (WorkflowTerminatedException)} to unwind without recording
     * a failure.
     */
    private void transitionToCompensating(WorkflowContext ctx, WorkflowInstance instance) {
        var latest = store.getInstance(ctx.workflowId()).orElse(instance);
        if (latest.status() == WorkflowStatus.TERMINATED) {
            logger.info("Workflow '{}' is TERMINATED — not starting compensation; abandoning this run",
                    ctx.workflowId());
            throw new WorkflowTerminatedException(ctx.workflowId(), null);
        }
        if (latest.status().isTerminal()) {
            logger.warn("Workflow '{}' is already {} — another runner finalised it first; "
                            + "not transitioning to COMPENSATING",
                    ctx.workflowId(), latest.status());
            return;
        }
        try {
            var compensating = latest.toBuilder()
                    .status(WorkflowStatus.COMPENSATING)
                    .updatedAt(Instant.now())
                    .version(latest.version() + 1)
                    .build();
            store.updateInstance(compensating);
        } catch (io.b2mash.maestro.core.exception.OptimisticLockException e) {
            logger.debug("Optimistic lock conflict updating workflow '{}' to COMPENSATING, continuing",
                    ctx.workflowId());
        } catch (Exception e) {
            logger.warn("Failed to update workflow '{}' status to COMPENSATING",
                    ctx.workflowId(), e);
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
