package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ConcurrentHashMap<String, LockHandle> heldLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean renewerStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile @Nullable Thread renewerThread;

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
        this.distributedLock = distributedLock;
        this.serviceName = serviceName;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.renewInterval = renewInterval;
    }

    /**
     * Attempts to acquire the instance lock for a workflow.
     *
     * @param workflowId the workflow's business ID
     * @return the acquisition outcome — never throws
     */
    Acquisition tryAcquire(String workflowId) {
        if (distributedLock == null) {
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
            startRenewerIfNeeded();
            return Acquisition.ACQUIRED;
        } catch (Exception e) {
            logger.warn("Instance lock backend unavailable for workflow '{}' — proceeding unlocked: {}",
                    workflowId, e.getMessage());
            return Acquisition.NO_BACKEND;
        }
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
        if (renewerStarted.compareAndSet(false, true)) {
            var thread = Thread.ofVirtual()
                    .name("maestro-instance-lock-renewer-" + serviceName)
                    .unstarted(this::renewLoop);
            renewerThread = thread;
            thread.start();
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
        try {
            if (!distributedLock.renew(handle, ttl)) {
                logger.error("Instance lock for workflow '{}' was lost — another node may now be "
                                + "executing this workflow; DB constraints remain the "
                                + "duplicate-execution guard",
                        workflowId);
                heldLocks.remove(workflowId, handle);
            }
        } catch (Exception e) {
            // Transient backend error — keep the handle, retry next cycle
            // (TTL tolerates roughly two missed cycles)
            logger.warn("Failed to renew instance lock for workflow '{}' — will retry: {}",
                    workflowId, e.getMessage());
        }
    }
}
