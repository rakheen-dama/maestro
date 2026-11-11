package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Issue 12 metrics capture (chaos-harness-design.md §6): a sampler thread that
 * snapshots recovery-query rate, lock probe/renew/held-keys/leader-election churn and
 * {@code LISTEN}/{@code NOTIFY} wake traffic (all from {@code pg_stat_statements}
 * — Postgres carries store, lock and signal wake, so one cluster-wide view
 * covers every backend-side rate) plus parked/running counts every window,
 * appending a row to {@code metrics.csv}.
 *
 * <p>Measurement is backend-side (no engine seam, ruling Q10). Per-node rates
 * are {@code clusterRate / liveNodes}, valid only in calm windows
 * ({@code chaosActive=false}); the {@code chaosActive} and {@code liveNodes}
 * columns let the benchmark be computed from designated calm windows rather than
 * mid-chaos noise.
 *
 * <h2>Thread Safety</h2>
 * <p>Runs on its own daemon thread; {@link #start()} / {@link #stop()} bracket
 * its lifecycle. The CSV writer serialises its own appends.
 */
public final class MetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(MetricsSampler.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final String HEADER = "windowStartUtc,windowSecs,liveNodes,running,"
            + "waitingSignal,waitingTimer,compensating,recoveryCalls,recoveryRatePerSec,"
            + "lockProbeCalls,lockRenewCalls,lockHeldKeysCalls,leaderElectionCalls,listenCalls,"
            + "notifyCalls,chaosActive";

    // The normalised recovery-poll statements (AbstractJdbcWorkflowStore), summed:
    //  - paged, service-scoped scan (the engine's since migration V101), two shapes — first
    //    page and cursor page — each normalised as "... FROM maestro_workflow_instance
    //    WHERE service_name = $1 AND status IN ($n, ...) [AND (started_at, id) > ($2, $3)]
    //    ORDER BY started_at, id LIMIT $n". One call per PAGE: a pass over <= 200
    //    of a service's active instances is one call, as the old scan was.
    //  - legacy unbounded scan, "... FROM maestro_workflow_instance WHERE status IN
    //    ($1, ...) ORDER BY started_at ASC", still sent by a pre-change node, so a
    //    mixed-version or older run reads correctly.
    // The two never match the same statement (WHERE service_name vs WHERE status).
    // Pinned by text against the real statements in MetricsSamplerStatementMatchTest;
    // the sampler still warns loudly if neither ever matches (Risk 3).
    static final String RECOVERY_CALLS_SQL =
            "SELECT COALESCE(SUM(calls),0) FROM pg_stat_statements WHERE "
            + "(query LIKE '%FROM maestro_workflow_instance WHERE service_name = %' "
            + "AND query LIKE '%ORDER BY started_at, id LIMIT %') "
            + "OR (query LIKE '%maestro_workflow_instance WHERE status IN%' "
            + "AND query LIKE '%ORDER BY started_at ASC%')";

    // Postgres-lock and LISTEN/NOTIFY statement shapes (PostgresDistributedLock,
    // PostgresSignalNotifier in maestro-store-postgres), pinned by text like the
    // recovery poll above. pg_stat_statements is cluster-wide, so one query from
    // any loan database sees every node's statements; its own literals are
    // normalised to $n so it never matches itself. LISTEN/NOTIFY are utility
    // statements, counted because the cluster starts Postgres with
    // pg_stat_statements.track_utility=on. "heldKeys" is the recovery pass's
    // one advisory read per page (PostgresDistributedLock.heldKeys, normalised
    // "SELECT lock_key FROM maestro_distributed_lock WHERE lock_key = ANY($1) AND
    // expires_at > now()"); it replaces the per-instance acquire probes for
    // instances another node holds, so a drop in lockProbeCalls shows up next to
    // it in lockHeldKeysCalls rather than unexplained.
    static final String LOCK_CALLS_SQL =
            "SELECT "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE '%INSERT INTO maestro_distributed_lock%'), 0), "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE '%UPDATE maestro_distributed_lock%'), 0), "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE '%FROM maestro_distributed_lock%'"
            + " AND query LIKE '%lock_key = ANY(%'), 0), "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE '%maestro_leader_election%'), 0), "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE 'LISTEN %'), 0), "
            + "COALESCE(SUM(calls) FILTER (WHERE query LIKE 'NOTIFY %'), 0) "
            + "FROM pg_stat_statements";
    static final String[] LOCK_CALL_KEYS = {"acquire", "renew", "heldKeys", "leader", "listen", "notify"};

    private final ChaosCluster cluster;
    private final ChaosConfig config;
    private final EvidenceWriter.CsvWriter csv;
    private final EvidenceWriter.CsvWriter heapCsv;
    private volatile boolean running;
    private volatile boolean stopped;
    private Thread thread;

    private long lastRecoveryCalls = -1;
    private final Map<String, Long> lastCmdCalls = new HashMap<>();
    private boolean recoveryEverMatched;
    // First-of-streak cause logging (investigation §7: the sampler's silent
    // per-window catch hid WHY every DB-derived column dropped to zero at the
    // soak's blindness knee). Sampler-thread confined.
    private boolean statusProbeFailingStreak;
    private boolean recoveryProbeFailingStreak;
    private boolean lockProbeFailingStreak;

    /**
     * @param cluster  the live cluster
     * @param config   run configuration (window size)
     * @param evidence evidence writer (opens {@code metrics.csv})
     */
    public MetricsSampler(ChaosCluster cluster, ChaosConfig config, EvidenceWriter evidence) {
        this.cluster = cluster;
        this.config = config;
        this.csv = evidence.openCsv("metrics.csv", HEADER);
        // Test-JVM heap telemetry (soak-failure diagnosis, report §10): one row
        // per metrics window so unbounded accumulation shows as a rising curve
        // in the evidence rather than as a terminal OOM 28 minutes in.
        this.heapCsv = evidence.openCsv("heap-samples.csv",
                "timestampUtc,usedMb,committedMb,maxMb");
    }

    /** Starts the sampler thread. */
    public void start() {
        running = true;
        thread = new Thread(this::loop, "chaos-metrics-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the sampler thread and closes the CSV. Idempotent (failure-path teardown re-invokes it). */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!recoveryEverMatched) {
            log.warn("[chaos] METRICS: recovery-poll statement never matched pg_stat_statements — "
                    + "the store SQL may have changed (Risk 3); recovery rate column is unreliable");
        }
        csv.close();
        heapCsv.close();
    }

    private void loop() {
        long windowSecs = config.metricsWindow().toSeconds();
        while (running) {
            try {
                sample(windowSecs);
            } catch (RuntimeException e) {
                log.warn("[chaos] metrics sample failed: {}", e.toString());
            }
            sampleHeap();
            try {
                Thread.sleep(config.metricsWindow().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sample(long windowSecs) {
        String windowStart = ISO.format(Instant.now());
        int liveNodes = cluster.liveNodeCount();
        boolean chaosActive = cluster.anyChaosActive();

        Map<String, Long> statusCounts = statusCounts();
        long recoveryCalls = recoveryCalls();
        long recoveryDelta = lastRecoveryCalls < 0 ? 0 : Math.max(0, recoveryCalls - lastRecoveryCalls);
        lastRecoveryCalls = recoveryCalls;
        double recoveryRate = (double) recoveryDelta / windowSecs;

        Map<String, Long> cmd = lockStatementCalls();
        long lockProbe = delta(cmd, "acquire");
        long lockRenew = delta(cmd, "renew");
        long lockHeldKeys = delta(cmd, "heldKeys");
        long leaderElection = delta(cmd, "leader");
        long listen = delta(cmd, "listen");
        long notify = delta(cmd, "notify");
        lastCmdCalls.putAll(cmd);

        String row = String.join(",",
                windowStart, str(windowSecs), str(liveNodes),
                str(statusCounts.getOrDefault("RUNNING", 0L)),
                str(statusCounts.getOrDefault("WAITING_SIGNAL", 0L)),
                str(statusCounts.getOrDefault("WAITING_TIMER", 0L)),
                str(statusCounts.getOrDefault("COMPENSATING", 0L)),
                str(recoveryDelta), String.format("%.3f", recoveryRate),
                str(lockProbe), str(lockRenew), str(lockHeldKeys), str(leaderElection), str(listen),
                str(notify), Boolean.toString(chaosActive));
        csv.append(row);
    }

    /** Appends one test-JVM heap sample row (used/committed/max, MB). */
    private void sampleHeap() {
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long committedMb = rt.totalMemory() / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            heapCsv.append(ISO.format(Instant.now()) + "," + usedMb + "," + committedMb + "," + maxMb);
        } catch (RuntimeException e) {
            log.debug("[chaos] heap sample failed: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- sources

    private Map<String, Long> statusCounts() {
        var totals = new HashMap<String, Long>();
        boolean anyFailed = false;
        for (Service svc : Service.values()) {
            try (Connection c = cluster.dataSource(svc).getConnection();
                 var st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT status, COUNT(*) FROM maestro_workflow_instance GROUP BY status")) {
                while (rs.next()) {
                    totals.merge(rs.getString(1), rs.getLong(2), Long::sum);
                }
            } catch (Exception e) {
                // Counts stay best-effort per window, but the CAUSE is logged
                // (class + message) on the first failure of each streak.
                if (!statusProbeFailingStreak && !anyFailed) {
                    log.warn("[chaos] metrics: status-count probe failed for {} — {} "
                            + "(first of streak; DB-derived columns are zero/stale while "
                            + "this persists)", svc, e.toString());
                }
                anyFailed = true;
            }
        }
        statusProbeFailingStreak = anyFailed;
        return totals;
    }

    private long recoveryCalls() {
        try (Connection c = cluster.dataSource(Service.LOAN_APPLICATION).getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(RECOVERY_CALLS_SQL)) {
            if (rs.next()) {
                long calls = rs.getLong(1);
                if (calls > 0) {
                    recoveryEverMatched = true;
                }
                recoveryProbeFailingStreak = false;
                return calls;
            }
        } catch (Exception e) {
            if (!recoveryProbeFailingStreak) {
                log.warn("[chaos] metrics: recovery-calls probe failed — {} "
                        + "(first of streak)", e.toString());
                recoveryProbeFailingStreak = true;
            }
        }
        return lastRecoveryCalls < 0 ? 0 : lastRecoveryCalls;
    }

    /**
     * Cumulative {@code pg_stat_statements} call counts for the Postgres-lock and
     * LISTEN/NOTIFY statement shapes, keyed by {@link #LOCK_CALL_KEYS}. On a probe
     * failure (Postgres paused) the previous cumulative values are returned so
     * the window's deltas read as zero rather than as a negative/garbage spike.
     */
    private Map<String, Long> lockStatementCalls() {
        var out = new HashMap<String, Long>();
        try (Connection c = cluster.dataSource(Service.LOAN_APPLICATION).getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(LOCK_CALLS_SQL)) {
            if (rs.next()) {
                for (int i = 0; i < LOCK_CALL_KEYS.length; i++) {
                    out.put(LOCK_CALL_KEYS[i], rs.getLong(i + 1));
                }
                lockProbeFailingStreak = false;
                return out;
            }
        } catch (Exception e) {
            if (!lockProbeFailingStreak) {
                log.warn("[chaos] metrics: lock-calls probe failed — {} "
                        + "(first of streak)", e.toString());
                lockProbeFailingStreak = true;
            }
        }
        out.putAll(lastCmdCalls);
        return out;
    }

    private long delta(Map<String, Long> current, String cmd) {
        long now = current.getOrDefault(cmd, 0L);
        long prev = lastCmdCalls.getOrDefault(cmd, 0L);
        return Math.max(0, now - prev);
    }

    private static String str(long v) {
        return Long.toString(v);
    }
}
