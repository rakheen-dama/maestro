package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-node, per-task-queue concurrency limits for workflow runs and live
 * activity executions (F8 / Issue 25).
 *
 * <p>Each configured task queue gets two independent pools of slots — one for
 * workflow runs ({@link TaskQueueLimits#workflowConcurrency()}), one for live
 * activity executions ({@link TaskQueueLimits#activityConcurrency()}). A task
 * queue is the bucket a workflow names in {@code @DurableWorkflow(taskQueue = ...)};
 * every activity a run calls is counted against its run's queue. Queues that
 * were not configured are <b>unbounded</b>: every method is a no-op for them,
 * and {@link #UNBOUNDED} — the governor with no configured queues — never
 * blocks anything.
 *
 * <h2>Workflow slots</h2>
 * <ul>
 *   <li>A run takes its slot when it starts executing on this node
 *       ({@link #acquireWorkflowSlot}) — blocking until one is free — and
 *       gives it back when it reaches a terminal status or otherwise ends its
 *       local run ({@link #releaseWorkflowSlot}).</li>
 *   <li>A run parked on {@code sleep()} or {@code awaitSignal()} does
 *       <em>not</em> hold its slot: the park is wrapped in {@link #parked},
 *       which gives the slot back for the park's duration and reacquires one —
 *       blocking if necessary — before the run resumes. A long-parked
 *       workflow therefore never starves the queue, and a workflow whose wake
 *       depends on another run of the same queue cannot deadlock it.</li>
 *   <li>A run's slot is tracked by its {@code workflowId}, so every thread of
 *       the run — {@code parallel()} branches included — shares the one slot.
 *       The slot is given back by whichever thread parks while the run holds
 *       it, and taken back by the first thread whose park chunk ends. So a run
 *       can briefly hold its slot with no thread executing — e.g. branch 2
 *       parks (gives), branch 1 parks (nothing to give), branch 1 wakes (takes)
 *       and finishes, leaving the parent in its join and branch 2 parked — but
 *       for at most one park chunk ({@code maestro.signal.wake-recheck-interval}),
 *       because every chunk gives the slot again. Conversely, branches still
 *       executing while a sibling gave the slot back are bounded only by their
 *       activity slots. A run never holds more than one slot (no over-limit),
 *       and the per-chunk give means no wait for a slot can deadlock on a
 *       parked run.</li>
 * </ul>
 *
 * <h2>Activity slots</h2>
 * <p>An activity slot is held only around a <em>live</em> execution — from the
 * activity's dedup lock, through every retry attempt and backoff, to its
 * persisted outcome. A replayed (memoized) step executes nothing and never
 * takes one.
 *
 * <h2>Scope</h2>
 * <p>Limits are per node: three nodes configured with a workflow concurrency of
 * 10 run up to 30 workflows of that queue at once. Slots are in-memory only;
 * nothing is persisted.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Any number of workflow, branch and activity threads may call
 * any method concurrently. Slots are handed out in FIFO order (fair
 * semaphores). A run's held/released state changes by compare-and-set, so two
 * branches of one run waking at once take exactly one slot between them, and
 * a branch reacquiring after its run already released for good gives the slot
 * straight back rather than leaking it.
 */
public final class TaskQueueGovernor {

    /** A governor with no configured queues: nothing is ever bounded. */
    public static final TaskQueueGovernor UNBOUNDED = new TaskQueueGovernor(Map.of());

    /** One configured queue's two slot pools. */
    private record Bucket(String name, Semaphore workflowSlots, Semaphore activitySlots) {}

    /** One run's claim on its queue's workflow pool. */
    private static final class RunSlot {
        final Bucket bucket;
        /** Whether this run currently holds a permit of {@code bucket.workflowSlots}. */
        final AtomicBoolean held = new AtomicBoolean();
        /** Set once the run's local execution ended; a late reacquire gives back at once. */
        volatile boolean closed;

        RunSlot(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    private final Map<String, Bucket> buckets;

    /** Runs on a configured queue that have started on this node, keyed by {@code workflowId}. */
    private final ConcurrentHashMap<String, RunSlot> runs = new ConcurrentHashMap<>();

    /**
     * Creates a governor for the given per-queue limits.
     *
     * @param limits limits keyed by task-queue name; queues not present are
     *               unbounded. Copied — later changes to the map have no effect.
     */
    public TaskQueueGovernor(Map<String, TaskQueueLimits> limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        var configured = new HashMap<String, Bucket>();
        limits.forEach((queue, limit) -> configured.put(queue, new Bucket(queue,
                new Semaphore(limit.workflowConcurrency(), true),
                new Semaphore(limit.activityConcurrency(), true))));
        this.buckets = Map.copyOf(configured);
    }

    /**
     * Takes a workflow slot on {@code taskQueue} for the run of
     * {@code workflowId}, blocking until one is free. Records which queue the
     * run belongs to so {@link #parked} and {@link #releaseWorkflowSlot} need
     * only the workflow ID. A no-op if the queue is unbounded or the run
     * already holds its slot.
     *
     * @param taskQueue  the run's task queue
     * @param workflowId the run's workflow ID
     * @throws ExecutorShutdownException if the waiting thread is interrupted —
     *         the run stops without being recorded as a failure (the thread's
     *         interrupt status is restored)
     */
    public void acquireWorkflowSlot(String taskQueue, String workflowId) {
        var bucket = buckets.get(taskQueue);
        if (bucket == null) {
            return;
        }
        take(runs.computeIfAbsent(workflowId, _ -> new RunSlot(bucket)));
    }

    /**
     * Gives back the workflow slot of the run of {@code workflowId} because its
     * local run has ended, and forgets the run. Idempotent: a no-op when the
     * run holds no slot (unbounded queue, already released, or released by a
     * park that ended in a control-flow {@code Error}) — safe in a
     * {@code finally}.
     *
     * @param workflowId the run's workflow ID
     */
    public void releaseWorkflowSlot(String workflowId) {
        var run = runs.remove(workflowId);
        if (run == null) {
            return;
        }
        run.closed = true;
        give(run);
    }

    /**
     * Runs {@code park} without holding the workflow slot of the run of
     * {@code workflowId}: gives the slot back, runs {@code park}, and
     * reacquires a slot (blocking until one is free) before returning.
     *
     * <p>The slot is reacquired only when {@code park} returns normally or
     * throws {@link TimeoutException} — a park chunk that expired, after which
     * the run carries on (re-checks, re-parks or times out). The exception is
     * rethrown after the reacquire. Anything else {@code park} throws
     * propagates <em>without</em> a reacquire: in particular an {@code Error}
     * such as a shutdown, terminate or unknown-history signal ends the run, and
     * the run's final {@link #releaseWorkflowSlot} is then a no-op.
     *
     * <p>When the run holds no slot (unbounded queue), {@code park} simply runs.
     *
     * @param workflowId the parking run's workflow ID
     * @param park       the park itself
     * @param <T>        the park's result type
     * @return what {@code park} returned
     * @throws TimeoutException          if {@code park} timed out (after the reacquire)
     * @throws ExecutorShutdownException if interrupted while reacquiring
     * @throws Exception                 whatever else {@code park} throws, unchanged
     */
    public <T extends @Nullable Object> T parked(String workflowId, Callable<T> park) throws Exception {
        var run = runs.get(workflowId);
        if (run == null) {
            return park.call();
        }
        give(run);
        T result;
        try {
            result = park.call();
        } catch (TimeoutException expired) {
            take(run);
            throw expired;
        }
        take(run);
        return result;
    }

    /**
     * Takes an activity slot on {@code taskQueue}, blocking until one is free.
     * A no-op if the queue is unbounded. Every successful call must be paired
     * with exactly one {@link #releaseActivitySlot} in a {@code finally}.
     *
     * @param taskQueue the calling run's task queue
     * @return {@code true} if no slot was free and the call had to wait for
     *         one — the caller should then re-check whether its run was asked
     *         to stop meanwhile; {@code false} if a slot was free at once or
     *         the queue is unbounded
     * @throws ExecutorShutdownException if the waiting thread is interrupted
     *         (the thread's interrupt status is restored)
     */
    public boolean acquireActivitySlot(String taskQueue) {
        var bucket = buckets.get(taskQueue);
        if (bucket == null) {
            return false;
        }
        var slots = bucket.activitySlots();
        try {
            // Timed form, not tryAcquire(): it honours the fair FIFO order
            // instead of barging past runs already waiting.
            if (slots.tryAcquire(0, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            throw interrupted("an activity slot", taskQueue);
        }
        acquire(slots, "an activity slot", taskQueue);
        return true;
    }

    /**
     * Gives back an activity slot taken by {@link #acquireActivitySlot}. A
     * no-op if the queue is unbounded.
     *
     * @param taskQueue the calling run's task queue
     */
    public void releaseActivitySlot(String taskQueue) {
        var bucket = buckets.get(taskQueue);
        if (bucket != null) {
            bucket.activitySlots().release();
        }
    }

    /**
     * Returns how many workflow slots of {@code taskQueue} are free right now.
     *
     * @param taskQueue the task queue
     * @return the free slot count, or empty if the queue is unbounded
     */
    public OptionalInt availableWorkflowSlots(String taskQueue) {
        var bucket = buckets.get(taskQueue);
        return bucket == null ? OptionalInt.empty() : OptionalInt.of(bucket.workflowSlots().availablePermits());
    }

    /**
     * Returns how many activity slots of {@code taskQueue} are free right now.
     *
     * @param taskQueue the task queue
     * @return the free slot count, or empty if the queue is unbounded
     */
    public OptionalInt availableActivitySlots(String taskQueue) {
        var bucket = buckets.get(taskQueue);
        return bucket == null ? OptionalInt.empty() : OptionalInt.of(bucket.activitySlots().availablePermits());
    }

    // ── internals ──────────────────────────────────────────────────────

    /** Takes a permit for {@code run} unless it already holds one or has ended. */
    private static void take(RunSlot run) {
        if (run.closed || run.held.get()) {
            return;
        }
        acquire(run.bucket.workflowSlots(), "a workflow slot", run.bucket.name());
        if (!run.held.compareAndSet(false, true)) {
            // Another thread of the same run (a parallel branch) got there first.
            run.bucket.workflowSlots().release();
            return;
        }
        if (run.closed) {
            // The run ended while this thread waited: hand the permit straight back.
            give(run);
        }
    }

    /** Gives back {@code run}'s permit if it holds one. */
    private static void give(RunSlot run) {
        if (run.held.compareAndSet(true, false)) {
            run.bucket.workflowSlots().release();
        }
    }

    private static void acquire(Semaphore slots, String what, String taskQueue) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            throw interrupted(what, taskQueue);
        }
    }

    /** Restores the interrupt status and builds the stop signal for an interrupted wait. */
    private static ExecutorShutdownException interrupted(String what, String taskQueue) {
        Thread.currentThread().interrupt();
        return new ExecutorShutdownException(
                "interrupted while waiting for " + what + " on queue '" + taskQueue + "'");
    }
}
