package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-node, per-task-queue concurrency limits for workflow runs and live
 * activity executions (F8 / Issue 25).
 *
 * <p>Each configured task queue gets two independent pools of slots — one for
 * workflow runs ({@link TaskQueueLimits#workflowConcurrency()}), one for live
 * activity executions ({@link TaskQueueLimits#activityConcurrency()}). A task
 * queue is the bucket a workflow names in {@code @DurableWorkflow(taskQueue = ...)};
 * every activity a run calls is counted against its run's queue.
 *
 * <p>A queue that was not configured is bounded by the governor's
 * <em>default</em> limits when it has them
 * ({@link #TaskQueueGovernor(Map, TaskQueueLimits)}): the first call naming the
 * queue creates its own two pools, sized from the defaults, and from then on it
 * behaves exactly like a configured queue — every unconfigured queue gets
 * separate pools, never one shared pool. Without default limits an
 * unconfigured queue is <b>unbounded</b>: every method is a no-op for it, and
 * {@link #UNBOUNDED} — the governor with no configured queues and no defaults
 * — never blocks anything.
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
 * activity's start, through every retry attempt and backoff, to its
 * persisted outcome. A replayed (memoized) step executes nothing and never
 * takes one.
 *
 * <h2>Scope</h2>
 * <p>Limits are per node: three nodes configured with a workflow concurrency of
 * 10 run up to 30 workflows of that queue at once. Slots are in-memory only;
 * nothing is persisted.
 *
 * <h2>Shutdown</h2>
 * <p>{@link #shutdown} lends one permit to every pool that exists and marks
 * the governor closed. A waiter woken by that permit sees the governor
 * closed, hands the permit straight back and throws
 * {@link ExecutorShutdownException}, which wakes the next waiter, until the
 * queue is empty; {@link #reclaimShutdownPermits}, called after the drain,
 * takes the loans back from every pool with no thread still queued.
 * <ul>
 *   <li><b>No interrupts.</b> Nothing interrupts a waiting thread and the
 *       closed path sets no interrupt flag, so a waiter's own instance-lock
 *       release over JDBC, in its {@code finally}, is never cut short.</li>
 *   <li><b>No waiter left parked.</b> Every waiter queued at shutdown is woken
 *       in turn by the travelling permit, and a wait that starts after
 *       shutdown throws before it touches the semaphore.</li>
 *   <li><b>No lost permits.</b> A closed waiter gives back exactly the permit
 *       it took, and the reclaim takes back at most one permit per loan, so
 *       the counts return exactly to the limits.</li>
 *   <li><b>Restart-safe.</b> A governor that can never bound anything —
 *       {@link #UNBOUNDED}, which every executor without limits shares — is
 *       never changed by a shutdown.</li>
 * </ul>
 * <p>A shut-down governor still admits an activity whose slot is free at once
 * (in-flight work drains); until the reclaim the loan is such a free permit,
 * so a bounded pool may run one activity over its limit during the drain.
 *
 * <h2>Recovery admission</h2>
 * <p>The recovery poller asks for a slot with {@link #admitRecovery} <em>before</em>
 * it locks an instance, so a node never locks a run it can neither run nor
 * queue (Issue 30). Admission never blocks and never barges: it takes a free
 * permit only with the fair timed {@code tryAcquire(0, …)}, which refuses
 * while any run is already waiting. When no permit is free it may still
 * admit the run as <em>queued</em>, up to the queue's workflow concurrency in
 * recovery tickets: a queued recovered run waits for its slot in the same
 * first-in-first-out line as fresh starts, so it is never treated worse than a
 * fresh start submitted at the same instant and never starves; fresh starts
 * pay at most one queue's worth of recovered runs ahead of them. Anything
 * beyond that is deferred, left unlocked for a peer. Tickets are per queue per
 * node. Every give of a workflow permit, every returned ticket and the
 * shutdown bump the governor's {@link CapacitySignal}, on which the poller
 * waits to re-poll as soon as capacity frees.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Any number of workflow, branch and activity threads may call
 * any method concurrently. Slots are handed out in FIFO order (fair
 * semaphores). At most one thread per run waits on the queue's semaphore: a
 * reacquire runs under a per-run lock, so when two branches of one run wake at
 * once, one waits for the slot and the other waits for that one, then sees the
 * run already holds its slot and carries on — they take exactly one slot
 * between them, and neither ever waits for a permit its own run holds. A
 * branch reacquiring after its run already released for good gives the slot
 * straight back rather than leaking it.
 */
public final class TaskQueueGovernor {

    /** A governor with no configured queues and no default limits: nothing is ever bounded. */
    public static final TaskQueueGovernor UNBOUNDED = new TaskQueueGovernor(Map.of());

    /**
     * One bounded queue's two slot pools, the limits they were sized from, and
     * its recovery tickets: how many more recovered runs may be admitted to
     * wait for a slot, at most the queue's workflow concurrency.
     */
    private record Bucket(String name, TaskQueueLimits limits, Semaphore workflowSlots, Semaphore activitySlots,
                          AtomicInteger recoveryTickets) {}

    /**
     * A recovered run's claim on its queue, taken by {@link #admitRecovery}
     * before the instance is locked and handed to the run.
     *
     * <ul>
     *   <li>{@link Kind#RESERVED}: holds one workflow permit, which becomes the
     *       run's slot when it claims it.</li>
     *   <li>{@link Kind#QUEUED}: holds one of the queue's recovery tickets; the
     *       run waits for a permit, first in first out, on its own thread, and
     *       gives the ticket back however that wait ends.</li>
     *   <li>{@link Kind#UNBOUNDED}: the shared {@link #UNBOUNDED} singleton for a
     *       queue without limits, or for the ungated pass; holds nothing.</li>
     * </ul>
     *
     * <p>Exactly one of the claim
     * ({@link #acquireWorkflowSlot(String, String, SlotReservation)}, on the
     * run's own thread) and {@link #release()} (every path that does not
     * launch the run) acts; the other, and any repeat, does nothing.
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe: one compare-and-set decides between the claim and the
     * release.
     */
    static final class SlotReservation {

        /** What a reservation holds. */
        enum Kind {
            /** Nothing: the queue is unbounded, or the pass is ungated. */
            UNBOUNDED,
            /** One workflow permit. */
            RESERVED,
            /** One recovery ticket: the run will wait for a permit in line. */
            QUEUED
        }

        /** The reservation of an unbounded queue: shared, holds nothing, claim and release are no-ops. */
        static final SlotReservation UNBOUNDED = new SlotReservation(null, null, Kind.UNBOUNDED);

        private final @Nullable Bucket bucket;
        private final @Nullable CapacitySignal signal;
        private final Kind kind;
        /** Won once, by the claim or by the release. */
        private final AtomicBoolean done = new AtomicBoolean();

        private SlotReservation(@Nullable Bucket bucket, @Nullable CapacitySignal signal, Kind kind) {
            this.bucket = bucket;
            this.signal = signal;
            this.kind = kind;
        }

        /** @return what this reservation holds */
        Kind kind() {
            return kind;
        }

        /**
         * Gives back what this reservation holds — the permit or the ticket —
         * and signals capacity, unless the run already claimed it or it was
         * already released. A no-op on {@link #UNBOUNDED}. Never blocks, never
         * throws.
         */
        void release() {
            if (!win()) {
                return;
            }
            var b = Objects.requireNonNull(bucket);
            if (kind == Kind.RESERVED) {
                b.workflowSlots().release();
            } else {
                b.recoveryTickets().incrementAndGet();
            }
            Objects.requireNonNull(signal).signal();
        }

        /** @return {@code true} for the first claim or release of a bounded reservation */
        private boolean win() {
            return kind != Kind.UNBOUNDED && done.compareAndSet(false, true);
        }
    }

    /** One run's claim on its queue's workflow pool. */
    private static final class RunSlot {
        final Bucket bucket;
        /** Whether this run currently holds a permit of {@code bucket.workflowSlots}. */
        final AtomicBoolean held = new AtomicBoolean();
        /** Set once the run's local execution ended; a late reacquire gives back at once. */
        volatile boolean closed;
        /**
         * Serialises the run's reacquires (R49): only its holder waits on the
         * semaphore, so a sibling branch never queues behind its own run.
         * {@link #give} does not take it — a park or the run's end must never
         * wait behind a reacquire.
         */
        final ReentrantLock reacquire = new ReentrantLock();

        RunSlot(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    /** The configured queues' pools; immutable. */
    private final Map<String, Bucket> buckets;

    /** The limits every unconfigured queue's pools are sized from; {@code null} leaves those queues unbounded. */
    private final @Nullable TaskQueueLimits defaults;

    /** Pools of the unconfigured queues seen so far, created on first use from {@link #defaults}. */
    private final ConcurrentHashMap<String, Bucket> defaultBuckets = new ConcurrentHashMap<>();

    /** Runs on a bounded queue that have started on this node, keyed by {@code workflowId}. */
    private final ConcurrentHashMap<String, RunSlot> runs = new ConcurrentHashMap<>();

    /** Set once by {@link #shutdown}: every wait for a slot leaves from then on. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** The pools {@link #shutdown} lent a permit to and whose loan is not yet reclaimed. */
    private final Set<Semaphore> lent = ConcurrentHashMap.newKeySet();

    /** Bumped whenever a workflow permit or a recovery ticket is given back, and at shutdown. */
    private final CapacitySignal capacitySignal = new CapacitySignal();

    /**
     * Creates a governor for the given per-queue limits, with no default:
     * queues not present in {@code limits} are unbounded. Same as
     * {@code new TaskQueueGovernor(limits, null)}.
     *
     * @param limits limits keyed by task-queue name. Copied — later changes to
     *               the map have no effect.
     */
    public TaskQueueGovernor(Map<String, TaskQueueLimits> limits) {
        this(limits, null);
    }

    /**
     * Creates a governor for the given per-queue limits and a default for
     * every other queue.
     *
     * @param limits   limits keyed by task-queue name. Copied — later changes to
     *                 the map have no effect.
     * @param defaults the limits of each queue not present in {@code limits}:
     *                 such a queue gets its own pools of this size, created the
     *                 first time a call names it. {@code null} leaves those
     *                 queues unbounded.
     */
    public TaskQueueGovernor(Map<String, TaskQueueLimits> limits, @Nullable TaskQueueLimits defaults) {
        Objects.requireNonNull(limits, "limits must not be null");
        var configured = new HashMap<String, Bucket>();
        limits.forEach((queue, limit) -> configured.put(queue, newBucket(queue, limit)));
        this.buckets = Map.copyOf(configured);
        this.defaults = defaults;
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
     *         interrupt status is restored) — or if the governor has been shut
     *         down: a waiting call leaves at once, and none sets the interrupt
     *         flag
     */
    public void acquireWorkflowSlot(String taskQueue, String workflowId) {
        var bucket = bucket(taskQueue);
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
     * Asks for a workflow slot on {@code taskQueue} for a recovered run,
     * without blocking — called before the run's instance is locked.
     *
     * <ul>
     *   <li>An unbounded queue: {@link SlotReservation#UNBOUNDED}, the shared
     *       singleton (no allocation).</li>
     *   <li>A free permit, taken with the fair timed {@code tryAcquire(0, …)}
     *       so it never overtakes a run already waiting: a
     *       {@link SlotReservation.Kind#RESERVED RESERVED} reservation.</li>
     *   <li>Else a free recovery ticket: a
     *       {@link SlotReservation.Kind#QUEUED QUEUED} reservation.</li>
     *   <li>Else, or once the governor is shut down, or when the calling
     *       thread is interrupted (the flag is kept): {@code null} — the
     *       instance is deferred.</li>
     * </ul>
     *
     * <p>A non-null reservation must reach exactly one of the run's claim
     * ({@link #acquireWorkflowSlot(String, String, SlotReservation)}) or
     * {@link SlotReservation#release()}.
     *
     * @param taskQueue the recovered instance's task queue
     * @return the reservation, or {@code null} to defer the instance
     */
    @Nullable SlotReservation admitRecovery(String taskQueue) {
        if (closed.get()) {
            return null;
        }
        var bucket = bucket(taskQueue);
        if (bucket == null) {
            return SlotReservation.UNBOUNDED;
        }
        try {
            // Timed form, not tryAcquire(): it refuses while any run waits,
            // so a recovered run never barges past one.
            if (bucket.workflowSlots().tryAcquire(0, TimeUnit.SECONDS)) {
                if (closed.get()) {
                    // The permit may be shutdown's loan, travelling to a waiter.
                    bucket.workflowSlots().release();
                    return null;
                }
                return new SlotReservation(bucket, capacitySignal, SlotReservation.Kind.RESERVED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the poller is stopping; the pass ends at the next instance
            return null;
        }
        var tickets = bucket.recoveryTickets();
        for (var free = tickets.get(); free > 0; free = tickets.get()) {
            if (tickets.compareAndSet(free, free - 1)) {
                return new SlotReservation(bucket, capacitySignal, SlotReservation.Kind.QUEUED);
            }
        }
        return null;
    }

    /**
     * Takes the workflow slot of a recovered run, on the run's own thread,
     * from the reservation {@link #admitRecovery} gave it.
     *
     * <ul>
     *   <li>{@code null}, {@link SlotReservation#UNBOUNDED}, or a reservation
     *       already released: {@link #acquireWorkflowSlot(String, String)}.</li>
     *   <li>{@link SlotReservation.Kind#RESERVED RESERVED}: the reserved
     *       permit becomes the run's slot; nothing is waited for.</li>
     *   <li>{@link SlotReservation.Kind#QUEUED QUEUED}: waits for a permit in
     *       line, exactly like {@link #acquireWorkflowSlot(String, String)},
     *       and gives the ticket back however the wait ends — a permit, the
     *       shutdown signal or an interrupt.</li>
     * </ul>
     *
     * @param taskQueue   the run's task queue
     * @param workflowId  the run's workflow ID
     * @param reservation the run's reservation; {@code null} for a run that has none
     * @throws ExecutorShutdownException as {@link #acquireWorkflowSlot(String, String)}
     */
    void acquireWorkflowSlot(String taskQueue, String workflowId, @Nullable SlotReservation reservation) {
        if (reservation == null || !reservation.win()) {
            acquireWorkflowSlot(taskQueue, workflowId);
            return;
        }
        var bucket = Objects.requireNonNull(reservation.bucket);
        if (reservation.kind() == SlotReservation.Kind.RESERVED) {
            // Not checked against closed: a run claiming its permit after
            // shutdown began stands down in executeWorkflow (cause SHUTDOWN)
            // before its body, and its finally gives the permit back.
            var slot = new RunSlot(bucket);
            slot.held.set(true);
            var existing = runs.putIfAbsent(workflowId, slot);
            if (existing != null) {
                // A run of this id already claimed here (possible only without
                // an instance lock): the reserved permit is not needed.
                bucket.workflowSlots().release();
                capacitySignal.signal();
                take(existing);
            }
            return;
        }
        try {
            take(runs.computeIfAbsent(workflowId, _ -> new RunSlot(bucket)));
        } finally {
            bucket.recoveryTickets().incrementAndGet();
            capacitySignal.signal();
        }
    }

    /**
     * How many more recovered runs {@code taskQueue} could admit right now:
     * its free permits (none while a run waits — admission would refuse
     * them) plus its free recovery tickets. Advisory: it only sizes the
     * recovery poller's reads; {@link #admitRecovery} remains the authority.
     *
     * @param taskQueue the task queue
     * @return the headroom; {@link Integer#MAX_VALUE} for an unbounded queue,
     *         {@code 0} once the governor is shut down
     */
    int recoveryHeadroom(String taskQueue) {
        if (closed.get()) {
            return 0;
        }
        var bucket = bucket(taskQueue);
        if (bucket == null) {
            return Integer.MAX_VALUE;
        }
        var slots = bucket.workflowSlots();
        var free = slots.hasQueuedThreads() ? 0 : slots.availablePermits();
        return free + bucket.recoveryTickets().get();
    }

    /**
     * Returns how many threads are waiting for a workflow slot of
     * {@code taskQueue} right now. Advisory (a snapshot); for tests and
     * diagnostics.
     *
     * @param taskQueue the task queue
     * @return the waiting threads; {@code 0} for an unbounded queue
     */
    int workflowSlotWaiters(String taskQueue) {
        var bucket = bucket(taskQueue);
        return bucket == null ? 0 : bucket.workflowSlots().getQueueLength();
    }

    /** @return the event count bumped whenever a workflow permit or a recovery ticket is given back */
    CapacitySignal capacitySignal() {
        return capacitySignal;
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
     * @throws ExecutorShutdownException if interrupted while reacquiring, or if
     *         the governor has been shut down: a waiting call leaves at once,
     *         and none sets the interrupt flag
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
     *         (the thread's interrupt status is restored), or if the governor
     *         has been shut down: a waiting call leaves at once, and none sets
     *         the interrupt flag
     */
    public boolean acquireActivitySlot(String taskQueue) {
        var bucket = bucket(taskQueue);
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
        var bucket = bucket(taskQueue);
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
        var bucket = bucket(taskQueue);
        return bucket == null ? OptionalInt.empty() : OptionalInt.of(bucket.workflowSlots().availablePermits());
    }

    /**
     * Returns how many activity slots of {@code taskQueue} are free right now.
     *
     * @param taskQueue the task queue
     * @return the free slot count, or empty if the queue is unbounded
     */
    public OptionalInt availableActivitySlots(String taskQueue) {
        var bucket = bucket(taskQueue);
        return bucket == null ? OptionalInt.empty() : OptionalInt.of(bucket.activitySlots().availablePermits());
    }

    /**
     * Returns how many workflow slots of {@code taskQueue} are held right now:
     * the queue's workflow limit minus its free slots. A run parked on
     * {@code sleep()} or {@code awaitSignal()} holds none (see {@link #parked}).
     *
     * <p>Read-only and non-blocking: it never takes or waits for a slot, so it
     * is safe to call from a metrics scrape thread. Like
     * {@link #availableWorkflowSlots}, a call naming an unconfigured queue
     * under default limits creates that queue's pools.
     *
     * <p>During {@link #shutdown}, until {@link #reclaimShutdownPermits}, each
     * pool holds one lent permit above its limit, so this reads one less than
     * the slots actually held, {@code -1} on an idle pool. A pool that keeps
     * its loan (the drain hit its deadline with threads still queued, or with
     * every slot held) reads one less for the rest of the node's life.
     *
     * <p>During recovery, a {@link SlotReservation.Kind#RESERVED RESERVED}
     * reservation counts from {@link #admitRecovery} on, before its run claims
     * it (the permit is taken; the claim takes no second one). A
     * {@link SlotReservation.Kind#QUEUED QUEUED} recovered run holds no slot
     * while it waits, like any waiting run, and does not count.
     *
     * @param taskQueue the task queue
     * @return the held slot count, or empty if the queue is unbounded
     */
    public OptionalInt workflowSlotsInUse(String taskQueue) {
        var bucket = bucket(taskQueue);
        return bucket == null ? OptionalInt.empty()
                : OptionalInt.of(bucket.limits().workflowConcurrency() - bucket.workflowSlots().availablePermits());
    }

    /**
     * Returns how many activity slots of {@code taskQueue} are held right now:
     * the queue's activity limit minus its free slots — that is, the live
     * activity executions of the queue's runs on this node.
     *
     * <p>Read-only and non-blocking: it never takes or waits for a slot, so it
     * is safe to call from a metrics scrape thread. Like
     * {@link #availableActivitySlots}, a call naming an unconfigured queue
     * under default limits creates that queue's pools.
     *
     * <p>During {@link #shutdown}, until {@link #reclaimShutdownPermits}, each
     * pool holds one lent permit above its limit, so this reads one less than
     * the slots actually held, {@code -1} on an idle pool. A pool that keeps
     * its loan (the drain hit its deadline with threads still queued, or with
     * every slot held) reads one less for the rest of the node's life.
     *
     * @param taskQueue the task queue
     * @return the held slot count, or empty if the queue is unbounded
     */
    public OptionalInt activitySlotsInUse(String taskQueue) {
        var bucket = bucket(taskQueue);
        return bucket == null ? OptionalInt.empty()
                : OptionalInt.of(bucket.limits().activityConcurrency() - bucket.activitySlots().availablePermits());
    }

    /**
     * Shuts the governor down: every wait for a slot, queued now or started
     * later, leaves with {@link ExecutorShutdownException}. Lends one permit
     * to every pool that exists (configured, and default pools created so
     * far) to wake its queue — see the class's "Shutdown" section. Never
     * blocks and never interrupts. Idempotent. A governor that can never bound
     * anything ({@link #UNBOUNDED} and its equivalents) is left untouched.
     */
    void shutdown() {
        if (buckets.isEmpty() && defaults == null) {
            return; // can never bound anything; UNBOUNDED is JVM-wide and must stay usable
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lend(buckets.values());
        lend(defaultBuckets.values());
        // Last: a recovery poller waiting for capacity wakes, sees no headroom
        // and waits for its next full pass or its stop.
        capacitySignal.signal();
    }

    /**
     * Returns whether {@link #shutdown} has closed this governor. Always
     * {@code false} for a governor that can never bound anything
     * ({@link #UNBOUNDED}, shared by every executor without limits), so it
     * does not answer "is the executor shutting down?": code that needs that
     * (recovery admission, for one) must read the executor's own
     * shutting-down flag.
     *
     * @return {@code true} once shut down
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Takes back the permits {@link #shutdown} lent, so each pool's count is
     * exact again. Called once, after the drain. Never takes a permit from a
     * pool that still has threads queued: the reclaim honours the pool's
     * fairness, so a poison permit still travelling down a queue (a drain
     * that hit its deadline) keeps waking it. Such a pool, and a pool whose
     * every permit is held, keeps its loan and reads one over its limit.
     * Never blocks; the caller's interrupt status is left as it was.
     * Idempotent: a loan is taken back at most once.
     */
    void reclaimShutdownPermits() {
        var interrupted = Thread.interrupted(); // a zero-timeout tryAcquire never waits; restored below
        try {
            for (var slots : lent) {
                if (slots.tryAcquire(0, TimeUnit.SECONDS)) {
                    lent.remove(slots);
                }
            }
        } catch (InterruptedException unreachable) {
            interrupted = true; // the flag was cleared above; kept for the compiler
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────

    /**
     * The pools of {@code taskQueue}: its configured ones, else — when there
     * are default limits — its own default-sized ones, created atomically on
     * first use; else {@code null} (unbounded).
     */
    private @Nullable Bucket bucket(String taskQueue) {
        var configured = buckets.get(taskQueue);
        if (configured != null || defaults == null) {
            return configured;
        }
        var limits = defaults;
        return defaultBuckets.computeIfAbsent(taskQueue, queue -> newBucket(queue, limits));
    }

    private static Bucket newBucket(String queue, TaskQueueLimits limit) {
        return new Bucket(queue, limit,
                new Semaphore(limit.workflowConcurrency(), true),
                new Semaphore(limit.activityConcurrency(), true),
                new AtomicInteger(limit.workflowConcurrency()));
    }

    /**
     * Takes a permit for {@code run} unless it already holds one or has ended.
     *
     * <p>R49: the check and the semaphore wait happen under the run's
     * {@code reacquire} lock. Checking {@code held} and then waiting without it
     * let two branches waking at once both see {@code false}; the loser then
     * waited for the permit its own run had just taken — forever, if the
     * winner finished its branch without parking again, wedging the run's
     * {@code parallel()} join. Under the lock only {@code take} sets
     * {@code held}, so a sibling that gets the lock after the holder sees
     * {@code true} and returns without a second acquire.
     */
    private void take(RunSlot run) {
        if (run.closed || run.held.get()) {
            return; // fast path; re-checked under the lock
        }
        try {
            run.reacquire.lockInterruptibly();
        } catch (InterruptedException e) {
            throw interrupted("a workflow slot", run.bucket.name());
        }
        try {
            if (run.closed || run.held.get()) {
                return; // a sibling branch took the run's slot while this thread waited for the lock
            }
            acquire(run.bucket.workflowSlots(), "a workflow slot", run.bucket.name());
            // Nobody else sets held (give only clears it), so this cannot lose a race.
            run.held.set(true);
            if (run.closed) {
                // The run ended while this thread waited: hand the permit straight back.
                give(run);
            }
        } finally {
            run.reacquire.unlock();
        }
    }

    /** Gives back {@code run}'s permit if it holds one, and signals capacity. */
    private void give(RunSlot run) {
        if (run.held.compareAndSet(true, false)) {
            run.bucket.workflowSlots().release();
            capacitySignal.signal();
        }
    }

    /** Lends one permit to each pool of {@code lendTo} and records the loan. */
    private void lend(Iterable<Bucket> lendTo) {
        for (var bucket : lendTo) {
            for (var slots : List.of(bucket.workflowSlots(), bucket.activitySlots())) {
                lent.add(slots);
                slots.release();
            }
        }
    }

    /**
     * The only waiting path. Refuses to wait once the governor is closed, and
     * a waiter woken after it closed hands the permit straight back — which
     * wakes the next waiter — and leaves with the shutdown signal.
     */
    private void acquire(Semaphore slots, String what, String taskQueue) {
        if (closed.get()) {
            throw shutdownSignal(what, taskQueue);
        }
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            throw interrupted(what, taskQueue);
        }
        if (closed.get()) {
            slots.release();
            throw shutdownSignal(what, taskQueue);
        }
    }

    /** Builds the stop signal for a wait refused because the governor shut down. Sets no interrupt flag. */
    private static ExecutorShutdownException shutdownSignal(String what, String taskQueue) {
        return new ExecutorShutdownException(
                "this node is shutting down — no longer waiting for " + what + " on queue '" + taskQueue + "'");
    }

    /** Restores the interrupt status and builds the stop signal for an interrupted wait. */
    private static ExecutorShutdownException interrupted(String what, String taskQueue) {
        Thread.currentThread().interrupt();
        return new ExecutorShutdownException(
                "interrupted while waiting for " + what + " on queue '" + taskQueue + "'");
    }
}
