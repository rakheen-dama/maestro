package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * The signal janitor's sweep (C14b): purges unconsumed signals addressed to
 * workflows that finished long ago. A {@link LoopBody} run under the
 * executor's {@link io.b2mash.maestro.core.loop.LoopSupervisor} as loop
 * {@value #LOOP_NAME} ({@link #spec()}), which owns its thread, its wait,
 * its failure streak and its stop.
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
 * <p>A sweep that throws — a store fault or an {@code Error} — is a failed
 * iteration: the supervisor counts it in the loop's streak, logs it at its
 * cadence (every failure at {@code ERROR} for an interval of five minutes or
 * more) and runs the next sweep one interval later; an {@code Error} is also
 * counted on {@code maestro.loop.errors}. The janitor never dies of a store
 * error. A sweep that deletes rows is logged at {@code INFO}, also when a
 * later batch of it failed.
 *
 * <h2>Thread Safety</h2>
 * <p>The supervisor runs {@link #iterate} on the loop's thread only.
 * {@link #sweepOnce()} is safe to call concurrently with the loop (the store
 * operation is idempotent).
 *
 * @see WorkflowExecutor#startSignalJanitor(Duration, Duration)
 */
final class SignalJanitor implements LoopBody {

    private static final Logger logger = LoggerFactory.getLogger(SignalJanitor.class);

    /** The loop's fixed name: its meter tag and log name. */
    static final String LOOP_NAME = "signal-janitor";

    /** Name of the virtual thread the sweep loop runs on. */
    static final String THREAD_NAME = "maestro-signal-janitor";

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
        this(store, serviceName, interval, retention, new BatchPacer());
    }

    /**
     * Creates a new signal janitor with the given pacer (a test seam).
     *
     * @param store       the store whose signal table is swept
     * @param serviceName the service whose workflows' signals are swept
     * @param interval    time between sweeps; must be positive
     * @param retention   how long after a workflow completed or was terminated
     *                    its unconsumed signals are kept; must be positive
     * @param pacer       times each batch and pauses between batches
     * @throws NullPointerException     if {@code store} or {@code pacer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or {@code interval} or {@code retention}
     *                                  is {@code null}, zero or negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention,
                  BatchPacer pacer) {
        if (store == null) throw new NullPointerException("store must not be null");
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
    }

    /**
     * How the janitor is supervised: housekeeping; the first sweep at once,
     * then one every {@code interval}, failed or not; failing after two failed
     * sweeps in a row; a stop also ends a pause between two batches.
     *
     * @return the janitor's loop spec
     */
    LoopSpec spec() {
        return LoopSpec.of(LOOP_NAME, THREAD_NAME, LoopRole.HOUSEKEEPING, interval)
                .withOnStop(pacer::requestStop);
    }

    @Override
    public Next iterate(LoopContext ctx) {
        sweepOnce();
        return Next.afterPeriod();
    }

    /** The settings, for the start line. */
    String describeSettings() {
        return "interval=%s, terminalRetention=%s, batchSize=%d, service=%s"
                .formatted(interval, retention, BATCH_SIZE, serviceName);
    }

    /**
     * Runs one sweep: purges this service's unconsumed signals of workflows
     * that reached {@code COMPLETED}/{@code TERMINATED} before
     * {@code now - retention}, in batches of {@link #BATCH_SIZE} until a batch
     * deletes fewer, pausing between batches (see the class Javadoc). The
     * cutoff is fixed once per sweep. A sweep issues at most
     * {@link #MAX_BATCHES_PER_SWEEP} batches, so a store that keeps reporting
     * full batches cannot keep it running; it logs one {@code INFO} and leaves
     * the rest to the next sweep. A stop request ends a pause at once and ends
     * the sweep.
     *
     * @return the number of signal rows deleted
     * @throws RuntimeException what the store threw; what the batches before
     *         it deleted is logged first
     */
    int sweepOnce() {
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
        } finally {
            if (total > 0) {
                logger.info("Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished "
                        + "before {} in {} batch(es)", total, serviceName, cutoff, batches);
            }
        }
        return total;
    }
}
