package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;

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
 * <p>Each compensation action calls through the activity proxy, which
 * assigns sequence numbers and persists results. On recovery, completed
 * compensations replay from the store; uncompleted ones execute live.
 *
 * <h2>Thread Safety</h2>
 * <p>Each instance is used by a single workflow's virtual thread (plus
 * spawned parallel compensation threads if parallel mode is enabled).
 */
public final class SagaManager {

    private static final Logger logger = LoggerFactory.getLogger(SagaManager.class);

    private final WorkflowStore store;
    private final @Nullable WorkflowMessaging messaging;
    private final PayloadSerializer serializer;
    private final String serviceName;

    /**
     * Creates a new SagaManager.
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
        this.store = store;
        this.messaging = messaging;
        this.serializer = serializer;
        this.serviceName = serviceName;
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

        // Record COMPENSATION_STARTED event
        appendEvent(ctx, EventType.COMPENSATION_STARTED, "$maestro:compensation", null);
        publishLifecycleEvent(ctx, "$maestro:compensation", LifecycleEventType.COMPENSATION_STARTED);

        // Execute compensations
        List<String> failedCompensations;
        if (parallelCompensation) {
            failedCompensations = executeParallel(ctx, entries);
        } else {
            failedCompensations = executeSequential(ctx, entries);
        }

        // Record COMPENSATION_COMPLETED event
        var completionPayload = failedCompensations.isEmpty()
                ? null
                : serializer.serialize(new CompensationSummary(entries.size(), failedCompensations));
        appendEvent(ctx, EventType.COMPENSATION_COMPLETED, "$maestro:compensation", completionPayload);
        publishLifecycleEvent(ctx, "$maestro:compensation", LifecycleEventType.COMPENSATION_COMPLETED);

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

    private List<String> executeSequential(WorkflowContext ctx, List<CompensationEntry> entries) {
        var failures = new ArrayList<String>();

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            try {
                entry.action().run();
                logger.debug("Compensation {} '{}' completed for workflow '{}'",
                        i, entry.stepName(), ctx.workflowId());
                publishLifecycleEvent(ctx, entry.stepName(),
                        LifecycleEventType.COMPENSATION_STEP_COMPLETED);
            } catch (Exception e) {
                logger.error("Compensation {} '{}' failed for workflow '{}': {}",
                        i, entry.stepName(), ctx.workflowId(), e.getMessage(), e);
                recordStepFailure(ctx, entry.stepName(), e);
                failures.add(entry.stepName());
            }
        }

        return failures;
    }

    // ── Parallel execution ────────────────────────────────────────────

    /**
     * Branch multiplier for parallel compensation sequence isolation.
     * Matches {@code DefaultWorkflowOperations.BRANCH_MULTIPLIER}.
     * Each branch gets up to {@code BRANCH_MULTIPLIER - 1} sequence slots.
     */
    private static final int BRANCH_MULTIPLIER = 1000;

    private List<String> executeParallel(WorkflowContext ctx, List<CompensationEntry> entries) {
        var failures = new ArrayList<String>();
        var errors = new ArrayList<AtomicReference<Throwable>>(entries.size());
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
                                            publishLifecycleEvent(ctx, entry.stepName(),
                                                    LifecycleEventType.COMPENSATION_STEP_COMPLETED);
                                        } catch (Throwable t) {
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

        // Collect failures
        for (int i = 0; i < entries.size(); i++) {
            var error = errors.get(i).get();
            if (error != null) {
                var entry = entries.get(i);
                logger.error("Compensation {} '{}' failed for workflow '{}': {}",
                        i, entry.stepName(), ctx.workflowId(), error.getMessage(), error);
                recordStepFailure(ctx, entry.stepName(),
                        error instanceof Exception ex ? ex : new RuntimeException(error));
                failures.add(entry.stepName());
            }
        }

        return failures;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private void transitionToCompensating(WorkflowContext ctx, WorkflowInstance instance) {
        try {
            var latest = store.getInstance(ctx.workflowId()).orElse(instance);
            var compensating = latest.toBuilder()
                    .status(WorkflowStatus.COMPENSATING)
                    .updatedAt(Instant.now())
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

    private void recordStepFailure(WorkflowContext ctx, String stepName, Exception exception) {
        try {
            var errorPayload = serializer.serialize(new StepFailure(
                    stepName,
                    exception.getClass().getName(),
                    exception.getMessage()
            ));
            appendEvent(ctx, EventType.COMPENSATION_STEP_FAILED, stepName, errorPayload);
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.COMPENSATION_STEP_FAILED);
        } catch (Exception e) {
            logger.warn("Failed to record compensation step failure for '{}' in workflow '{}'",
                    stepName, ctx.workflowId(), e);
        }
    }

    private void appendEvent(WorkflowContext ctx, EventType type,
                             String stepName, @Nullable JsonNode payload) {
        appendEvent(ctx, ctx.nextSequence(), type, stepName, payload);
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
