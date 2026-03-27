package io.maestro.core.engine;

import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background poller that scans for due timers and fires them.
 *
 * <p>Runs on a single virtual thread, polling at a configurable interval.
 * Uses distributed leader election to ensure only one poller per service
 * cluster is active at any time.
 *
 * <h2>Leader Election</h2>
 * <p>If a {@link DistributedLock} is configured, the poller calls
 * {@link DistributedLock#trySetLeader(String, String, Duration)} on each
 * iteration with key {@code maestro:leader:timer-poller:{serviceName}}
 * and 15-second TTL. The implementation should return {@code true} both
 * when newly elected and when the candidate is already the current leader
 * (effectively renewing the TTL). If the lock backend is unavailable
 * ({@code null}), the poller runs unconditionally (single-node mode).
 *
 * <h2>Idempotency</h2>
 * <p>Duplicate timer fires are safe:
 * <ul>
 *   <li>{@link WorkflowExecutor#fireTimer} marks the timer as {@code FIRED}
 *       before unparking — a second fire finds it already transitioned.</li>
 *   <li>{@link ParkingLot#unpark} is a no-op if no thread is parked.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #start()} and {@link #stop()} may be called from any thread.
 * The polling loop runs on its own virtual thread. Concurrent {@code start()}
 * calls are guarded by an {@link AtomicBoolean}.
 *
 * @see TimerManager
 * @see WorkflowExecutor#fireTimer(String, String, UUID)
 */
final class TimerPoller {

    private static final Logger logger = LoggerFactory.getLogger(TimerPoller.class);
    private static final Duration LEADER_TTL = Duration.ofSeconds(15);
    private static final String LEADER_KEY_PREFIX = "maestro:leader:timer-poller:";

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final @Nullable DistributedLock distributedLock;
    private final String serviceName;
    private final Duration pollInterval;
    private final int batchSize;
    private final String candidateId;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile @Nullable Thread pollerThread;

    /**
     * Creates a new timer poller.
     *
     * @param store           workflow store for timer queries
     * @param executor        workflow executor for firing timers
     * @param distributedLock optional lock backend for leader election
     *                        ({@code null} for single-node mode)
     * @param serviceName     the owning service name (for leader election key)
     * @param pollInterval    interval between polling cycles
     * @param batchSize       maximum timers to process per cycle
     */
    TimerPoller(
            WorkflowStore store,
            WorkflowExecutor executor,
            @Nullable DistributedLock distributedLock,
            String serviceName,
            Duration pollInterval,
            int batchSize
    ) {
        this.store = store;
        this.executor = executor;
        this.distributedLock = distributedLock;
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.candidateId = UUID.randomUUID().toString();
    }

    /**
     * Starts the polling loop on a virtual thread.
     *
     * @throws IllegalStateException if already started
     */
    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Timer poller already started");
        }
        running = true;
        pollerThread = Thread.ofVirtual()
                .name("maestro-timer-poller-" + serviceName)
                .start(this::pollLoop);
        logger.info("Timer poller started (interval={}, batchSize={}, service={})",
                pollInterval, batchSize, serviceName);
    }

    /**
     * Stops the polling loop.
     *
     * <p>Interrupts the poller thread and waits up to 5 seconds for it
     * to terminate. After stopping, the poller cannot be restarted.
     */
    void stop() {
        running = false;
        var thread = pollerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pollerThread = null;
        logger.info("Timer poller stopped");
    }

    /**
     * Returns whether the poller is currently running.
     *
     * @return {@code true} if the poller is active
     */
    boolean isRunning() {
        var thread = pollerThread;
        return running && thread != null && thread.isAlive();
    }

    // ── Internal: polling loop ──────────────────────────────────────────

    private void pollLoop() {
        logger.debug("Timer poller loop started on thread '{}'", Thread.currentThread().getName());
        while (running) {
            try {
                if (isLeader()) {
                    var fired = pollDueTimers();
                    if (fired > 0) {
                        logger.debug("Fired {} due timer(s)", fired);
                    }
                }
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Timer poller interrupted, exiting loop");
                break;
            } catch (Exception e) {
                logger.error("Timer polling error — will retry next cycle", e);
                // Don't let one error kill the poller
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.debug("Timer poller loop exited");
    }

    private int pollDueTimers() {
        var dueTimers = store.getDueTimers(Instant.now(), batchSize);
        var fired = 0;

        for (var timer : dueTimers) {
            try {
                executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
                fired++;
            } catch (Exception e) {
                logger.error("Failed to fire timer {} (workflowId='{}', timerId='{}')",
                        timer.id(), timer.workflowId(), timer.timerId(), e);
                // Continue with remaining timers — don't let one failure stop the batch
            }
        }

        return fired;
    }

    // ── Internal: leader election ───────────────────────────────────────

    /**
     * Checks whether this instance is the timer poller leader.
     *
     * <p>Uses {@link DistributedLock#trySetLeader} which serves as both
     * initial election and TTL renewal — the implementation returns
     * {@code true} if this candidate is (or becomes) the leader.
     */
    private boolean isLeader() {
        if (distributedLock == null) {
            return true; // No lock backend — single-node mode, always poll
        }

        var key = LEADER_KEY_PREFIX + serviceName;
        try {
            var elected = distributedLock.trySetLeader(key, candidateId, LEADER_TTL);
            if (!elected) {
                logger.debug("Another instance holds timer poller leadership for service '{}'", serviceName);
            }
            return elected;
        } catch (Exception e) {
            logger.warn("Leader election failed for service '{}' — skipping this poll cycle", serviceName, e);
            return false;
        }
    }
}
