package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically re-runs workflow recovery so that instances owned by a node
 * that has since died (or shut down) are picked up without a restart.
 *
 * <p>Startup recovery alone is not enough once per-instance distributed
 * locks exist: during a rolling deploy, node B's startup recovery skips
 * workflows still locked by node A — when A later releases them (or its
 * lock TTL expires after a crash), only a periodic retry can adopt them.
 *
 * <p><b>No leader election:</b> every node polls. Safety comes from the
 * instance lock (cross-node), the executor's in-JVM running-workflow check,
 * and idempotent replay; all-node polling is what makes orphan pickup fast.
 *
 * <p><b>Thread safety:</b> {@link #start()} and {@link #stop()} are
 * thread-safe; the loop runs on a dedicated virtual thread.
 *
 * @see WorkflowExecutor#recoverWorkflows(Map)
 */
final class RecoveryPoller {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryPoller.class);

    private final WorkflowExecutor executor;
    private final Map<String, WorkflowRegistration> registrations;
    private final String serviceName;
    private final Duration pollInterval;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile @Nullable Thread pollerThread;

    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval
    ) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
    }

    /**
     * Starts the polling loop on a virtual thread.
     *
     * @throws IllegalStateException if already started
     */
    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Recovery poller already started");
        }
        running = true;
        pollerThread = Thread.ofVirtual()
                .name("maestro-recovery-poller-" + serviceName)
                .start(this::pollLoop);
        logger.info("Recovery poller started (interval={}, service={})", pollInterval, serviceName);
    }

    /**
     * Stops the polling loop. After stopping, the poller cannot be restarted.
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
        logger.info("Recovery poller stopped");
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
        while (running) {
            try {
                Thread.sleep(pollInterval);
                if (!running) {
                    break;
                }
                var recovered = executor.recoverWorkflows(registrations);
                if (recovered > 0) {
                    logger.info("Recovery poller adopted {} orphaned workflow(s)", recovered);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Don't let one error kill the poller
                logger.error("Recovery polling error — will retry next cycle", e);
            }
        }
        logger.debug("Recovery poller loop exited");
    }
}
