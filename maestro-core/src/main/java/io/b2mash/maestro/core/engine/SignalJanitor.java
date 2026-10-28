package io.b2mash.maestro.core.engine;

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
 * {@link WorkflowStore#deleteUnconsumedSignalsForTerminalInstancesBefore}
 * with {@code Instant.now() - retention} as the cutoff. The store method
 * defines exactly what is purged; in particular {@code FAILED} workflows keep
 * their signals (a retry may consume them), and orphaned and consumed rows
 * are never touched.
 *
 * <h2>No leader election</h2>
 * <p>Unlike {@link TimerPoller}, every node runs a janitor. The delete is a
 * single idempotent statement, cheap when there is nothing to purge, and two
 * nodes sweeping at once simply race to delete the same rows — the loser
 * deletes nothing. Electing a leader would buy nothing but a dependency on
 * the lock backend.
 *
 * <h2>Failure handling</h2>
 * <p>A {@link RuntimeException} from the store (for example the database is
 * briefly unreachable) is logged at {@code WARN} and the loop carries on to
 * the next interval; the janitor never dies of a store error. Purges that
 * delete rows are logged at {@code INFO}.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #start()}, {@link #stop()} and {@link #isRunning()} may be
 * called from any thread. The sweep loop runs on its own virtual thread named
 * {@code maestro-signal-janitor}. {@link #sweepOnce()} is safe to call
 * concurrently with the loop (the store operation is idempotent).
 *
 * @see WorkflowExecutor#startSignalJanitor(Duration, Duration)
 */
final class SignalJanitor {

    private static final Logger logger = LoggerFactory.getLogger(SignalJanitor.class);

    /** Name of the virtual thread the sweep loop runs on. */
    static final String THREAD_NAME = "maestro-signal-janitor";

    private final WorkflowStore store;
    private final Duration interval;
    private final Duration retention;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile @Nullable Thread janitorThread;

    /**
     * Creates a new signal janitor.
     *
     * @param store     the store whose signal table is swept
     * @param interval  time between sweeps; must be positive
     * @param retention how long after a workflow completed or was terminated
     *                  its unconsumed signals are kept; must be positive
     * @throws NullPointerException     if {@code store} is {@code null}
     * @throws IllegalArgumentException if {@code interval} or {@code retention}
     *                                  is {@code null}, zero or negative
     */
    SignalJanitor(WorkflowStore store, Duration interval, Duration retention) {
        if (store == null) throw new NullPointerException("store must not be null");
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive, got " + retention);
        }
        this.store = store;
        this.interval = interval;
        this.retention = retention;
    }

    /**
     * Starts the sweep loop on a virtual thread. The first sweep runs
     * immediately, then one every {@code interval}.
     *
     * @throws IllegalStateException if already started
     */
    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Signal janitor already started");
        }
        running = true;
        janitorThread = Thread.ofVirtual()
                .name(THREAD_NAME)
                .start(this::sweepLoop);
        logger.info("Signal janitor started (interval={}, terminalRetention={})", interval, retention);
    }

    /**
     * Stops the sweep loop.
     *
     * <p>Interrupts the janitor thread and waits up to 5 seconds for it to
     * terminate. After stopping, the janitor cannot be restarted.
     */
    void stop() {
        running = false;
        var thread = janitorThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        janitorThread = null;
        logger.info("Signal janitor stopped");
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
     * Runs one sweep: purges the unconsumed signals of workflows that reached
     * {@code COMPLETED}/{@code TERMINATED} before {@code now - retention}.
     *
     * <p>A {@link RuntimeException} from the store is logged at {@code WARN}
     * and swallowed, so the loop keeps running; the sweep then reports
     * {@code 0}.
     *
     * @return the number of signal rows deleted ({@code 0} on a store failure)
     */
    int sweepOnce() {
        var cutoff = Instant.now().minus(retention);
        try {
            int deleted = store.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff);
            if (deleted > 0) {
                logger.info("Signal janitor purged {} unconsumed signal(s) of workflows finished before {}",
                        deleted, cutoff);
            }
            return deleted;
        } catch (RuntimeException e) {
            logger.warn("Signal janitor sweep failed — will retry in {}", interval, e);
            return 0;
        }
    }

    // ── Internal: sweep loop ────────────────────────────────────────────

    private void sweepLoop() {
        while (running) {
            sweepOnce();
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
