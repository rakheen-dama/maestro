package io.b2mash.maestro.integration.e2e.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Runs the read-only periodic invariant checks during a run
 * (chaos-harness-design.md §5): every 30s it evaluates the always-inexcusable
 * invariants (I3a duplicate events, I5 admin rows) and — only in calm windows,
 * when no chaos action is active — the stuck-{@code WAITING_TIMER} invariant
 * (I2). Findings are logged prominently for early visibility; the authoritative
 * post-drain check is the run's pass/fail gate, so this thread never fails the
 * run directly (avoiding teardown races).
 *
 * <h2>Thread Safety</h2>
 * <p>Runs on its own daemon thread; reads the driver's thread-safe ledger
 * snapshot each cycle.
 */
public final class PeriodicChecker {

    private static final Logger log = LoggerFactory.getLogger(PeriodicChecker.class);
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final ChaosCluster cluster;
    private final EvidenceWriter evidence;
    private final WorkloadDriver driver;
    private volatile boolean running;
    private Thread thread;

    // DB-visibility accounting (soak-failure fix, report §10): a checker that
    // silently cannot reach the store must not look like health. Each cycle
    // probes all three databases with SELECT 1; unreachable cycles are counted,
    // streaks are surfaced at ERROR level, and the totals land in the run
    // summary via the getters below.
    private final java.util.concurrent.atomic.AtomicInteger unreachableCycles =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger maxUnreachableStreak =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger totalCycles =
            new java.util.concurrent.atomic.AtomicInteger();
    private int currentStreak;

    /**
     * @param cluster  the cluster
     * @param evidence evidence writer
     * @param driver   the driver (ledger source)
     */
    public PeriodicChecker(ChaosCluster cluster, EvidenceWriter evidence, WorkloadDriver driver) {
        this.cluster = cluster;
        this.evidence = evidence;
        this.driver = driver;
    }

    /** Starts the periodic checker thread. */
    public void start() {
        running = true;
        thread = new Thread(this::loop, "chaos-periodic-checker");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the periodic checker thread. */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running) {
            totalCycles.incrementAndGet();
            if (probeDatabases()) {
                currentStreak = 0;
                try {
                    var checker = new InvariantChecker(cluster, evidence, driver.ledger());
                    checker.checkAlwaysInexcusable().forEach(v ->
                            log.warn("[chaos] PERIODIC inexcusable [{}] {} -> {}",
                                    v.invariant(), v.detail(), v.workflowIds()));
                    if (!cluster.anyChaosActive()) {
                        checker.checkStuckWaitingTimer().forEach(v ->
                                log.warn("[chaos] PERIODIC calm-window [{}] {} -> {}",
                                        v.invariant(), v.detail(), v.workflowIds()));
                    }
                } catch (RuntimeException e) {
                    log.debug("[chaos] periodic check cycle failed: {}", e.toString());
                }
            } else {
                unreachableCycles.incrementAndGet();
                currentStreak++;
                maxUnreachableStreak.accumulateAndGet(currentStreak, Math::max);
                // One line per cycle at WARN; escalate loudly once the outage
                // outlives any bounded BACKEND_OUTAGE window (>= 2 cycles = 60s
                // vs the 30s outage cap) — this is the "silence looked like
                // health" failure mode made explicit.
                if (currentStreak >= 2) {
                    log.error("[chaos] PERIODIC CHECKER BLIND: store unreachable for {} "
                            + "consecutive cycles (~{}s) — exceeds any bounded backend outage; "
                            + "invariants are NOT being watched", currentStreak,
                            currentStreak * INTERVAL.toSeconds());
                } else {
                    log.warn("[chaos] periodic checker: store unreachable this cycle "
                            + "(expected only inside a bounded backend-outage window)");
                }
            }
            try {
                Thread.sleep(INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** @return true if all three service databases answer {@code SELECT 1}. */
    private boolean probeDatabases() {
        for (var svc : NodeRole.Service.values()) {
            try (var c = cluster.dataSource(svc).getConnection();
                 var st = c.createStatement()) {
                st.execute("SELECT 1");
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** @return cycles in which the store was unreachable (run-summary field). */
    public int unreachableCycles() {
        return unreachableCycles.get();
    }

    /** @return the longest consecutive unreachable streak (run-summary field). */
    public int maxUnreachableStreak() {
        return maxUnreachableStreak.get();
    }

    /** @return total periodic cycles executed. */
    public int totalCycles() {
        return totalCycles.get();
    }
}
