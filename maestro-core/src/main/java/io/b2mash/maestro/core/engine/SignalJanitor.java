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
import java.util.function.IntSupplier;

/**
 * The signal janitor's sweep (C14b): purges unconsumed signals addressed to
 * workflows that finished long ago, and orphaned signals no workflow can
 * consume any more (Issue 83). A {@link LoopBody} run under the
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
 * <h2>Orphans</h2>
 * <p>Then, in a phase of its own, the sweep calls
 * {@link WorkflowStore#deleteOrphanedSignalsBefore(Instant, int)} with
 * {@code Instant.now() - orphanRetention} as the cutoff
 * ({@code maestro.signal.orphan-retention}, default
 * {@link #DEFAULT_ORPHAN_RETENTION}): it deletes unconsumed signals with no
 * instance, received before the cutoff, whose workflow id has no instance that
 * could still consume them (none at all, or one {@code COMPLETED} or
 * {@code TERMINATED}). A signal delivered before its workflow starts is
 * therefore kept for {@code orphanRetention}; a workflow started within it
 * still adopts and consumes it, and one active or {@code FAILED} keeps it
 * however old it is. An orphan row names no service, so this phase is not
 * scoped by service: it covers the orphans of the tables the node runs on,
 * which belong to its service alone (services never share Maestro tables).
 *
 * <h2>Batches, without leader election</h2>
 * <p>Every node runs a janitor, without leader election. Each phase deletes
 * in batches of 1,000, pausing between batches as
 * the retention sweeper does, and at most {@link #MAX_BATCHES_PER_SWEEP}
 * batches (1,000,000 signals) per phase per sweep; a larger backlog takes
 * more than one interval. Two nodes sweeping at once wait at most one
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

    /**
     * How long an orphaned signal waits for its workflow to start when no
     * orphan retention is given: 7 days, the
     * {@code maestro.signal.orphan-retention} default.
     */
    static final Duration DEFAULT_ORPHAN_RETENTION = Duration.ofDays(7);

    private final WorkflowStore store;
    private final String serviceName;
    private final Duration interval;
    private final Duration retention;
    private final Duration orphanRetention;
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
        this(store, serviceName, interval, retention, DEFAULT_ORPHAN_RETENTION, new BatchPacer());
    }

    /**
     * Creates a new signal janitor with the given orphan retention.
     *
     * @param store           the store whose signal table is swept
     * @param serviceName     the service whose workflows' signals are swept
     * @param interval        time between sweeps; must be positive
     * @param retention       how long after a workflow completed or was
     *                        terminated its unconsumed signals are kept; must
     *                        be positive
     * @param orphanRetention how long an orphaned signal waits for its
     *                        workflow to start; must be positive
     * @throws NullPointerException     if {@code store} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or a duration is {@code null}, zero or
     *                                  negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention,
                  Duration orphanRetention) {
        this(store, serviceName, interval, retention, orphanRetention, new BatchPacer());
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
        this(store, serviceName, interval, retention, DEFAULT_ORPHAN_RETENTION, pacer);
    }

    /**
     * Creates a new signal janitor with every setting and the given pacer (a
     * test seam).
     *
     * @param store           the store whose signal table is swept
     * @param serviceName     the service whose workflows' signals are swept
     * @param interval        time between sweeps; must be positive
     * @param retention       how long after a workflow completed or was
     *                        terminated its unconsumed signals are kept; must
     *                        be positive
     * @param orphanRetention how long an orphaned signal waits for its
     *                        workflow to start; must be positive
     * @param pacer           times each batch and pauses between batches
     * @throws NullPointerException     if {@code store} or {@code pacer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank,
     *                                  or a duration is {@code null}, zero or
     *                                  negative
     */
    SignalJanitor(WorkflowStore store, String serviceName, Duration interval, Duration retention,
                  Duration orphanRetention, BatchPacer pacer) {
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
        if (orphanRetention == null || orphanRetention.isNegative() || orphanRetention.isZero()) {
            throw new IllegalArgumentException("orphanRetention must be positive, got " + orphanRetention);
        }
        this.store = store;
        this.serviceName = serviceName;
        this.interval = interval;
        this.retention = retention;
        this.orphanRetention = orphanRetention;
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
        return "interval=%s, terminalRetention=%s, orphanRetention=%s, batchSize=%d, service=%s"
                .formatted(interval, retention, orphanRetention, BATCH_SIZE, serviceName);
    }

    /**
     * Runs one sweep: purges this service's unconsumed signals of workflows
     * that reached {@code COMPLETED}/{@code TERMINATED} before
     * {@code now - retention}, then the orphans received before
     * {@code now - orphanRetention} that no workflow can consume (see the
     * class Javadoc). Each phase deletes in batches of {@link #BATCH_SIZE}
     * until a batch deletes fewer, pausing between two batches of a phase as
     * long as the batch before took. Both cutoffs are fixed
     * once per sweep. A phase issues at most {@link #MAX_BATCHES_PER_SWEEP}
     * batches, so a store that keeps reporting full batches cannot keep it
     * running; it logs one {@code INFO} and leaves the rest to the next
     * sweep. A stop request ends a pause at once and ends the sweep.
     *
     * @return the number of signal rows deleted, both phases together
     * @throws RuntimeException what the store threw; what the batches before
     *         it deleted is logged first
     */
    int sweepOnce() {
        var now = Instant.now();
        var cutoff = now.minus(retention);
        var orphanCutoff = now.minus(orphanRetention);
        var terminal = new Phase();
        var orphans = new Phase();
        try {
            drain(terminal, "this sweep",
                    () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore(serviceName, cutoff, BATCH_SIZE));
            drain(orphans, "this sweep's orphan purge",
                    () -> store.deleteOrphanedSignalsBefore(orphanCutoff, BATCH_SIZE));
        } finally {
            if (terminal.total > 0) {
                logger.info("Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished "
                        + "before {} in {} batch(es)", terminal.total, serviceName, cutoff, terminal.batches);
            }
            if (orphans.total > 0) {
                logger.info("Signal janitor purged {} orphaned signal(s) received before {} that no workflow "
                        + "can consume, in {} batch(es)", orphans.total, orphanCutoff, orphans.batches);
            }
        }
        return terminal.total + orphans.total;
    }

    /** What one phase of a sweep deleted so far. */
    private static final class Phase {
        int total;
        int batches;
    }

    /**
     * Runs {@code batch} until it deletes fewer than {@link #BATCH_SIZE}, the
     * phase reaches {@link #MAX_BATCHES_PER_SWEEP} batches, or a stop is
     * requested (checked before each batch, after its pause). The phase's
     * first batch follows no pause.
     */
    private void drain(Phase phase, String what, IntSupplier batch) {
        long previous = -1;
        while (true) {
            if ((previous >= 0 && !pacer.pauseAfter(previous)) || pacer.stopRequested()) {
                return;
            }
            var t0 = pacer.now();
            int deleted = batch.getAsInt();
            previous = pacer.now() - t0;
            phase.batches++;
            phase.total += deleted;
            if (deleted < BATCH_SIZE) {
                return;
            }
            if (phase.batches >= MAX_BATCHES_PER_SWEEP) {
                logger.info("Signal janitor ended {} after {} batches ({} signal(s)); the rest is "
                        + "left to the next sweep, in {}", what, phase.batches, phase.total, interval);
                return;
            }
        }
    }
}
