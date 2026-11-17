package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages per-workflow-instance distributed locks
 * ({@code maestro:lock:workflow:{workflowId}}).
 *
 * <p>A workflow's lock is acquired before its virtual thread launches and
 * held — renewed by a single shared renewer thread — for the workflow's
 * entire local lifetime, including hours parked in {@code WAITING_SIGNAL}
 * or {@code WAITING_TIMER} and through saga compensation. This prevents a
 * second node's recovery from re-executing a workflow that is live here;
 * if this node crashes, the TTL expires and another node's recovery poller
 * picks the workflow up.
 *
 * <p><b>Graceful degradation:</b> with no {@link DistributedLock} backend
 * (or a failing one), acquisition reports {@link Acquisition#NO_BACKEND}
 * and callers proceed exactly as before this lock existed. The lock is an
 * <em>admission</em> guard — it stops a second node from starting a redundant
 * run in the first place; it is not what keeps a stale run from persisting
 * progress. That is the store's lease fencing (Issue 11): every adoption bumps
 * the instance row's {@code lease_epoch}, and every run-scoped write is
 * validated against the epoch the run adopted at, so a run whose lock expired
 * or was never held cannot write once another node has adopted the workflow.
 *
 * <p><b>Lost locks:</b> if a renewal reports lost ownership, the handle is
 * dropped, an ERROR is logged and the {@link #onLockLost} callback is invoked,
 * which the executor uses to mark the run stale so it stands down at its next
 * stop point instead of first trying a write the store would fence anyway.
 * The callback is proactive courtesy, not the guarantee — the fence holds
 * even when the renewer notices nothing (a frozen JVM notices only after it
 * thaws). An {@code Error} in a pass is contained by the renewer's guard:
 * every handle is kept and the next pass runs one interval later.
 *
 * <p><b>Thread safety:</b> all methods are thread-safe. {@link #close(Duration)}
 * never interrupts the renewer: a pass in progress finishes, and
 * {@code close(join)} waits for it for at most {@code join}.
 */
final class WorkflowInstanceLockManager {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowInstanceLockManager.class);

    static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);
    static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofSeconds(10);
    static final String DEFAULT_KEY_PREFIX = "maestro:lock:";
    /** How long {@link #close()} waits for the renewer to end; the wake listener's bound too (WP10). */
    static final Duration CLOSE_JOIN = Duration.ofSeconds(2);
    private static final String WORKFLOW_KEY_SEGMENT = "workflow:";

    /** Outcome of a lock acquisition attempt. */
    enum Acquisition {
        /** Lock acquired — this node owns the workflow instance. */
        ACQUIRED,
        /** Lock held elsewhere (another node, or already held locally). */
        HELD_ELSEWHERE,
        /** No lock backend configured or backend unavailable — proceed unlocked. */
        NO_BACKEND
    }

    private final @Nullable DistributedLock distributedLock;
    private final String serviceName;
    private final String keyPrefix;
    private final Duration ttl;
    private final Duration renewInterval;
    private final EngineObserver observer;
    private final BackgroundLoopGuard guard;
    private final ConcurrentHashMap<String, LockHandle> heldLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean renewerStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Counted down by {@link #close(Duration)}: ends the renewer's wait between passes at once. */
    private final CountDownLatch stop = new CountDownLatch(1);
    private volatile @Nullable Thread renewerThread;
    private volatile Consumer<String> lockLostCallback = _ -> { };

    /**
     * Registers the callback {@link #renewLoop} invokes with a workflow ID when
     * a renewal reports that its instance lock was lost. Invoked at most once
     * per lost handle, on the renewer thread, through the same containment as
     * the observer callbacks — a throwing callback cannot kill the renewer.
     * Replaces any previously registered callback.
     *
     * @param callback receives the workflow ID whose lock was lost
     */
    void onLockLost(Consumer<String> callback) {
        this.lockLostCallback = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Test seam: builds the (not-yet-started) renewer thread. Package-private
     * and non-final so a test can replace it with a throwing factory to
     * exercise the renewer-start failure path from {@link #tryAcquire}
     * without touching real threads. Defaults to production behaviour — a
     * virtual thread running {@link #renewLoop} — assigned in the
     * constructor (after {@code serviceName} is set) rather than as a field
     * initializer.
     *
     * <p>{@code volatile}: every other piece of mutable state this class
     * shares across threads is already safe for it ({@code heldLocks} is a
     * {@link ConcurrentHashMap}, {@code renewerStarted}/{@code closed} are
     * {@link AtomicBoolean}, {@code renewerThread} is {@code volatile}) — this
     * field was the odd one out, assigned in the constructor and read from
     * {@link #startRenewerIfNeeded()} with no happens-before edge guaranteeing
     * visibility across the many workflow threads that call {@link #tryAcquire}
     * concurrently.
     */
    volatile Supplier<Thread> renewerThreadStarter;

    WorkflowInstanceLockManager(@Nullable DistributedLock distributedLock, String serviceName) {
        this(distributedLock, serviceName, DEFAULT_KEY_PREFIX, DEFAULT_LOCK_TTL, DEFAULT_RENEW_INTERVAL);
    }

    WorkflowInstanceLockManager(
            @Nullable DistributedLock distributedLock,
            String serviceName,
            Duration ttl,
            Duration renewInterval
    ) {
        this(distributedLock, serviceName, DEFAULT_KEY_PREFIX, ttl, renewInterval);
    }

    WorkflowInstanceLockManager(
            @Nullable DistributedLock distributedLock,
            String serviceName,
            String keyPrefix,
            Duration ttl,
            Duration renewInterval
    ) {
        this(distributedLock, serviceName, keyPrefix, ttl, renewInterval, EngineObserver.NOOP);
    }

    /**
     * Creates a lock manager with an {@link EngineObserver}.
     *
     * @param observer engine observation seam — fires
     *                 {@code instanceLockAcquired}, {@code instanceLockRenewFailed}
     *                 and {@code instanceLockLost}; never {@code null} (pass
     *                 {@link EngineObserver#NOOP})
     */
    WorkflowInstanceLockManager(
            @Nullable DistributedLock distributedLock,
            String serviceName,
            String keyPrefix,
            Duration ttl,
            Duration renewInterval,
            EngineObserver observer
    ) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("instance lock ttl must be positive, got " + ttl);
        }
        if (renewInterval == null || renewInterval.isNegative() || renewInterval.isZero()) {
            throw new IllegalArgumentException(
                    "instance lock renewInterval must be positive, got " + renewInterval);
        }
        this.distributedLock = distributedLock;
        this.serviceName = serviceName;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.renewInterval = renewInterval;
        this.observer = observer;
        this.guard = new BackgroundLoopGuard(BackgroundLoopGuard.INSTANCE_LOCK_RENEWER, renewInterval,
                closed::get, observer);
        this.renewerThreadStarter = () -> Thread.ofVirtual()
                .name("maestro-instance-lock-renewer-" + this.serviceName)
                .uncaughtExceptionHandler(guard.uncaughtExceptionHandler())
                .unstarted(this::renewLoop);
    }

    /**
     * Attempts to acquire the instance lock for a workflow.
     *
     * @param workflowId the workflow's business ID
     * @return the acquisition outcome — never throws
     */
    Acquisition tryAcquire(String workflowId) {
        if (distributedLock == null || closed.get()) {
            // After close() the renewer is gone — a lock acquired now would
            // silently expire mid-run, so degrade to unlocked instead
            return Acquisition.NO_BACKEND;
        }
        if (heldLocks.containsKey(workflowId)) {
            // Local completing-vs-relaunching race: treat as held
            return Acquisition.HELD_ELSEWHERE;
        }
        try {
            var handle = distributedLock.tryAcquire(lockKey(workflowId), ttl);
            if (handle.isEmpty()) {
                return Acquisition.HELD_ELSEWHERE;
            }
            heldLocks.put(workflowId, handle.get());
        } catch (Exception e) {
            logger.warn("Instance lock backend unavailable for workflow '{}' — proceeding unlocked: {}",
                    workflowId, e.getMessage());
            return Acquisition.NO_BACKEND;
        }
        // Deliberately outside the backend try: the lock IS held from the
        // heldLocks.put() above, so neither a renewer-start failure nor a
        // throwing observer may be reported as NO_BACKEND — the caller would
        // then skip release() and this node would renew a lock nobody
        // releases, making the workflowId permanently unacquirable here and
        // blocked cluster-wide.
        try {
            try {
                startRenewerIfNeeded();
            } catch (Exception e) {
                // Node-total, not per-lock: no held lock on this node is being
                // renewed right now (all will expire via TTL unless renewal is
                // restarted). startRenewerIfNeeded() resets its latch on
                // failure, so the very next tryAcquire — for this or any other
                // workflowId — retries starting the renewer.
                logger.error("Instance lock renewer failed to start for '{}' — no locks on this node are "
                                + "being renewed until the next acquisition retries the renewer start; "
                                + "until then they expire via TTL: {}",
                        workflowId, e.getMessage(), e);
            }
            emit("instanceLockAcquired", workflowId, () -> observer.instanceLockAcquired(workflowId));
        } catch (RuntimeException | Error e) {
            // An Error (from the renewer start or the observer) reaches the
            // caller instead of ACQUIRED, so no run will ever own — or release
            // — this handle. Kept, the renewer would renew it forever and the
            // workflow would be unacquirable on every node until this one
            // restarts: give it back first, then rethrow (a control-flow
            // Error included). A RuntimeException cannot reach here today
            // (both calls above contain it); caught so that stays true if
            // either changes. A failing release is suppressed on the original.
            try {
                release(workflowId);
            } catch (RuntimeException | Error releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
        return Acquisition.ACQUIRED;
    }

    /**
     * Releases the instance lock for a workflow. Safe to call when no lock
     * is held. Backend failures are tolerated — the TTL cleans up.
     */
    void release(String workflowId) {
        var handle = heldLocks.remove(workflowId);
        if (handle == null || distributedLock == null) {
            return;
        }
        try {
            distributedLock.release(handle);
        } catch (Exception e) {
            logger.warn("Failed to release instance lock for workflow '{}' — TTL will expire it: {}",
                    workflowId, e.getMessage());
        }
    }

    /**
     * Which of {@code workflowIds} have an instance lock currently held by
     * another node — one {@link DistributedLock#heldKeys} read for the whole
     * collection, keyed exactly as {@link #tryAcquire} keys the lock.
     *
     * <p><b>Advisory.</b> The recovery pass uses it only to skip instances
     * without spending a {@code tryAcquire} on each; an id not reported here
     * still goes through {@code tryAcquire}, which remains the authority. Locks
     * this node holds are never reported (they are held here, not elsewhere).
     * With no backend, after {@link #close}, or when the backend throws, the
     * answer is empty — "unknown, try them all" — never an exception.
     * {@code Error}s propagate.
     *
     * @param workflowIds the workflow IDs to look up
     * @return the subset whose lock another node holds; may be empty
     */
    Set<String> heldElsewhere(Collection<String> workflowIds) {
        if (distributedLock == null || closed.get() || workflowIds.isEmpty()) {
            return Set.of();
        }
        var idByKey = new HashMap<String, String>();
        for (var workflowId : workflowIds) {
            if (!heldLocks.containsKey(workflowId)) {
                idByKey.put(lockKey(workflowId), workflowId);
            }
        }
        if (idByKey.isEmpty()) {
            return Set.of();
        }
        Set<String> heldKeys;
        try {
            heldKeys = distributedLock.heldKeys(idByKey.keySet());
        } catch (RuntimeException e) {
            logger.warn("Could not read which of {} instance lock(s) are held elsewhere — every one will "
                    + "be tried: {}", idByKey.size(), e.getMessage());
            return Set.of();
        }
        var held = new HashSet<String>();
        for (var key : heldKeys) {
            var workflowId = idByKey.get(key);
            // Re-checked: this node may have acquired it while the read ran.
            if (workflowId != null && !heldLocks.containsKey(workflowId)) {
                held.add(workflowId);
            }
        }
        return held;
    }

    /** The instance lock key for a workflow — the one derivation of the key format. */
    private String lockKey(String workflowId) {
        return keyPrefix + WORKFLOW_KEY_SEGMENT + workflowId;
    }

    /** Returns whether this manager currently holds the lock for a workflow. */
    boolean isHeld(String workflowId) {
        return heldLocks.containsKey(workflowId);
    }

    /**
     * Whether the renewer thread is alive. It starts with the first lock this
     * manager acquires, so this is {@code false} before then, with no
     * {@link DistributedLock}, and after {@link #close()}.
     *
     * @return {@code true} while the renewer thread runs and the manager is open
     */
    boolean isRenewerRunning() {
        var thread = renewerThread;
        return !closed.get() && thread != null && thread.isAlive();
    }

    /**
     * Whether the renewer thread has been started. Monotonic in practice: it
     * is reset only when starting the thread itself failed, so that the next
     * acquisition retries the start.
     *
     * @return {@code true} once the renewer has been started
     */
    boolean hasRenewerStarted() {
        return renewerStarted.get();
    }

    /**
     * The renewer's current failure streak: consecutive passes whose
     * {@code renewAll} failed or that threw, since the last pass that did not.
     *
     * @return the number of consecutive failed passes
     */
    int renewFailureStreak() {
        return guard.consecutiveFailures();
    }

    /**
     * Stops the renewer thread, waiting for it for at most {@link #CLOSE_JOIN}.
     * Same as {@code close(CLOSE_JOIN)}.
     */
    void close() {
        close(CLOSE_JOIN);
    }

    /**
     * Stops the renewer thread. Still-held locks are left to expire via TTL —
     * their workflow threads may still be mid-activity during shutdown, and
     * TTL expiry is the safe handoff to another node.
     *
     * <p>Never interrupts the renewer (Issue 64): a pass in progress is a
     * backend call — JDBC, for Postgres — and an interrupt on a virtual thread
     * closes its socket and fails the pass. A latch ends the wait between
     * passes at once; a pass in progress finishes, and this call waits for it
     * for at most {@code join}. A pass that fails once the manager is closed
     * counts no renewal failure. If the calling thread is interrupted while it
     * waits, its interrupt status is restored and the call returns as if the
     * wait had timed out. Idempotent: a second call returns at once.
     *
     * @param join how long to wait for the renewer to end; zero or negative
     *             does not wait
     */
    void close(Duration join) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stop.countDown();
        var thread = renewerThread;
        if (thread != null && thread != Thread.currentThread() && join.isPositive()) {
            try {
                thread.join(join);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!heldLocks.isEmpty()) {
            logger.info("{} instance lock(s) still held at close — they will expire via TTL ({})",
                    heldLocks.size(), ttl);
        }
    }

    private void startRenewerIfNeeded() {
        if (closed.get()) {
            return;
        }
        if (renewerStarted.compareAndSet(false, true)) {
            try {
                var thread = renewerThreadStarter.get();
                renewerThread = thread;
                thread.start();
            } catch (RuntimeException | Error e) {
                // Un-latch: if we left renewerStarted=true here, the CAS
                // above would stay burned for the rest of the process's
                // life — every future tryAcquire, for every workflowId,
                // would silently skip starting the renewer, and every lock
                // held on this node (not just this one) would expire via
                // TTL while its workflow kept running. Resetting it lets
                // the next successful acquisition retry the start.
                renewerStarted.set(false);
                throw e;
            }
        }
    }

    private void renewLoop() {
        while (!closed.get()) {
            try {
                if (stop.await(renewInterval.toNanos(), TimeUnit.NANOSECONDS)) {
                    return; // closed
                }
            } catch (InterruptedException e) {
                // Nothing in Maestro interrupts the renewer; whoever did is stopping it.
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                return;
            }
            try {
                if (renewPass()) {
                    guard.succeeded();
                }
            } catch (Throwable t) {
                // Every handle is kept: renewPass removes one only for a loss
                // the backend reported. A control-flow Error is rethrown and
                // ends the renewer; anything else waits one interval.
                guard.failed(t);
            }
        }
    }

    /**
     * Renews every held lock with one {@link DistributedLock#renewAll} call on
     * a snapshot of {@code heldLocks}. A backend failure keeps every handle
     * for the next pass (the TTL tolerates roughly two missed passes); a
     * handle reported lost takes {@link #lockLost}. A pass slower than the
     * renew interval logs one WARN — it is eating into the TTL margin.
     *
     * @return {@code false} when the backend failed (reported through the
     *         guard here), {@code true} otherwise, including a pass with no
     *         held lock
     */
    @SuppressWarnings("DataFlowIssue") // renewer only runs when distributedLock != null
    private boolean renewPass() {
        if (heldLocks.isEmpty()) {
            return true;
        }
        var snapshot = new HashMap<LockHandle, String>();
        heldLocks.forEach((workflowId, handle) -> snapshot.put(handle, workflowId));
        var started = System.nanoTime();
        try {
            Set<LockHandle> lost;
            try {
                lost = distributedLock.renewAll(snapshot.keySet(), ttl);
            } catch (Exception e) {
                // Backend failure — nothing is known, so nothing is lost: keep
                // every handle and retry next pass. Logged through the guard:
                // once loudly, then quietly with a reminder every 5 minutes.
                // Once closed it is a routine shutdown, not an outage: nothing
                // is counted (Issue 64), and the guard logs at DEBUG.
                if (closed.get()) {
                    guard.failed(e);
                    return false;
                }
                snapshot.forEach((_, workflowId) -> emit("instanceLockRenewFailed", workflowId,
                        () -> observer.instanceLockRenewFailed(workflowId)));
                guard.failed(e);
                return false;
            }
            for (var handle : lost) {
                var workflowId = snapshot.get(handle);
                if (workflowId != null) {
                    lockLost(workflowId, handle);
                }
            }
            return true;
        } finally {
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            if (elapsed.compareTo(renewInterval) > 0) {
                logger.warn("Instance lock renewal pass over {} lock(s) took {} ms, longer than the {} ms "
                                + "renew interval — held locks are closer to their {} ms TTL than intended",
                        snapshot.size(), elapsed.toMillis(), renewInterval.toMillis(), ttl.toMillis());
            }
        }
    }

    /**
     * The lost-lock path: drops the handle, logs an ERROR and tells the
     * observer and the executor. Skipped when the handle is no longer the one
     * held for the workflow — it was released (and perhaps re-acquired by a
     * new run) after the pass took its snapshot, so the stale handle's
     * "loss" says nothing about the current run.
     *
     * <p>Once the manager is closed (a renewal still in flight at shutdown
     * that later reports a loss), the node is giving every lock up anyway:
     * the loss is logged at INFO and not reported to the observer, so a
     * routine deploy fires no lost-lock alert. The run is still stood down —
     * one still executing must be fenced.
     */
    private void lockLost(String workflowId, LockHandle handle) {
        if (!heldLocks.remove(workflowId, handle)) {
            return;
        }
        var afterClose = closed.get();
        if (afterClose) {
            logger.info("Instance lock for workflow '{}' was lost after shutdown began — this run is "
                            + "being stood down and the store's lease fence rejects any write it still "
                            + "attempts",
                    workflowId);
        } else {
            logger.error("Instance lock for workflow '{}' was lost — another node may now be "
                            + "executing this workflow; this run is being stood down and the "
                            + "store's lease fence rejects any write it still attempts",
                    workflowId);
        }
        // Emitted outside the backend call so a throwing observer is not
        // reported as a transient renew failure, and contained so it cannot
        // escape renewLoop() and kill the single renewer thread — that would
        // silently stop renewing EVERY held lock.
        try {
            if (!afterClose) {
                emit("instanceLockLost", workflowId, () -> observer.instanceLockLost(workflowId));
            }
        } finally {
            // Proactive stand-down: tell the executor now, so the run stops at
            // its next stop point rather than after a write the store fences.
            // In a finally: the handle is already dropped, so no later pass
            // reports this loss again — an observer Error (which emit lets
            // through, to the renewer's guard) must not skip the stand-down.
            emit("onLockLost", workflowId, () -> lockLostCallback.accept(workflowId));
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
     * either report a held lock as {@code NO_BACKEND} or kill the single
     * renewer thread. {@code RuntimeException} only: {@code Error}s (the
     * engine's control-flow signals) always propagate.
     */
    private void emit(String callback, String workflowId, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException e) {
            logger.warn("EngineObserver.{} threw for workflow '{}' — ignoring: {}",
                    callback, workflowId, e.toString());
        }
    }
}
