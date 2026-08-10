package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * and callers proceed exactly as before this lock existed — the store's
 * unique event index and optimistic instance versioning remain the
 * correctness backstop.
 *
 * <p><b>Lost locks:</b> if a renewal reports lost ownership, the handle is
 * dropped and an ERROR is logged, but the running workflow is not aborted —
 * fencing-token validation in the store is future work.
 *
 * <p><b>Thread safety:</b> all methods are thread-safe.
 */
final class WorkflowInstanceLockManager {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowInstanceLockManager.class);

    static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);
    static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofSeconds(10);
    static final String DEFAULT_KEY_PREFIX = "maestro:lock:";
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
    private final ConcurrentHashMap<String, LockHandle> heldLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean renewerStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile @Nullable Thread renewerThread;

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
        this.renewerThreadStarter = () -> Thread.ofVirtual()
                .name("maestro-instance-lock-renewer-" + this.serviceName)
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
            var handle = distributedLock.tryAcquire(keyPrefix + WORKFLOW_KEY_SEGMENT + workflowId, ttl);
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

    /** Returns whether this manager currently holds the lock for a workflow. */
    boolean isHeld(String workflowId) {
        return heldLocks.containsKey(workflowId);
    }

    /**
     * Stops the renewer thread. Still-held locks are left to expire via TTL —
     * their workflow threads may still be mid-activity during shutdown, and
     * TTL expiry is the safe handoff to another node.
     */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var thread = renewerThread;
        if (thread != null) {
            thread.interrupt();
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
            } catch (Exception e) {
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
                Thread.sleep(renewInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                return;
            }
            for (var entry : heldLocks.entrySet()) {
                renewOne(entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("DataFlowIssue") // renewer only runs when distributedLock != null
    private void renewOne(String workflowId, LockHandle handle) {
        var lost = false;
        try {
            if (!distributedLock.renew(handle, ttl)) {
                logger.error("Instance lock for workflow '{}' was lost — another node may now be "
                                + "executing this workflow; DB constraints remain the "
                                + "duplicate-execution guard",
                        workflowId);
                heldLocks.remove(workflowId, handle);
                lost = true;
            }
        } catch (Exception e) {
            // Transient backend error — keep the handle, retry next cycle
            // (TTL tolerates roughly two missed cycles)
            logger.warn("Failed to renew instance lock for workflow '{}' — will retry: {}",
                    workflowId, e.getMessage());
            emit("instanceLockRenewFailed", workflowId, () -> observer.instanceLockRenewFailed(workflowId));
            return;
        }
        // Same reasoning as tryAcquire: emitted outside the backend try so a
        // throwing observer is not reported as a transient renew failure, and
        // contained so it cannot escape renewLoop() and kill the single
        // renewer thread — that would silently stop renewing EVERY held lock.
        if (lost) {
            emit("instanceLockLost", workflowId, () -> observer.instanceLockLost(workflowId));
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
