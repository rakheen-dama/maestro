package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
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
 * <p><b>Failures:</b> a pass that throws — a store fault, or any
 * {@code Error} but a {@code MaestroControlFlowError} — is contained by the
 * loop's {@link BackgroundLoopGuard}, and the next pass runs after the loop's
 * own {@code pollInterval} (60 s by default). A failure streak logs one
 * {@code ERROR} with the stack trace, then {@code DEBUG} with an {@code ERROR}
 * reminder every 5 minutes while it continues, and one {@code INFO} when a
 * pass succeeds again.
 *
 * <p><b>Thread safety:</b> {@link #start()} and {@link #stop()} are
 * thread-safe; the loop runs on a dedicated virtual thread.
 *
 * @see WorkflowExecutor#recoverWorkflows(Map)
 */
final class RecoveryPoller implements StoppableLoop {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryPoller.class);

    /** How long {@link #stop()} waits for the loop's thread to end. */
    static final Duration STOP_JOIN = Duration.ofSeconds(5);

    private final WorkflowExecutor executor;
    private final Map<String, WorkflowRegistration> registrations;
    private final String serviceName;
    private final Duration pollInterval;
    private final BackgroundLoopGuard guard;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile @Nullable Thread pollerThread;

    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval
    ) {
        this(executor, registrations, serviceName, pollInterval, EngineObserver.NOOP);
    }

    /**
     * Creates a recovery poller that reports contained {@code Error}s.
     *
     * @param executor      the executor whose recovery pass this repeats
     * @param registrations workflow type → registration, as for {@link WorkflowExecutor#recoverWorkflows(Map)}
     * @param serviceName   the owning service name (thread name)
     * @param pollInterval  interval between passes; must be positive
     * @param observer      receives {@link EngineObserver#backgroundLoopError}
     *                      for every {@code Error} the loop contains
     */
    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval,
            EngineObserver observer
    ) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
        this.guard = new BackgroundLoopGuard(BackgroundLoopGuard.RECOVERY_POLLER, pollInterval, () -> !running,
                observer);
    }

    /**
     * Starts the polling loop on a virtual thread.
     *
     * @throws IllegalStateException if already started
     */
    synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Recovery poller already started");
        }
        running = true;
        pollerThread = Thread.ofVirtual()
                .name("maestro-recovery-poller-" + serviceName)
                .uncaughtExceptionHandler(guard.uncaughtExceptionHandler())
                .start(this::pollLoop);
        logger.info("Recovery poller started (interval={}, service={})", pollInterval, serviceName);
    }

    /**
     * Stops the polling loop: {@link #requestStop()}, then
     * {@link #awaitStopped(Duration) awaitStopped(STOP_JOIN)} — waits up to
     * {@link #STOP_JOIN} (5 seconds) for the thread to end. After stopping,
     * the poller cannot be restarted.
     */
    void stop() {
        requestStop();
        awaitStopped(STOP_JOIN);
    }

    @Override
    public String loopName() {
        return BackgroundLoopGuard.RECOVERY_POLLER;
    }

    /**
     * Asks the polling loop to stop and returns at once: no new iteration starts,
     * and the thread is interrupted (Issue 64 tracks that interrupt, which
     * can land inside a store call). Never joins. Idempotent.
     */
    @Override
    public synchronized void requestStop() {
        running = false;
        var thread = pollerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Waits at most {@code bound} for the polling loop's thread to end. Once it
     * has ended, logs that the poller stopped (once) and forgets the thread.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the thread has ended or was never started,
     *         {@code false} if it is still running — nothing is logged then
     */
    @Override
    public boolean awaitStopped(Duration bound) {
        var thread = pollerThread;
        if (thread == null) {
            return true;
        }
        try {
            thread.join(bound);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            return false;
        }
        synchronized (this) {
            if (pollerThread == thread) {
                pollerThread = null;
                logger.info("Recovery poller stopped");
            }
        }
        return true;
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

    /**
     * The loop's current failure streak (failed passes since the last
     * successful one). Safe to call from any thread.
     *
     * @return the number of consecutive failed passes
     */
    int consecutiveFailures() {
        return guard.consecutiveFailures();
    }

    // ── Internal: polling loop ──────────────────────────────────────────

    private void pollLoop() {
        while (running) {
            try {
                Thread.sleep(pollInterval);
                if (!running) {
                    break;
                }
                var recovered = executor.recoveryPass(registrations).adopted();
                guard.succeeded();
                if (recovered > 0) {
                    logger.info("Recovery poller adopted {} orphaned workflow(s)", recovered);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Rethrows a MaestroControlFlowError (ending the loop);
                // contains and logs everything else. The next pass waits one
                // pollInterval at the top of the loop.
                guard.failed(t);
            }
        }
        logger.debug("Recovery poller loop exited");
    }
}
