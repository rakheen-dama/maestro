package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background sweeper that purges unconsumed signals addressed to workflows
 * that finished long ago (C14b).
 *
 * <p>A signal can arrive after its workflow has already reached
 * {@code COMPLETED} or {@code TERMINATED} — a late duplicate, a retry from an
 * upstream service, an event for a step the workflow no longer waits on.
 * Nothing will ever consume it, and without this sweeper the signal table
 * grows without bound. Every {@code interval} the janitor calls
 * {@link WorkflowStore#deleteUnconsumedSignalsForTerminalInstancesBefore(String, Instant, int)}
 * with this node's service name, {@code Instant.now() - retention} as the
 * cutoff and {@link #BATCH_SIZE} as the limit. The store method defines
 * exactly what is purged; in particular {@code FAILED} workflows keep their
 * signals (a retry may consume them), and orphaned and consumed rows are
 * never touched.
 *
 * <h2>Batches, without leader election</h2>
 * <p>Every node runs a janitor, without leader election. It deletes only
 * this service's signals, in batches of 1,000, pausing between batches as
 * the retention sweeper does, and at most {@link #MAX_BATCHES_PER_SWEEP}
 * batches (1,000,000 signals) per sweep; a larger backlog takes more than
 * one interval. Two nodes sweeping at once wait at most one
 * batch on each other, and the later one ends early.
 *
 * <h2>Failure handling</h2>
 * <p>A {@link RuntimeException} from the store (for example the database is
 * briefly unreachable) is logged at {@code WARN} and the loop carries on to
 * the next interval; the janitor never dies of a store error. Sweeps that
 * delete rows are logged at {@code INFO}. An {@code Error} is contained by
 * the loop's guard, logged and counted on {@code maestro.loop.errors}, and the
 * next sweep runs one interval later.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #start()}, {@link #stop()} and {@link #isRunning()} may be
 * called from any thread. The sweep loop runs on its own virtual thread named
 * {@code maestro-signal-janitor}. {@link #sweepOnce()} is safe to call
 * concurrently with the loop (the store operation is idempotent).
 *
 * @see WorkflowExecutor#startSignalJanitor(Duration, Duration)
 */
final class SignalJanitor implements StoppableLoop {

    private static final Logger logger = LoggerFactory.getLogger(SignalJanitor.class);

    /** Name of the virtual thread the sweep loop runs on. */
    static final String THREAD_NAME = "maestro-signal-janitor";

    /** How long {@link #stop()} waits for the loop's thread to end. */
    static final Duration STOP_JOIN = Duration.ofSeconds(5);

    /** The most signal rows one delete statement removes. */
    static final int BATCH_SIZE = 1_000;

    /**
     * The most batches one sweep issues: 1,000 batches, so at most 1,000,000
     * signals per sweep.
     */
    static final int MAX_BATCHES_PER_SWEEP = 1_000;

    private final WorkflowStore store;
    private final String serviceName;
    private final Duration interval;
    private final Duration retention;
    private final BatchPacer pacer;
    private final BackgroundLoopGuard guard;

    /**
     * Set by {@link #sweepOnce()} when it logged and swallowed a fault, so the
     * loop does not count that sweep as a success. Written and then read on
     * the loop thread; a direct {@code sweepOnce()} from another thread while
     * the loop runs can at worst misreport one sweep.
     */
    private volatile boolean sweepSwallowedAFailure;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile @Nullable Thread janitorThread;

    /**
     * Creates a new signal janitor.
     *
     * @param store       the store whose signal table is swept
     * @param serviceName the service whose workflows' signals are swept
     * @param interval    time between sweeps; must be positive
     * @param retention   how long after a workflow completed or was terminated
     *                    its unconsumed signals are kept; must be positive
     * @throws NullPointerException     if {@code store} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or {@code interval} or {@code retention}
     *                                  is {@code null}, zero or negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention) {
        this(store, serviceName, interval, retention, EngineObserver.NOOP);
    }

    /**
     * Creates a new signal janitor that reports contained {@code Error}s.
     *
     * @param store       the store whose signal table is swept
     * @param serviceName the service whose workflows' signals are swept
     * @param interval    time between sweeps; must be positive
     * @param retention   how long after a workflow completed or was terminated
     *                    its unconsumed signals are kept; must be positive
     * @param observer    receives {@link EngineObserver#backgroundLoopError}
     *                    for every {@code Error} the loop contains
     * @throws NullPointerException     if {@code store} or {@code observer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or {@code interval} or {@code retention}
     *                                  is {@code null}, zero or negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention,
                  EngineObserver observer) {
        this(store, serviceName, interval, retention, observer, new BatchPacer());
    }

    /**
     * Creates a new signal janitor with the given pacer (a test seam).
     *
     * @param store       the store whose signal table is swept
     * @param serviceName the service whose workflows' signals are swept
     * @param interval    time between sweeps; must be positive
     * @param retention   how long after a workflow completed or was terminated
     *                    its unconsumed signals are kept; must be positive
     * @param observer    receives {@link EngineObserver#backgroundLoopError}
     *                    for every {@code Error} the loop contains
     * @param pacer       times each batch and pauses between batches
     * @throws NullPointerException     if {@code store}, {@code observer} or
     *                                  {@code pacer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or {@code interval} or {@code retention}
     *                                  is {@code null}, zero or negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention,
                  EngineObserver observer, BatchPacer pacer) {
        if (store == null) throw new NullPointerException("store must not be null");
        if (observer == null) throw new NullPointerException("observer must not be null");
        if (pacer == null) throw new NullPointerException("pacer must not be null");
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive, got " + retention);
        }
        this.store = store;
        this.serviceName = serviceName;
        this.interval = interval;
        this.retention = retention;
        this.pacer = pacer;
        this.guard = new BackgroundLoopGuard(BackgroundLoopGuard.SIGNAL_JANITOR, interval, () -> !running, observer);
    }

    /**
     * Starts the sweep loop on a virtual thread. The first sweep runs
     * immediately, then one every {@code interval}.
     *
     * @throws IllegalStateException if already started
     */
    synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Signal janitor already started");
        }
        running = true;
        janitorThread = Thread.ofVirtual()
                .name(THREAD_NAME)
                .uncaughtExceptionHandler(guard.uncaughtExceptionHandler())
                .start(this::sweepLoop);
        logger.info("Signal janitor started (interval={}, terminalRetention={}, batchSize={}, service={})",
                interval, retention, BATCH_SIZE, serviceName);
    }

    /**
     * Stops the sweep loop: {@link #requestStop()}, then
     * {@link #awaitStopped(Duration) awaitStopped(STOP_JOIN)} — waits up to
     * {@link #STOP_JOIN} (5 seconds) for the thread to end. After stopping,
     * the janitor cannot be restarted.
     */
    void stop() {
        requestStop();
        awaitStopped(STOP_JOIN);
    }

    @Override
    public String loopName() {
        return BackgroundLoopGuard.SIGNAL_JANITOR;
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
        running = false;
        var thread = janitorThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Waits at most {@code bound} for the sweep loop's thread to end. Once it
     * has ended, logs that the janitor stopped (once) and forgets the thread.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the thread has ended or was never started,
     *         {@code false} if it is still running — nothing is logged then
     */
    @Override
    public boolean awaitStopped(Duration bound) {
        var thread = janitorThread;
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
            if (janitorThread == thread) {
                janitorThread = null;
                logger.info("Signal janitor stopped");
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
        var thread = janitorThread;
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
     * Runs one sweep: purges this service's unconsumed signals of workflows
     * that reached {@code COMPLETED}/{@code TERMINATED} before
     * {@code now - retention}, in batches of {@link #BATCH_SIZE} until a batch
     * deletes fewer, pausing between batches (see the class Javadoc). The
     * cutoff is fixed once per sweep. A sweep issues at most
     * {@link #MAX_BATCHES_PER_SWEEP} batches, so a store that keeps reporting
     * full batches cannot keep it running; it logs one {@code INFO} and leaves
     * the rest to the next sweep.
     *
     * <p>A {@link RuntimeException} from the store is logged at {@code WARN}
     * and swallowed, so the loop keeps running; the sweep ends there and
     * reports what the batches before it deleted. A stop request ends a pause
     * at once and ends the sweep.
     *
     * @return the number of signal rows deleted
     */
    int sweepOnce() {
        sweepSwallowedAFailure = false;
        var cutoff = Instant.now().minus(retention);
        var total = 0;
        var batches = 0;
        long previous = -1;
        try {
            while (true) {
                if ((previous >= 0 && !pacer.pauseAfter(previous)) || pacer.stopRequested()) {
                    break;
                }
                var t0 = pacer.now();
                int deleted = store.deleteUnconsumedSignalsForTerminalInstancesBefore(serviceName, cutoff, BATCH_SIZE);
                previous = pacer.now() - t0;
                batches++;
                total += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
                if (batches >= MAX_BATCHES_PER_SWEEP) {
                    logger.info("Signal janitor ended this sweep after {} batches ({} signal(s)); the rest is "
                            + "left to the next sweep, in {}", batches, total, interval);
                    break;
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Signal janitor sweep failed after purging {} signal(s) — will retry in {}",
                    total, interval, e);
            sweepSwallowedAFailure = true;
        }
        if (total > 0) {
            logger.info("Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished before {} "
                    + "in {} batch(es)", total, serviceName, cutoff, batches);
        }
        return total;
    }

    // ── Internal: sweep loop ────────────────────────────────────────────

    private void sweepLoop() {
        while (running) {
            try {
                sweepOnce();
                // A sweep that swallowed a store fault is not a success: it
                // must not end an Error streak (nor log "recovered").
                if (!sweepSwallowedAFailure) {
                    guard.succeeded();
                }
            } catch (Throwable t) {
                // Rethrows a MaestroControlFlowError (ending the loop);
                // contains and logs everything else. Store faults never get
                // here: sweepOnce logs and swallows them itself.
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
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.debug("Signal janitor loop exited");
    }
}
