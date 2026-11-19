package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.UUID;

/**
 * The timer poller's iteration: scans for due timers and fires them. A
 * {@link LoopBody} run under the executor's
 * {@link io.b2mash.maestro.core.loop.LoopSupervisor} as loop
 * {@value #LOOP_NAME} ({@link #spec()}), which owns its thread, its wait, its
 * failure streak and its stop — never an interrupt (Issue 64).
 *
 * <h2>Leader Election</h2>
 * <p>The poller calls
 * {@link DistributedLock#trySetLeader(String, String, Duration)} on each
 * iteration with key {@code maestro:leader:timer-poller:{serviceName}}
 * and 15-second TTL. The implementation should return {@code true} both
 * when newly elected and when the candidate is already the current leader
 * (effectively renewing the TTL). A {@link DistributedLock} is mandatory
 * (pilot readiness P3): there is no single-node mode that polls unelected.
 * The loop notes {@code leader: true|false} after each election.
 *
 * <h2>Clock</h2>
 * <p>A due timer is one the store says is due by its own clock
 * ({@link WorkflowStore#getDueTimers(int)}). On Postgres that is the
 * database's clock ({@code fire_at <= now()}), so this leader's own clock
 * does not decide when a timer fires. On a store without a clock of its own
 * (the in-memory test store, a custom store) it is this JVM's clock, as
 * before. A timer's {@code fireAt} itself is still armed by the node that
 * schedules it, on that node's clock; so every node, leader or not, runs a
 * {@link ClockSkewCheck} at the end of each iteration (at start, then
 * every {@link ClockSkewCheck#INTERVAL}) and logs a {@code WARN} when its clock
 * is more than {@link ClockSkewCheck#WARN_ABOVE} off the store's — also when
 * the election or the poll threw.
 *
 * <h2>Idempotency</h2>
 * <p>Duplicate timer fires are handled safely:
 * <ul>
 *   <li>{@link WorkflowStore#markTimerFired} uses compare-and-set semantics
 *       ({@code PENDING → FIRED}) and returns {@code false} if the timer was
 *       already fired or cancelled.</li>
 *   <li>{@link WorkflowExecutor#fireTimer} only unparks the workflow when
 *       the CAS succeeds — a duplicate or cancelled fire is a no-op.</li>
 * </ul>
 *
 * <h2>Failures</h2>
 * <p>An iteration that throws — a store or lock-backend fault, or any
 * {@code Error} but a {@code MaestroControlFlowError} — is a failed iteration
 * the supervisor counts and logs at its cadence, and the poller tries again
 * after its own {@code pollInterval} (5 s by default). A timer whose fire
 * throws is logged and skipped; the batch goes on. A stop ends a batch at the
 * next timer.
 *
 * <h2>Thread Safety</h2>
 * <p>The supervisor runs {@link #iterate} on the loop's thread only.
 *
 * @see WorkflowExecutor#fireTimer(String, String, UUID)
 */
final class TimerPoller implements LoopBody {

    private static final Logger logger = LoggerFactory.getLogger(TimerPoller.class);
    private static final Duration LEADER_TTL = Duration.ofSeconds(15);
    private static final String LEADER_KEY_PREFIX = "maestro:leader:timer-poller:";

    /** The loop's fixed name: its meter tag and log name. */
    static final String LOOP_NAME = "timer-poller";

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final DistributedLock distributedLock;
    private final String serviceName;
    private final Duration pollInterval;
    private final int batchSize;
    private final String candidateId;
    private final ClockSkewCheck skewCheck;

    /**
     * Creates a new timer poller.
     *
     * @param store           workflow store for timer queries
     * @param executor        workflow executor for firing timers
     * @param distributedLock lock backend for leader election; never {@code null}
     * @param serviceName     the owning service name (for leader election key)
     * @param pollInterval    interval between polling cycles
     * @param batchSize       maximum timers to process per cycle
     */
    TimerPoller(
            WorkflowStore store,
            WorkflowExecutor executor,
            DistributedLock distributedLock,
            String serviceName,
            Duration pollInterval,
            int batchSize
    ) {
        if (store == null) throw new NullPointerException("store must not be null");
        if (executor == null) throw new NullPointerException("executor must not be null");
        if (distributedLock == null) throw new NullPointerException("distributedLock must not be null");
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, got " + batchSize);
        }
        this.store = store;
        this.executor = executor;
        this.distributedLock = distributedLock;
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.candidateId = UUID.randomUUID().toString();
        this.skewCheck = new ClockSkewCheck(store);
    }

    /**
     * How the poller is supervised: critical; the first poll at once, then one
     * every {@code pollInterval}, failed or not; failing after two failures in
     * a row spanning at least {@code max(60 s, 2 × pollInterval)}.
     *
     * @return the poller's loop spec
     */
    LoopSpec spec() {
        var twoPeriods = pollInterval.multipliedBy(2);
        var minute = Duration.ofSeconds(60);
        return LoopSpec.of(LOOP_NAME, "maestro-timer-poller-" + serviceName, LoopRole.CRITICAL, pollInterval)
                .withFailing(FailingRule.streakFor(2, twoPeriods.compareTo(minute) > 0 ? twoPeriods : minute));
    }

    /** The settings, for the start line. */
    String describeSettings() {
        return "interval=%s, batchSize=%d, service=%s".formatted(pollInterval, batchSize, serviceName);
    }

    @Override
    public Next iterate(LoopContext ctx) {
        // Leadership first: the skew check's SELECT now() can block on an
        // exhausted pool for longer than LEADER_TTL, so it runs after this
        // pass's renewal and poll. It still sits between this renewal and the
        // next one, so a blocked read can delay the NEXT renewal (at most once
        // per ClockSkewCheck.INTERVAL); losing the lease is safe, firing a
        // timer is a compare-and-set.
        try {
            var leader = isLeader();
            ctx.note("leader", leader);
            if (leader) {
                var fired = pollDueTimers(ctx);
                if (fired > 0) {
                    logger.debug("Fired {} due timer(s)", fired);
                }
            }
        } finally {
            // Every node, leader or not: every node arms deadlines (3.7). Runs
            // even when the election or the poll threw. Never throws a
            // RuntimeException; at most one store read per ClockSkewCheck.INTERVAL.
            skewCheck.maybeCheck();
        }
        return Next.afterPeriod();
    }

    private int pollDueTimers(LoopContext ctx) {
        var dueTimers = store.getDueTimers(batchSize);
        var fired = 0;

        for (var timer : dueTimers) {
            if (ctx.stopRequested()) {
                break; // a stop ends the batch at the next timer; the rest fire after the restart
            }
            try {
                MDC.put("workflowId", timer.workflowId());
                executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
                fired++;
            } catch (Exception e) {
                logger.error("Failed to fire timer {} (workflowId='{}', timerId='{}')",
                        timer.id(), timer.workflowId(), timer.timerId(), e);
                // Continue with remaining timers — don't let one failure stop the batch
            } finally {
                MDC.remove("workflowId");
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
     * {@code true} if this candidate is (or becomes) the leader. A backend
     * failure propagates: the iteration fails (the supervisor logs and counts
     * it) and skips this cycle's poll; the next cycle elects (or renews) again.
     */
    private boolean isLeader() {
        var elected = distributedLock.trySetLeader(LEADER_KEY_PREFIX + serviceName, candidateId, LEADER_TTL);
        if (!elected) {
            logger.debug("Another instance holds timer poller leadership for service '{}'", serviceName);
        }
        return elected;
    }
}
