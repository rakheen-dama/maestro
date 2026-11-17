package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background sweeper that purges this service's ({@code serviceName})
 * long-finished workflows — instance, events, signals and timers — so the
 * store does not grow without bound. Another service's workflows in the same
 * tables are left to that service's own sweeper and its own retention.
 *
 * <p>Every {@code interval} the leader calls
 * {@link WorkflowStore#purgeTerminalInstancesBefore(String, WorkflowStatus, Instant, int)}
 * with its service name once per terminal status, each with its own cutoff
 * ({@code now - completedRetention}, {@code now - terminatedRetention},
 * {@code now - failedRetention}), in batches of {@code batchSize} until a
 * batch comes back short or {@code maxBatchesPerSweep} batches have run for
 * that status. The store method defines exactly what one batch removes and
 * what must survive (orphaned signals, instances with no {@code completedAt},
 * anything at or after the cutoff).
 *
 * <h2>Leader election</h2>
 * <p>Unlike {@link SignalJanitor}, whose batches are single idempotent
 * statements, a purge sweep is many multi-row transactions, so only one node
 * per service runs it:
 * before <em>every batch</em> the sweeper calls
 * {@link DistributedLock#trySetLeader} on
 * {@code maestro:leader:retention:<serviceName>} with a 15-second TTL — the
 * call both elects and renews, exactly as {@link TimerPoller} does for the
 * timer leader — and a node that is not (or no longer) the leader stops
 * sweeping at once. With no lock backend the node is always the leader
 * (single-node mode). Postgres's {@code FOR UPDATE SKIP LOCKED} on the
 * candidate select means two leaders overlapping briefly at a TTL boundary
 * take disjoint batches anyway.
 *
 * <h2>Pacing</h2>
 * <p>Within one sweep, every batch but the first is preceded by a pause as
 * long as the batch before it took (the store call only, on the monotonic
 * clock), at most five seconds; the boundary between two statuses counts as
 * between two batches, and nothing pauses after the last. The database is then
 * busy with a sweep about half the time it runs, instead of taking every batch
 * back to back. The cap is a third of {@link #LEADER_TTL}: leadership is
 * renewed only before each batch, after the pause, so a pause never makes a
 * sweep whose batches take under ten seconds lose its claim. A stop request
 * ends a pause at once.
 *
 * <h2>Failure handling</h2>
 * <p>A {@link RuntimeException} from the store or the lock backend is logged
 * at {@code WARN}, the current sweep ends, and the loop carries on to the next
 * interval; the sweeper never dies of a store error. A sweep that purged
 * anything is logged at {@code INFO}; a non-leader's skipped sweep at
 * {@code DEBUG}. An {@code Error} is contained by the loop's guard, logged and
 * counted on {@code maestro.loop.errors}, and the next sweep runs one interval
 * later.
 *
 * <h2>Observation</h2>
 * <p>Every sweep this node ran as leader — including one that found nothing —
 * ends in one {@link EngineObserver#retentionPass(PurgeResult)} with the
 * summed counts (the counts actually deleted, even if the sweep was cut short
 * by an error or by losing leadership). A non-leader emits nothing.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #start()}, {@link #stop()} and {@link #isRunning()} may be called
 * from any thread. The sweep loop runs on its own virtual thread named
 * {@code maestro-retention-sweeper}. {@link #sweepOnce()} is safe to call
 * concurrently with the loop.
 *
 * @see WorkflowExecutor#startRetentionSweeper(WorkflowExecutor.RetentionSettings)
 */
final class RetentionSweeper implements StoppableLoop {

    private static final Logger logger = LoggerFactory.getLogger(RetentionSweeper.class);

    /** Name of the virtual thread the sweep loop runs on. */
    static final String THREAD_NAME = "maestro-retention-sweeper";

    /** Prefix of the leader-election key; the service name is appended. */
    static final String LEADER_KEY_PREFIX = "maestro:leader:retention:";

    /** TTL of the leadership claim; renewed by every batch's re-check. */
    static final Duration LEADER_TTL = Duration.ofSeconds(15);

    /** How long {@link #stop()} waits for the loop's thread to end. */
    static final Duration STOP_JOIN = Duration.ofSeconds(5);

    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final String serviceName;
    private final WorkflowExecutor.RetentionSettings settings;
    private final EngineObserver observer;
    private final BatchPacer pacer;
    private final String candidateId;
    private final BackgroundLoopGuard guard;

    /**
     * Set by {@link #sweepOnce()} when it logged and swallowed a fault, so the
     * loop does not count that sweep as a success. Written and then read on
     * the loop thread; a direct {@code sweepOnce()} from another thread while
     * the loop runs can at worst misreport one sweep.
     */
    private volatile boolean sweepSwallowedAFailure;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /**
     * Set by {@link #requestStop()} and never cleared; distinct from {@code running}
     * so a direct {@link #sweepOnce()} on a sweeper that was never started
     * still sweeps.
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile @Nullable Thread sweeperThread;

    /**
     * Creates a new retention sweeper.
     *
     * @param store           the store whose terminal workflows are purged
     * @param distributedLock optional lock backend for leader election
     *                        ({@code null} means this node is always the leader)
     * @param serviceName     the owning service name (suffix of the election key)
     * @param settings        interval, per-status retention and batching
     * @param observer        receives one {@code retentionPass} per sweep run as leader
     * @throws NullPointerException     if {@code store}, {@code settings} or
     *                                  {@code observer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank
     */
    RetentionSweeper(WorkflowStore store,
                     @Nullable DistributedLock distributedLock,
                     String serviceName,
                     WorkflowExecutor.RetentionSettings settings,
                     EngineObserver observer) {
        this(store, distributedLock, serviceName, settings, observer, new BatchPacer());
    }

    /**
     * Creates a new retention sweeper with the given pacer (a test seam).
     *
     * @param store           the store whose terminal workflows are purged
     * @param distributedLock optional lock backend for leader election
     *                        ({@code null} means this node is always the leader)
     * @param serviceName     the owning service name (suffix of the election key)
     * @param settings        interval, per-status retention and batching
     * @param observer        receives one {@code retentionPass} per sweep run as leader
     * @param pacer           times each batch and pauses between batches
     * @throws NullPointerException     if {@code store}, {@code settings},
     *                                  {@code observer} or {@code pacer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank
     */
    RetentionSweeper(WorkflowStore store,
                     @Nullable DistributedLock distributedLock,
                     String serviceName,
                     WorkflowExecutor.RetentionSettings settings,
                     EngineObserver observer,
                     BatchPacer pacer) {
        if (store == null) throw new NullPointerException("store must not be null");
        if (settings == null) throw new NullPointerException("settings must not be null");
        if (observer == null) throw new NullPointerException("observer must not be null");
        if (pacer == null) throw new NullPointerException("pacer must not be null");
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        this.store = store;
        this.distributedLock = distributedLock;
        this.serviceName = serviceName;
        this.settings = settings;
        this.observer = observer;
        this.pacer = pacer;
        this.candidateId = UUID.randomUUID().toString();
        this.guard = new BackgroundLoopGuard(BackgroundLoopGuard.RETENTION_SWEEPER, settings.interval(),
                () -> !running, observer);
    }

    /**
     * Starts the sweep loop on a virtual thread. The first sweep runs
     * immediately, then one every {@code interval}.
     *
     * @throws IllegalStateException if already started
     */
    synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Retention sweeper already started");
        }
        running = true;
        sweeperThread = Thread.ofVirtual()
                .name(THREAD_NAME)
                .uncaughtExceptionHandler(guard.uncaughtExceptionHandler())
                .start(this::sweepLoop);
        logger.info("Retention sweeper started (interval={}, completedRetention={}, terminatedRetention={}, "
                        + "failedRetention={}, batchSize={}, maxBatchesPerSweep={}, service={})",
                settings.interval(), settings.completedRetention(), settings.terminatedRetention(),
                settings.failedRetention(), settings.batchSize(), settings.maxBatchesPerSweep(), serviceName);
    }

    /**
     * Stops the sweep loop: {@link #requestStop()}, then
     * {@link #awaitStopped(Duration) awaitStopped(STOP_JOIN)} — waits up to
     * {@link #STOP_JOIN} (5 seconds) for the thread to end. After stopping,
     * the sweeper cannot be restarted.
     */
    void stop() {
        requestStop();
        awaitStopped(STOP_JOIN);
    }

    @Override
    public String loopName() {
        return BackgroundLoopGuard.RETENTION_SWEEPER;
    }

    /**
     * Asks the sweep loop to stop and returns at once: no new iteration starts,
     * a pause between two batches ends at once (without the interrupt), and
     * the thread is interrupted (Issue 64 tracks that interrupt, which can
     * land inside a store call). Never joins. Idempotent.
     */
    @Override
    public synchronized void requestStop() {
        pacer.requestStop();
        stopRequested.set(true);
        running = false;
        var thread = sweeperThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Waits at most {@code bound} for the sweep loop's thread to end. Once it
     * has ended, logs that the sweeper stopped (once) and forgets the thread.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the thread has ended or was never started,
     *         {@code false} if it is still running — nothing is logged then
     */
    @Override
    public boolean awaitStopped(Duration bound) {
        var thread = sweeperThread;
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
            if (sweeperThread == thread) {
                sweeperThread = null;
                logger.info("Retention sweeper stopped");
            }
        }
        return true;
    }

    /**
     * Returns whether the sweep loop is currently running.
     *
     * @return {@code true} if started, not stopped, and the thread is alive
     */
    boolean isRunning() {
        var thread = sweeperThread;
        return running && thread != null && thread.isAlive();
    }

    /**
     * The loop's current failure streak (sweeps that threw since the last one
     * that returned). Safe to call from any thread.
     *
     * @return the number of consecutive failed sweeps
     */
    int consecutiveFailures() {
        return guard.consecutiveFailures();
    }

    /**
     * Runs one sweep: for each terminal status, purges batches of instances
     * finished before {@code now - <that status's retention>} until a batch
     * comes back short or the per-status batch budget is spent, pausing
     * between batches (see the class Javadoc) and re-checking leadership after
     * each pause, right before the batch.
     *
     * <p>A {@link RuntimeException} from the store or the lock backend is
     * logged at {@code WARN} and swallowed; the sweep ends there and reports
     * what it had purged so far. So does a {@link #stop()} request: it ends a
     * pause at once and is checked before every batch, so a store call that swallowed the
     * interrupt cannot carry a sweep on past shutdown for up to
     * {@code maxBatchesPerSweep} more batches.
     *
     * @return the summed counts of this sweep if this node was the leader when
     *         it began (possibly {@link PurgeResult#EMPTY}); empty if another
     *         node holds leadership, or the first leader check failed
     */
    Optional<PurgeResult> sweepOnce() {
        sweepSwallowedAFailure = false;
        var now = Instant.now();
        var total = PurgeResult.EMPTY;
        var leader = false;
        var batches = 0;
        long previous = -1;
        try {
            for (var target : targets(now)) {
                for (int batch = 0; batch < settings.maxBatchesPerSweep(); batch++) {
                    // The pause comes before the stop and leader checks, so
                    // leadership is renewed right before the batch it guards.
                    if ((previous >= 0 && !pacer.pauseAfter(previous)) || stopRequested.get()) {
                        logger.info("Retention sweeper stopping mid-sweep after {} instance(s)", total.instances());
                        return leader ? finish(total, batches) : Optional.empty();
                    }
                    if (!isLeader()) {
                        if (!leader) {
                            logger.debug("Another node holds retention leadership for service '{}' — skipping sweep",
                                    serviceName);
                            return Optional.empty();
                        }
                        logger.info("Lost retention leadership for service '{}' mid-sweep — stopping after {} "
                                + "instance(s)", serviceName, total.instances());
                        return finish(total, batches);
                    }
                    leader = true;
                    var t0 = pacer.now();
                    var result = store.purgeTerminalInstancesBefore(serviceName, target.status(),
                            target.cutoff(), settings.batchSize());
                    previous = pacer.now() - t0;
                    batches++;
                    total = total.plus(result);
                    if (result.instances() < settings.batchSize()) {
                        break;
                    }
                }
            }
            return finish(total, batches);
        } catch (RuntimeException e) {
            logger.warn("Retention sweep failed after purging {} instance(s) — will retry in {}",
                    total.instances(), settings.interval(), e);
            sweepSwallowedAFailure = true;
            return leader ? finish(total, batches) : Optional.empty();
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    /** The three terminal statuses with their cutoffs for a sweep starting at {@code now}. */
    private List<Target> targets(Instant now) {
        return List.of(
                new Target(WorkflowStatus.COMPLETED, now.minus(settings.completedRetention())),
                new Target(WorkflowStatus.TERMINATED, now.minus(settings.terminatedRetention())),
                new Target(WorkflowStatus.FAILED, now.minus(settings.failedRetention())));
    }

    private record Target(WorkflowStatus status, Instant cutoff) {
    }

    /** Logs and observes a sweep this node ran as leader. */
    private Optional<PurgeResult> finish(PurgeResult total, int batches) {
        if (total.instances() > 0) {
            logger.info("Retention sweep purged {} workflow instance(s) with {} event(s), {} signal(s) and "
                            + "{} timer(s) in {} batch(es)", total.instances(), total.events(), total.signals(),
                    total.timers(), batches);
        }
        try {
            observer.retentionPass(total);
        } catch (RuntimeException e) {
            logger.warn("EngineObserver.retentionPass threw — ignoring: {}", e.toString());
        }
        return Optional.of(total);
    }

    /**
     * Checks whether this node is the retention leader, electing or renewing
     * in the same call (see {@link TimerPoller}). A lock-backend failure is
     * treated as "not the leader" for this check and logged at {@code WARN}.
     */
    private boolean isLeader() {
        if (distributedLock == null) {
            return true; // No lock backend — single-node mode
        }
        try {
            return distributedLock.trySetLeader(LEADER_KEY_PREFIX + serviceName, candidateId, LEADER_TTL);
        } catch (RuntimeException e) {
            logger.warn("Retention leader election failed for service '{}' — treating as not the leader",
                    serviceName, e);
            sweepSwallowedAFailure = true;
            return false;
        }
    }

    private void sweepLoop() {
        while (running) {
            try {
                sweepOnce();
                // A sweep that swallowed a store or lock-backend fault is not
                // a success: it must not end an Error streak (nor log "recovered").
                if (!sweepSwallowedAFailure) {
                    guard.succeeded();
                }
            } catch (Throwable t) {
                // Rethrows a MaestroControlFlowError (ending the loop);
                // contains and logs everything else. Store and lock-backend
                // faults never get here: sweepOnce logs and swallows them.
                guard.failed(t);
            }
            // stop() interrupts this thread, but a store call (e.g. a JDBC
            // driver) may swallow that interrupt and return normally; without
            // this re-check the loop would then sleep a full interval after
            // stop() and outlive its 5 s join.
            if (!running) {
                break;
            }
            try {
                Thread.sleep(settings.interval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.debug("Retention sweeper loop exited");
    }
}
