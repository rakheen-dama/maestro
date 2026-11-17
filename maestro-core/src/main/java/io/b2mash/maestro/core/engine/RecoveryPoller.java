package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * <h2>Admission</h2>
 * <p>Every pass is gated (Issue 30): before it locks an instance it asks the
 * task-queue governor for a slot without blocking, so this node locks only
 * what it can run now or queue — its free slots plus, per queue, at most the
 * queue's workflow concurrency of recovered runs waiting in the same
 * first-in-first-out line as fresh starts, where they never starve. Every
 * other instance is deferred: left unlocked, for a peer or for this node once
 * capacity frees.
 *
 * <h2>Cadence</h2>
 * <p>The first pass runs as soon as the poller starts. A <em>full</em> pass,
 * from the first row, then runs every {@code pollInterval}. After a pass that
 * deferred, the poller waits on the governor's {@link CapacitySignal} — read
 * before the pass, so a release during it is not missed — and runs a
 * <em>capacity pass</em> as soon as a slot or a recovery ticket frees, at most
 * once per {@code capacitySpacing} (by default {@code min(1 s, pollInterval)}).
 * A capacity pass resumes at the first row the previous pass deferred and
 * reads only what the free capacity can adopt; a wake with no free capacity
 * makes no statement and goes back to waiting. A pass that deferred nothing
 * waits for the next full pass. A stop wakes every wait at once; it does not
 * need the interrupt.
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
    private final CapacitySignal signal;
    private final Duration capacitySpacing;
    private final BackgroundLoopGuard guard;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** Counted down by {@link #requestStop()}: ends every wait of the loop without an interrupt. */
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private volatile boolean running;
    private volatile @Nullable Thread pollerThread;

    /**
     * Test seam: whether {@link #requestStop()} also interrupts the loop's
     * thread. A test clears it to show that a stop ends every wait without the
     * interrupt. Package-private; production never assigns it.
     */
    volatile boolean interruptOnStop = true;

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
        this(executor, registrations, serviceName, pollInterval, observer,
                executor.taskQueueGovernor().capacitySignal(), defaultCapacitySpacing(pollInterval));
    }

    /**
     * Creates a recovery poller with an explicit capacity signal and spacing.
     *
     * @param executor        the executor whose gated passes this runs
     * @param registrations   workflow type → registration, as for {@link WorkflowExecutor#recoverWorkflows(Map)}
     * @param serviceName     the owning service name (thread name)
     * @param pollInterval    interval between full passes; must be positive
     * @param observer        receives {@link EngineObserver#backgroundLoopError}
     *                        for every {@code Error} the loop contains
     * @param signal          the executor's governor's capacity signal
     * @param capacitySpacing the least time between the starts of two passes
     *                        when the second is a capacity pass; must be positive
     */
    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval,
            EngineObserver observer,
            CapacitySignal signal,
            Duration capacitySpacing
    ) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        if (capacitySpacing == null || capacitySpacing.isNegative() || capacitySpacing.isZero()) {
            throw new IllegalArgumentException("capacitySpacing must be positive, got " + capacitySpacing);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
        this.signal = signal;
        this.capacitySpacing = capacitySpacing;
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

    /** MD-4: the default spacing of capacity passes, {@code min(1 s, pollInterval)}. */
    static Duration defaultCapacitySpacing(@Nullable Duration pollInterval) {
        var oneSecond = Duration.ofSeconds(1);
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            return oneSecond; // the constructor rejects the interval itself
        }
        return pollInterval.compareTo(oneSecond) < 0 ? pollInterval : oneSecond;
    }

    /**
     * Asks the polling loop to stop and returns at once: no new iteration starts,
     * every wait between passes ends, and the thread is interrupted (Issue 64
     * tracks that interrupt, which can land inside a store call). Never joins.
     * Idempotent.
     */
    @Override
    public synchronized void requestStop() {
        running = false;
        stopRequested.countDown();
        signal.signal(); // wakes a wait for capacity without the interrupt
        var thread = pollerThread;
        if (thread != null && interruptOnStop) {
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
        var pollNanos = pollInterval.toNanos();
        var first = true;
        var nextFull = System.nanoTime(); // pass first
        var lastPassStart = nextFull;
        var awaitingCapacity = false;
        var seen = 0L;
        @Nullable RecoveryCursor resumeAfter = null;
        Set<String> queues = Set.of();
        while (running) {
            try {
                var capacityPass = awaitingCapacity && awaitCapacity(seen, nextFull, lastPassStart, queues);
                if (!capacityPass && !sleepUntil(nextFull)) {
                    break;
                }
                if (!running) {
                    break;
                }
                // Read before the pass: a release during it moves the count,
                // so the wait after it returns at once (D-3).
                seen = signal.epoch();
                lastPassStart = System.nanoTime();
                WorkflowExecutor.RecoveryPass pass;
                if (capacityPass) {
                    pass = executor.capacityPass(registrations, resumeAfter, queues);
                } else {
                    nextFull = lastPassStart + pollNanos;
                    pass = executor.recoveryPass(registrations);
                }
                guard.succeeded();
                log(pass, capacityPass, first);
                first = false;
                if (pass.deferred() > 0 || pass.outOfCapacity()) {
                    var deferredOn = pass.deferredByQueue().keySet();
                    if (pass.outOfCapacity()) {
                        // Rows past the stop were not read: any queue deferred before may own some.
                        var union = new HashSet<>(queues);
                        union.addAll(deferredOn);
                        queues = Set.copyOf(union);
                    } else {
                        queues = Set.copyOf(deferredOn);
                    }
                    resumeAfter = pass.resumeAfter();
                    awaitingCapacity = true;
                } else {
                    awaitingCapacity = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Rethrows a MaestroControlFlowError (ending the loop);
                // contains and logs everything else. The cursor is discarded
                // and the next attempt is a full pass, one pollInterval on.
                guard.failed(t);
                first = false;
                awaitingCapacity = false;
                resumeAfter = null;
                queues = Set.of();
                nextFull = System.nanoTime() + pollNanos;
            }
        }
        logger.debug("Recovery poller loop exited");
    }

    /**
     * After a pass that deferred: waits until capacity frees on one of
     * {@code queues}, but no sooner than {@code capacitySpacing} after
     * {@code lastPassStart}, and no later than the full pass due at
     * {@code nextFull}. A wake whose queues have no headroom makes no statement
     * and waits again.
     *
     * @return {@code true} to run a capacity pass now; {@code false} when the
     *         full pass is due or the poller is stopping
     */
    private boolean awaitCapacity(long seen, long nextFull, long lastPassStart, Set<String> queues)
            throws InterruptedException {
        var spacingNanos = capacitySpacing.toNanos();
        var epoch = seen;
        while (running) {
            var untilFull = nextFull - System.nanoTime();
            if (untilFull <= 0 || !signal.awaitChange(epoch, Duration.ofNanos(untilFull))) {
                return false;
            }
            if (!running) {
                return false;
            }
            var spacingLeft = lastPassStart + spacingNanos - System.nanoTime();
            if (spacingLeft > 0 && !sleepUntil(Math.min(lastPassStart + spacingNanos, nextFull))) {
                return false;
            }
            if (nextFull - System.nanoTime() <= 0) {
                return false;
            }
            // Read before the headroom check: a release after it wakes the next wait.
            epoch = signal.epoch();
            if (executor.recoveryHeadroom(queues) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits until {@code deadline} ({@link System#nanoTime()}) or a stop.
     *
     * @return {@code false} if the poller is stopping
     */
    private boolean sleepUntil(long deadline) throws InterruptedException {
        var remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            return !stopRequested.await(remaining, TimeUnit.NANOSECONDS) && running;
        }
        return running;
    }

    private void log(WorkflowExecutor.RecoveryPass pass, boolean capacityPass, boolean first) {
        if (first) {
            logger.info("Maestro startup recovery pass: adopted {} workflow(s); {} deferred until a task-queue "
                    + "slot frees on this node", pass.adopted(), pass.deferred());
        } else if (capacityPass) {
            if (pass.adopted() > 0) {
                logger.debug("Recovery poller adopted {} deferred workflow(s) as capacity freed", pass.adopted());
            }
        } else if (pass.adopted() > 0) {
            logger.info("Recovery poller adopted {} orphaned workflow(s)", pass.adopted());
        }
    }
}
