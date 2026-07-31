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
            try {
                Thread.sleep(INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
