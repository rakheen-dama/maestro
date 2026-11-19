package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The retention sweeper's sweep: purges this service's ({@code serviceName})
 * long-finished workflows — instance, events, signals and timers — so the
 * store does not grow without bound. Another service's workflows in the same
 * tables are left to that service's own sweeper and its own retention. A
 * {@link LoopBody} run under the executor's
 * {@link io.b2mash.maestro.core.loop.LoopSupervisor} as loop
 * {@value #LOOP_NAME} ({@link #spec()}), which owns its thread, its wait, its
 * failure streak and its stop.
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
 * sweeping at once. A {@link DistributedLock} is mandatory (pilot readiness
 * P3). Postgres's {@code FOR UPDATE SKIP LOCKED} on the
 * candidate select means two leaders overlapping briefly at a TTL boundary
 * take disjoint batches anyway. A non-leader whose election call answered has
 * swept successfully — it is able to take over; the loop notes
 * {@code leader: true|false} after each sweep.
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
 * ends a pause at once and is checked before every batch.
 *
 * <h2>Failure handling</h2>
 * <p>A sweep that throws — a store fault, a lock-backend fault on the
 * election, or an {@code Error} — is a failed iteration on every node: the
 * supervisor counts it in the loop's streak, logs it at its cadence and runs
 * the next sweep one interval later; an {@code Error} is also counted on
 * {@code maestro.loop.errors}. The sweeper never dies of a store error. A
 * sweep that purged anything is logged at {@code INFO}; a non-leader's skipped
 * sweep at {@code DEBUG}.
 *
 * <h2>Observation</h2>
 * <p>Every sweep this node ran as leader — including one that found nothing,
 * and one a fault cut short — ends in one
 * {@link EngineObserver#retentionPass(PurgeResult)} with the summed counts
 * actually deleted. A non-leader emits nothing.
 *
 * <h2>Thread Safety</h2>
 * <p>The supervisor runs {@link #iterate} on the loop's thread only.
 * {@link #sweepOnce()} is safe to call concurrently with the loop.
 *
 * @see WorkflowExecutor#startRetentionSweeper(WorkflowExecutor.RetentionSettings)
 */
final class RetentionSweeper implements LoopBody {

    private static final Logger logger = LoggerFactory.getLogger(RetentionSweeper.class);

    /** The loop's fixed name: its meter tag and log name. */
    static final String LOOP_NAME = "retention-sweeper";

    /** Name of the virtual thread the sweep loop runs on. */
    static final String THREAD_NAME = "maestro-retention-sweeper";

    /** Prefix of the leader-election key; the service name is appended. */
    static final String LEADER_KEY_PREFIX = "maestro:leader:retention:";

    /** TTL of the leadership claim; renewed by every batch's re-check. */
    static final Duration LEADER_TTL = Duration.ofSeconds(15);

    private final WorkflowStore store;
    private final DistributedLock distributedLock;
    private final String serviceName;
    private final WorkflowExecutor.RetentionSettings settings;
    private final EngineObserver observer;
    private final BatchPacer pacer;
    private final String candidateId;

    /**
     * Creates a new retention sweeper.
     *
     * @param store           the store whose terminal workflows are purged
     * @param distributedLock lock backend for leader election; never {@code null}
     * @param serviceName     the owning service name (suffix of the election key)
     * @param settings        interval, per-status retention and batching
     * @param observer        receives one {@code retentionPass} per sweep run as leader
     * @throws NullPointerException     if {@code store}, {@code distributedLock},
     *                                  {@code settings} or {@code observer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank
     */
    RetentionSweeper(WorkflowStore store,
                     DistributedLock distributedLock,
                     String serviceName,
                     WorkflowExecutor.RetentionSettings settings,
                     EngineObserver observer) {
        this(store, distributedLock, serviceName, settings, observer, new BatchPacer());
    }

    /**
     * Creates a new retention sweeper with the given pacer (a test seam).
     *
     * @param store           the store whose terminal workflows are purged
     * @param distributedLock lock backend for leader election; never {@code null}
     * @param serviceName     the owning service name (suffix of the election key)
     * @param settings        interval, per-status retention and batching
     * @param observer        receives one {@code retentionPass} per sweep run as leader
     * @param pacer           times each batch and pauses between batches
     * @throws NullPointerException     if {@code store}, {@code distributedLock}, {@code settings},
     *                                  {@code observer} or {@code pacer} is {@code null}
     * @throws IllegalArgumentException if {@code serviceName} is null or blank
     */
    RetentionSweeper(WorkflowStore store,
                     DistributedLock distributedLock,
                     String serviceName,
                     WorkflowExecutor.RetentionSettings settings,
                     EngineObserver observer,
                     BatchPacer pacer) {
        if (store == null) throw new NullPointerException("store must not be null");
        if (distributedLock == null) throw new NullPointerException("distributedLock must not be null");
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
        this.observer = CompositeEngineObserver.contain(observer);
        this.pacer = pacer;
        this.candidateId = UUID.randomUUID().toString();
    }

    /**
     * How the sweeper is supervised: housekeeping; the first sweep at once,
     * then one every {@code settings.interval()}, failed or not; failing after
     * two failed sweeps in a row; a stop also ends a pause between two batches.
     *
     * @return the sweeper's loop spec
     */
    LoopSpec spec() {
        return LoopSpec.of(LOOP_NAME, THREAD_NAME, LoopRole.HOUSEKEEPING, settings.interval())
                .withOnStop(pacer::requestStop);
    }

    @Override
    public Next iterate(LoopContext ctx) {
        var swept = sweepOnce();
        ctx.note("leader", swept.isPresent());
        return Next.afterPeriod();
    }

    /**
     * Runs one sweep: for each terminal status, purges batches of instances
     * finished before {@code now - <that status's retention>} until a batch
     * comes back short or the per-status batch budget is spent, pausing
     * between batches (see the class Javadoc) and re-checking leadership after
     * each pause, right before the batch. A stop request ends a pause at once
     * and is checked before every batch, so a sweep never runs on past
     * shutdown for up to {@code maxBatchesPerSweep} more batches.
     *
     * @return the summed counts of this sweep if this node was the leader when
     *         it began (possibly {@link PurgeResult#EMPTY}); empty if another
     *         node holds leadership
     * @throws RuntimeException what the store or the lock backend threw; a
     *         sweep this node ran as leader reports what it purged so far
     *         ({@code retentionPass}) first
     */
    Optional<PurgeResult> sweepOnce() {
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
                    if ((previous >= 0 && !pacer.pauseAfter(previous)) || pacer.stopRequested()) {
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
            if (leader) {
                // A partial sweep is still a sweep this node ran: report what it purged.
                finish(total, batches);
            }
            throw e;
        }
    }

    /** The settings, for the start line. */
    String describeSettings() {
        return ("interval=%s, completedRetention=%s, terminatedRetention=%s, failedRetention=%s, batchSize=%d, "
                + "maxBatchesPerSweep=%d, service=%s").formatted(settings.interval(), settings.completedRetention(),
                settings.terminatedRetention(), settings.failedRetention(), settings.batchSize(),
                settings.maxBatchesPerSweep(), serviceName);
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
        observer.retentionPass(total);
        return Optional.of(total);
    }

    /**
     * Checks whether this node is the retention leader, electing or renewing
     * in the same call (see {@link TimerPoller}). A lock-backend fault
     * propagates: it fails the sweep.
     */
    private boolean isLeader() {
        return distributedLock.trySetLeader(LEADER_KEY_PREFIX + serviceName, candidateId, LEADER_TTL);
    }
}
