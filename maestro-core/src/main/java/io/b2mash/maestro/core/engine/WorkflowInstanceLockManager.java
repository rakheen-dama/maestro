package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

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
 * <p><b>Mandatory:</b> a {@link DistributedLock} is required (pilot
 * readiness P3). When the backend throws, or after {@link #close()},
 * acquisition reports {@link Acquisition#UNAVAILABLE} and the executor does
 * not launch the run: no run executes without holding its instance lock. The
 * lock is an <em>admission</em> guard — it stops a second node from starting a
 * redundant run in the first place; it is not what keeps a stale run from
 * persisting progress. That is the store's lease fencing (Issue 11): every
 * adoption bumps the instance row's {@code lease_epoch}, and every run-scoped
 * write is validated against the epoch the run adopted at, so a run whose lock
 * expired cannot write once another node has adopted the workflow.
 *
 * <p><b>Lost locks:</b> if a renewal reports lost ownership, the handle is
 * dropped, an ERROR is logged and the {@link #onLockLost} callback is invoked,
 * which the executor uses to mark the run stale so it stands down at its next
 * stop point instead of first trying a write the store would fence anyway.
 * The callback is proactive courtesy, not the guarantee — the fence holds
 * even when the renewer notices nothing (a frozen JVM notices only after it
 * thaws). An {@code Error} in a pass is contained by the renewer's
 * {@link LoopSupervisor}: every handle is kept and the next pass runs one
 * interval later.
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
    /** How long {@link #close()} waits for the renewer to end; matches the wake listener's bound (WP10). */
    static final Duration CLOSE_JOIN = Duration.ofSeconds(2);
    private static final String WORKFLOW_KEY_SEGMENT = "workflow:";

    /** Outcome of a lock acquisition attempt. */
    enum Acquisition {
        /** Lock acquired — this node owns the workflow instance. */
        ACQUIRED,
        /** Lock held elsewhere (another node, or already held locally). */
        HELD_ELSEWHERE,
        /**
         * The backend threw, or this manager is closed — nothing is held and
         * the caller must not run the workflow (it is left for recovery).
         */
        UNAVAILABLE
    }

    private final DistributedLock distributedLock;
    private final String serviceName;
    private final String keyPrefix;
    private final Duration ttl;
    private final Duration renewInterval;
    private final CompositeEngineObserver observer;
    private final ConcurrentHashMap<String, LockHandle> heldLocks = new ConcurrentHashMap<>();
    /**
     * When each held lock was last renewed — or, before its first renewal,
     * when its acquisition began — on {@link System#nanoTime()}: the oldest
     * entry is how close the node is to losing a lock it believes it holds
     * (PR #76 review). One entry per handle in {@link #heldLocks}.
     */
    private final ConcurrentHashMap<LockHandle, Long> renewedAt = new ConcurrentHashMap<>();
    /**
     * Makes {@link #close} idempotent. Not the stop state: that is the
     * renewer's own {@link LoopSupervisor#stopRequested()} (PR #76 review), so
     * failure reporting and the supervisor's count read one flag.
     */
    private final AtomicBoolean closeCalled = new AtomicBoolean(false);
    /** The renewer, started with this manager (P2 task 4) and stopped only by {@link #close(Duration)}. */
    private final LoopSupervisor renewer;
    private volatile BiConsumer<String, LockHandle> lockLostCallback = (_, _) -> { };

    /**
     * Registers the callback the renewer invokes with a workflow ID and
     * the lost handle when a renewal reports that an instance lock was lost.
     * Invoked at most once per lost handle, on the renewer thread; a
     * {@code RuntimeException} from it is contained, so a throwing callback
     * cannot end the renewal pass. The handle tells the caller <em>which</em> run lost it:
     * a newer run of the same workflow ID holds a different handle (L1).
     * Replaces any previously registered callback.
     *
     * @param callback receives the workflow ID and the handle that was lost
     */
    void onLockLost(BiConsumer<String, LockHandle> callback) {
        this.lockLostCallback = Objects.requireNonNull(callback, "callback");
    }

    /** The renewer's fixed loop name: its meter tag and log name. */
    static final String RENEWER_LOOP_NAME = "instance-lock-renewer";

    WorkflowInstanceLockManager(DistributedLock distributedLock, String serviceName) {
        this(distributedLock, serviceName, DEFAULT_KEY_PREFIX, DEFAULT_LOCK_TTL, DEFAULT_RENEW_INTERVAL);
    }

    WorkflowInstanceLockManager(
            DistributedLock distributedLock,
            String serviceName,
            Duration ttl,
            Duration renewInterval
    ) {
        this(distributedLock, serviceName, DEFAULT_KEY_PREFIX, ttl, renewInterval);
    }

    WorkflowInstanceLockManager(
            DistributedLock distributedLock,
            String serviceName,
            String keyPrefix,
            Duration ttl,
            Duration renewInterval
    ) {
        this(distributedLock, serviceName, keyPrefix, ttl, renewInterval, EngineObserver.NOOP);
    }

    /**
     * Creates a lock manager with an {@link EngineObserver} and starts its
     * renewer.
     *
     * @param distributedLock the lock backend; never {@code null}
     * @param observer engine observation seam — fires
     *                 {@code instanceLockAcquired}, {@code instanceLockRenewFailed}
     *                 and {@code instanceLockLost}; never {@code null} (pass
     *                 {@link EngineObserver#NOOP})
     */
    WorkflowInstanceLockManager(
            DistributedLock distributedLock,
            String serviceName,
            String keyPrefix,
            Duration ttl,
            Duration renewInterval,
            EngineObserver observer
    ) {
        this(distributedLock, serviceName, keyPrefix, ttl, renewInterval, observer, LoopStarter.DEFAULT);
    }

    /**
     * Creates a lock manager and starts its renewer with {@code renewerStarter}
     * (a test seam). The renewer starts here, with the manager — the
     * executor's build — not with the first acquisition (P2 task 4, Issue 68
     * part 1): a renewer that cannot start fails this constructor, and so the
     * build, instead of leaving held locks unrenewed.
     *
     * @param renewerStarter starts the renewer's supervisor
     * @throws RuntimeException what {@code renewerStarter} throws
     */
    WorkflowInstanceLockManager(
            DistributedLock distributedLock,
            String serviceName,
            String keyPrefix,
            Duration ttl,
            Duration renewInterval,
            EngineObserver observer,
            LoopStarter renewerStarter
    ) {
        Objects.requireNonNull(distributedLock, "distributedLock");
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
        // Contained once, here (Issue 81): a throwing observer can neither
        // report a held lock as UNAVAILABLE nor end a renewal pass early.
        this.observer = CompositeEngineObserver.contain(observer);
        // Last: every field the renewer reads is set. The same composite as
        // this manager's (PR #74 review): one containment count per observer.
        this.renewer = renewerStarter.start(renewerSpec(), this::renewPass, this.observer);
    }

    /**
     * How the renewer is supervised: critical; it sleeps first, then renews
     * every {@code renewInterval}, failed or not; failing after two failed
     * passes in a row (a health reading also looks at held locks; see
     * {@code docs/operations.md} §14).
     */
    private LoopSpec renewerSpec() {
        return LoopSpec.of(RENEWER_LOOP_NAME, "maestro-instance-lock-renewer-" + serviceName, LoopRole.CRITICAL,
                        renewInterval)
                .withInitialDelay(renewInterval)
                .withFailing(FailingRule.streak(2));
    }

    /**
     * The outcome of one {@link #acquire}: the handle is the one <em>this</em>
     * acquisition took — never looked up again by workflow ID, so a launch whose
     * handle is later reported lost cannot pick up a newer acquisition's handle
     * (PR #72 review).
     *
     * @param outcome what happened
     * @param handle  the handle taken; non-{@code null} exactly when
     *                {@code outcome} is {@link Acquisition#ACQUIRED}
     */
    record Acquired(Acquisition outcome, @Nullable LockHandle handle) {

        private static final Acquired HELD_ELSEWHERE = new Acquired(Acquisition.HELD_ELSEWHERE, null);
        private static final Acquired UNAVAILABLE = new Acquired(Acquisition.UNAVAILABLE, null);
    }

    /**
     * Attempts to acquire the instance lock for a workflow; the outcome only.
     * Same as {@code acquire(workflowId).outcome()}. A launch uses
     * {@link #acquire}, which also returns the handle it must carry.
     *
     * @param workflowId the workflow's business ID
     * @return the acquisition outcome — never throws
     */
    Acquisition tryAcquire(String workflowId) {
        return acquire(workflowId).outcome();
    }

    /**
     * Attempts to acquire the instance lock for a workflow, returning the
     * outcome together with the handle this attempt took.
     *
     * @param workflowId the workflow's business ID
     * @return the outcome and, when {@link Acquisition#ACQUIRED}, this
     *         acquisition's handle — never throws, but an {@code Error} from
     *         the renewer start or the observer propagates after this
     *         attempt's own handle was given back
     */
    Acquired acquire(String workflowId) {
        if (renewer.stopRequested()) {
            // After close() the renewer is gone — a lock acquired now would
            // silently expire mid-run, so nothing is acquired and the caller
            // does not launch
            return Acquired.UNAVAILABLE;
        }
        if (heldLocks.containsKey(workflowId)) {
            // Local completing-vs-relaunching race: treat as held
            return Acquired.HELD_ELSEWHERE;
        }
        LockHandle handle;
        // Taken before the backend call: the lock's TTL runs from no earlier
        // than this, so its age is never under-stated.
        var attemptStart = System.nanoTime();
        try {
            var acquired = distributedLock.tryAcquire(lockKey(workflowId), ttl);
            if (acquired.isEmpty()) {
                return Acquired.HELD_ELSEWHERE;
            }
            handle = acquired.get();
            // Conditional (PR #72 review): a grant that arrived late — this
            // attempt paused after the backend granted it, its grant expired,
            // and a newer acquisition published its own handle meanwhile — must
            // not overwrite the newer handle. It is rejected below, and gives
            // back only its own backend token.
            renewedAt.put(handle, attemptStart);
            if (heldLocks.putIfAbsent(workflowId, handle) != null) {
                renewedAt.remove(handle);
                releaseLateGrant(workflowId, handle);
                return Acquired.HELD_ELSEWHERE;
            }
        } catch (Exception e) {
            logger.warn("Instance lock backend unavailable for workflow '{}' — not running it now; recovery "
                    + "retries it once the lock can be taken: {}", workflowId, e.getMessage());
            return Acquired.UNAVAILABLE;
        }
        // Deliberately outside the backend try: the lock IS held from the
        // heldLocks.putIfAbsent() above, so a throwing observer may not be
        // reported as UNAVAILABLE — the caller would then skip release() and
        // this node would renew a lock nobody releases, making the workflowId
        // permanently unacquirable here and blocked cluster-wide. (The renewer
        // runs already: it starts with this manager.)
        try {
            observer.instanceLockAcquired(workflowId);
        } catch (RuntimeException | Error e) {
            // A control-flow or VM error from the observer (the composite
            // contains everything else) reaches the caller instead of ACQUIRED,
            // so no run will ever own — or release — this handle. Kept, the renewer would renew it forever and the
            // workflow would be unacquirable on every node until this one
            // restarts: give it back first, then rethrow (a control-flow
            // Error included). A RuntimeException cannot reach here today
            // (the composite contains it); caught so that stays true if that
            // changes. A failing release is suppressed on the original.
            // This attempt's own handle: if it was reported lost meanwhile and
            // another launch took the lock, the newer handle is not ours.
            try {
                release(workflowId, handle);
            } catch (RuntimeException | Error releaseFailure) {
                WorkflowExecutor.suppress(e, releaseFailure);
            }
            throw e;
        }
        return new Acquired(Acquisition.ACQUIRED, handle);
    }

    /**
     * Gives back a backend grant that lost the race to publish (see
     * {@link #acquire}): only its own token, which the backend releases only
     * if it still holds the key — never the newer, published handle.
     */
    private void releaseLateGrant(String workflowId, LockHandle handle) {
        try {
            distributedLock.release(handle);
        } catch (Exception e) {
            logger.warn("Failed to give back a late instance-lock grant for workflow '{}' — TTL will expire it: {}",
                    workflowId, e.getMessage());
        }
    }

    /**
     * Whether {@code handle} is still the lock this manager holds for the workflow.
     *
     * @param workflowId the workflow's business ID
     * @param handle     the handle a run acquired; {@code null} answers {@code false}
     * @return {@code true} while that exact handle is held
     */
    boolean holds(String workflowId, @Nullable LockHandle handle) {
        return handle != null && handle.equals(heldLocks.get(workflowId));
    }

    /**
     * Releases a run's own instance lock: only if {@code handle} is still the
     * one held for the workflow. A run whose lock was reported lost (the
     * handle already dropped), or replaced by a newer run's acquisition, releases
     * nothing — it must never remove or delete a newer run's lock (L1). Safe to
     * call with {@code null}. Backend failures are tolerated — the TTL cleans up.
     *
     * @param workflowId the workflow's business ID
     * @param handle     the handle the run acquired, or {@code null} for none
     */
    void release(String workflowId, @Nullable LockHandle handle) {
        if (handle == null || !heldLocks.remove(workflowId, handle)) {
            return;
        }
        renewedAt.remove(handle);
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
     * After {@link #close}, or when the backend throws, the answer is empty — "unknown, try them all" — never an exception.
     * {@code Error}s propagate.
     *
     * @param workflowIds the workflow IDs to look up
     * @return the subset whose lock another node holds; may be empty
     */
    Set<String> heldElsewhere(Collection<String> workflowIds) {
        if (renewer.stopRequested() || workflowIds.isEmpty()) {
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
    String lockKey(String workflowId) {
        return keyPrefix + WORKFLOW_KEY_SEGMENT + workflowId;
    }

    /** Returns whether this manager currently holds the lock for a workflow. */
    boolean isHeld(String workflowId) {
        return heldLocks.containsKey(workflowId);
    }

    /**
     * Whether the renewer is running: it starts with this manager, so this is
     * {@code true} from construction until {@link #close()} or a death.
     *
     * @return {@code true} while the renewer runs and the manager is open
     */
    boolean isRenewerRunning() {
        return renewer.snapshot().running();
    }

    /**
     * The renewer's snapshot, for health and {@code WorkflowExecutor.loops()}.
     *
     * @return the renewer's current snapshot
     */
    LoopSnapshot renewerSnapshot() {
        return renewer.snapshot();
    }

    /**
     * How many instance locks this manager holds right now.
     *
     * @return the number of held locks
     */
    int heldCount() {
        return heldLocks.size();
    }

    /**
     * How long the held lock closest to expiry has gone without renewal: since
     * its last successful renewal or, if it has had none, since its
     * acquisition began. Monotonic; a failed renewal attempt resets nothing.
     * Health reads the node {@code DOWN} once this reaches the lock TTL — every
     * lock that old has expired (PR #76 review).
     *
     * @return that age, or {@code null} when no lock is held
     */
    @Nullable Duration oldestRenewalAge() {
        var now = System.nanoTime();
        Long oldest = null;
        for (var at : renewedAt.values()) {
            if (oldest == null || at - oldest < 0) {
                oldest = at;
            }
        }
        return oldest == null ? null : Duration.ofNanos(now - oldest);
    }

    /**
     * The renewer's current failure streak: consecutive passes whose
     * {@code renewAll} failed or that threw, since the last pass that did not.
     *
     * @return the number of consecutive failed passes
     */
    int renewFailureStreak() {
        return renewer.snapshot().consecutiveFailures();
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
        if (!closeCalled.compareAndSet(false, true)) {
            return;
        }
        // The stop state itself (PR #76 review): from its first instruction,
        // acquire() refuses, a failed pass is neither reported to the observer
        // nor counted by the supervisor — one flag, no window between them.
        renewer.requestStop();
        if (join.isPositive()) {
            renewer.awaitStopped(join);
        }
        if (!heldLocks.isEmpty()) {
            logger.info("{} instance lock(s) still held at close — they will expire via TTL ({})",
                    heldLocks.size(), ttl);
        }
    }

    /**
     * Renews every held lock with one {@link DistributedLock#renewAll} call on
     * a snapshot of {@code heldLocks}. A backend failure keeps every handle
     * for the next pass (the TTL tolerates roughly two missed passes); a
     * handle reported lost takes {@link #lockLost}. A pass slower than the
     * renew interval logs one WARN — it is eating into the TTL margin.
     *
     * <p>The renewer's iteration: a backend failure is reported to the
     * observer for every held lock and then thrown, so the supervisor counts
     * the failed pass (once the manager is closed it is a routine shutdown:
     * nothing is reported, and the supervisor does not count a failure while
     * stopping).
     */
    private Next renewPass(LoopContext ctx) throws Exception {
        if (heldLocks.isEmpty()) {
            return Next.afterPeriod();
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
                // every handle and retry next pass. The supervisor logs it
                // once loudly, then quietly with a reminder every 5 minutes.
                if (!ctx.stopRequested()) {
                    snapshot.forEach((_, workflowId) -> observer.instanceLockRenewFailed(workflowId));
                }
                throw e;
            }
            // Renewed from the pass's start (an under-statement of the TTL
            // left): every handle the backend did not report lost. One
            // released meanwhile has no entry and gets none.
            for (var handle : snapshot.keySet()) {
                if (!lost.contains(handle)) {
                    renewedAt.replace(handle, started);
                }
            }
            for (var handle : lost) {
                var workflowId = snapshot.get(handle);
                if (workflowId != null) {
                    lockLost(workflowId, handle, ctx.stopRequested());
                }
            }
            return Next.afterPeriod();
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
    private void lockLost(String workflowId, LockHandle handle, boolean afterClose) {
        if (!heldLocks.remove(workflowId, handle)) {
            return;
        }
        renewedAt.remove(handle);
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
        // Proactive stand-down first: tell the executor now, so the run stops
        // at its next stop point rather than after a write the store fences —
        // and before anyone observing the loss can act on it (a run that has
        // not entered the workflow method yet never runs user code).
        notifyLockLost(workflowId, handle);
        // Then the report, outside the backend call so a throwing observer is
        // not reported as a transient renew failure. The observer is contained
        // (Issue 81), so an observer's Error no longer leaves renewPass's loop
        // over the lost handles: each one is handled in the same pass (68.2).
        if (!afterClose) {
            observer.instanceLockLost(workflowId);
        }
    }

    /**
     * Runs the executor's lost-lock callback, containing a
     * {@code RuntimeException} from it: engine code, not an observer, but an
     * escape would end the renewal pass before the remaining lost handles.
     */
    private void notifyLockLost(String workflowId, LockHandle handle) {
        try {
            lockLostCallback.accept(workflowId, handle);
        } catch (RuntimeException e) {
            logger.warn("The lost-lock callback threw for workflow '{}' — ignoring: {}", workflowId, e.toString());
        }
    }
}
