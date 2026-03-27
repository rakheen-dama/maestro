package io.maestro.core.engine;

import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.RetryExhaustedException;
import io.maestro.core.exception.SignalTimeoutException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.WorkflowTimer;
import io.maestro.core.model.TimerStatus;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowStatus;
import io.maestro.core.retry.RetryUntilOptions;
import io.maestro.core.saga.CompensationStack;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LifecycleEventType;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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

    /**
     * Multiplier for computing branch sequence bases during parallel execution.
     * Branch {@code i} gets sequences starting at {@code parentSeq * BRANCH_MULTIPLIER + (i+1) * BRANCH_MULTIPLIER}.
     * This gives each branch up to {@code BRANCH_MULTIPLIER - 1} sequence slots.
     */
    static final int BRANCH_MULTIPLIER = 1000;

    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final @Nullable WorkflowMessaging messaging;
    private final PayloadSerializer serializer;
    private final ParkingLot parkingLot;
    private final CompensationStack compensationStack;
    private final SignalManager signalManager;

    /**
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
        this.store = store;
        this.distributedLock = distributedLock;
        this.messaging = messaging;
        this.serializer = serializer;
        this.parkingLot = parkingLot;
        this.compensationStack = compensationStack;
        this.signalManager = signalManager;
    }

    // ── sleep ──────────────────────────────────────────────────────────

    @Override
    public void sleep(Duration duration) {
        var ctx = WorkflowContext.current();
        var seq = ctx.nextSequence();
        var stepName = "$maestro:sleep";

        // Replay check: look for TIMER_SCHEDULED at this sequence
        var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);
        if (storedEvent.isPresent()) {
            if (storedEvent.get().eventType() == EventType.TIMER_SCHEDULED) {
                // Check if TIMER_FIRED exists at the next sequence
                var nextSeq = seq + 1;
                var firedEvent = store.getEventBySequence(ctx.workflowInstanceId(), nextSeq);
                if (firedEvent.isPresent() && firedEvent.get().eventType() == EventType.TIMER_FIRED) {
                    // Both events exist — skip the sleep entirely
                    ctx.nextSequence(); // advance past TIMER_FIRED
                    logger.debug("Replaying completed sleep at seq {} (skipped)", seq);
                    return;
                }
                // TIMER_SCHEDULED exists but TIMER_FIRED does not — timer is still pending.
                // Re-park to wait for it.
                logger.debug("Replaying pending sleep at seq {} — re-parking", seq);
                var timerId = extractTimerId(storedEvent.get().payload());
                parkForTimer(ctx, timerId);
                // Timer fired — record the TIMER_FIRED event
                recordTimerFired(ctx);
                // Restore RUNNING status (matches the live path)
                updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_FIRED);
                return;
            }
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

        // Update instance status
        updateInstanceStatus(ctx, WorkflowStatus.WAITING_TIMER);

        // Publish lifecycle event (best-effort)
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_SCHEDULED);

        // Park the virtual thread
        logger.debug("Workflow '{}' sleeping for {} (timerId={})", ctx.workflowId(), duration, timerId);
        parkForTimer(ctx, timerId);

        // Timer fired — record TIMER_FIRED event
        recordTimerFired(ctx);

        // Update status back to RUNNING
        updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.TIMER_FIRED);
        logger.debug("Workflow '{}' woke from sleep (timerId={})", ctx.workflowId(), timerId);
    }

    private void parkForTimer(WorkflowContext ctx, String timerId) {
        var parkKey = ctx.workflowId() + ":timer:" + timerId;
        parkingLot.park(parkKey);
    }

    private void recordTimerFired(WorkflowContext ctx) {
        var firedSeq = ctx.nextSequence();
        appendEvent(ctx, firedSeq, EventType.TIMER_FIRED, "$maestro:timer-fired", null);
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

        // Validate that branch sequence ranges won't overflow Integer.MAX_VALUE
        var peekParentSeq = ctx.currentSequence() + 1;
        long maxBranchBase = (long) peekParentSeq * BRANCH_MULTIPLIER
                + (long) (tasks.size() + 1) * BRANCH_MULTIPLIER;
        if (maxBranchBase > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Parallel execution at sequence %d with %d branches would overflow the integer sequence space. "
                            .formatted(peekParentSeq, tasks.size())
                            + "Reduce nesting depth or number of branches.");
        }

        var parentSeq = ctx.nextSequence();
        var branchCount = tasks.size();

        // Replay check: look for existing parallel fork event at this sequence
        var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), parentSeq);
        if (storedEvent.isEmpty() || storedEvent.get().eventType() != EventType.SIDE_EFFECT) {
            // Live path: record the parallel fork point
            ctx.setReplaying(false);
            appendEvent(ctx, parentSeq, EventType.SIDE_EFFECT, "$maestro:parallel",
                    serializer.serialize(new ParallelDetail(branchCount)));
        }

        var results = new ArrayList<AtomicReference<T>>(branchCount);
        var errors = new ArrayList<AtomicReference<Throwable>>(branchCount);
        var latch = new CountDownLatch(branchCount);

        for (int i = 0; i < branchCount; i++) {
            results.add(new AtomicReference<>());
            errors.add(new AtomicReference<>());
        }

        for (int i = 0; i < branchCount; i++) {
            var branchIndex = i;
            var task = tasks.get(i);

            // Each branch gets its own sequence space
            var branchBaseSeq = parentSeq * BRANCH_MULTIPLIER + (branchIndex + 1) * BRANCH_MULTIPLIER;

            Thread.ofVirtual()
                    .name("maestro-workflow-%s-%s-branch-%d".formatted(
                            ctx.workflowType(), ctx.workflowId(), branchIndex))
                    .start(() -> {
                        // Create a branch context with its own sequence counter
                        // but sharing the same operations instance so branches
                        // can call sleep/awaitSignal if needed
                        var branchCtx = new WorkflowContext(
                                ctx.workflowInstanceId(),
                                ctx.workflowId(),
                                ctx.runId(),
                                ctx.workflowType(),
                                ctx.taskQueue(),
                                ctx.serviceName(),
                                branchBaseSeq,
                                ctx.isReplaying(),
                                DefaultWorkflowOperations.this
                        );
                        WorkflowContext.bind(branchCtx);
                        try {
                            results.get(branchIndex).set(task.call());
                        } catch (Throwable t) {
                            errors.get(branchIndex).set(t);
                        } finally {
                            WorkflowContext.clear();
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

        // Check for errors — fail fast with first error
        for (int i = 0; i < branchCount; i++) {
            var error = errors.get(i).get();
            if (error != null) {
                if (error instanceof RuntimeException re) throw re;
                throw new RuntimeException("Parallel branch " + i + " failed", error);
            }
        }

        // Advance parent sequence counter past all branch spaces
        var nextParentSeq = parentSeq * BRANCH_MULTIPLIER + (branchCount + 1) * BRANCH_MULTIPLIER;
        ctx.setSequence(nextParentSeq);

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
        var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);
        if (storedEvent.isPresent() && storedEvent.get().eventType() == EventType.SIDE_EFFECT) {
            return serializer.deserialize(storedEvent.get().payload(), Instant.class);
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
        var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);
        if (storedEvent.isPresent() && storedEvent.get().eventType() == EventType.SIDE_EFFECT) {
            return serializer.deserialize(storedEvent.get().payload(), String.class);
        }

        // Live path
        ctx.setReplaying(false);
        var uuid = UUID.randomUUID().toString();
        appendEvent(ctx, seq, EventType.SIDE_EFFECT, "$maestro:randomUUID", serializer.serialize(uuid));
        return uuid;
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
        compensationStack.push(compensation);
    }

    @Override
    public void addCompensation(String stepName, Runnable compensation) {
        compensationStack.push(stepName, compensation);
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
        store.appendEvent(event);
    }

    private void updateInstanceStatus(WorkflowContext ctx, WorkflowStatus newStatus) {
        var instance = store.getInstance(ctx.workflowId());
        if (instance.isEmpty()) {
            logger.warn("Cannot update status to {} — workflow '{}' not found", newStatus, ctx.workflowId());
            return;
        }
        var updated = instance.get().toBuilder()
                .status(newStatus)
                .updatedAt(Instant.now())
                .version(instance.get().version() + 1)
                .build();
        store.updateInstance(updated);
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
}
