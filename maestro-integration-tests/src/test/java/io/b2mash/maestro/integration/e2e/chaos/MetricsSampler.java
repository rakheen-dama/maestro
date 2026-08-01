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
 * snapshots recovery-query rate ({@code pg_stat_statements}), lock probe/renew
 * and wake-subscription churn (Valkey {@code INFO commandstats} / {@code PUBSUB})
 * and parked/running counts every window, appending a row to {@code metrics.csv}.
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
            + "lockProbeCalls,lockRenewCalls,subscribeCalls,unsubscribeCalls,pubsubChannels,chaosActive";

    // The normalised recovery-poll statement (AbstractJdbcWorkflowStore
    // .getRecoverableInstances): FROM maestro_workflow_instance WHERE status IN
    // (...) ORDER BY started_at ASC. Pinned by text; the sampler warns loudly if
    // it never matches (Risk 3 — the store SQL changed).
    private static final String RECOVERY_CALLS_SQL =
            "SELECT COALESCE(SUM(calls),0) FROM pg_stat_statements "
            + "WHERE query LIKE '%maestro_workflow_instance WHERE status IN%' "
            + "AND query LIKE '%ORDER BY started_at ASC%'";

    private final ChaosCluster cluster;
    private final ChaosConfig config;
    private final EvidenceWriter.CsvWriter csv;
    private final EvidenceWriter.CsvWriter heapCsv;
    private volatile boolean running;
    private Thread thread;

    private long lastRecoveryCalls = -1;
    private final Map<String, Long> lastCmdCalls = new HashMap<>();
    private boolean recoveryEverMatched;

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

    /** Stops the sampler thread and closes the CSV. */
    public void stop() {
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

        Map<String, Long> cmd = valkeyCommandCalls();
        long lockProbe = delta(cmd, "set");
        long lockRenew = delta(cmd, "evalsha") + delta(cmd, "eval");
        long subscribe = delta(cmd, "subscribe");
        long unsubscribe = delta(cmd, "unsubscribe");
        lastCmdCalls.putAll(cmd);
        long pubsubChannels = pubsubChannels();

        String row = String.join(",",
                windowStart, str(windowSecs), str(liveNodes),
                str(statusCounts.getOrDefault("RUNNING", 0L)),
                str(statusCounts.getOrDefault("WAITING_SIGNAL", 0L)),
                str(statusCounts.getOrDefault("WAITING_TIMER", 0L)),
                str(statusCounts.getOrDefault("COMPENSATING", 0L)),
                str(recoveryDelta), String.format("%.3f", recoveryRate),
                str(lockProbe), str(lockRenew), str(subscribe), str(unsubscribe),
                str(pubsubChannels), Boolean.toString(chaosActive));
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
        for (Service svc : Service.values()) {
            try (Connection c = cluster.dataSource(svc).getConnection();
                 var st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT status, COUNT(*) FROM maestro_workflow_instance GROUP BY status")) {
                while (rs.next()) {
                    totals.merge(rs.getString(1), rs.getLong(2), Long::sum);
                }
            } catch (Exception e) {
                // a backend blip; this window's counts are best-effort
            }
        }
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
                return calls;
            }
        } catch (Exception e) {
            log.debug("[chaos] recovery-calls query failed: {}", e.toString());
        }
        return lastRecoveryCalls < 0 ? 0 : lastRecoveryCalls;
    }

    /** Parses {@code cmdstat_<cmd>:calls=N,...} from Valkey {@code INFO commandstats}. */
    private Map<String, Long> valkeyCommandCalls() {
        var out = new HashMap<String, Long>();
        String info = cluster.valkeyCli("INFO", "commandstats");
        for (String line : info.split("\\R")) {
            if (!line.startsWith("cmdstat_")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String cmd = line.substring("cmdstat_".length(), colon);
            for (String field : line.substring(colon + 1).split(",")) {
                if (field.startsWith("calls=")) {
                    try {
                        out.put(cmd, Long.parseLong(field.substring("calls=".length())));
                    } catch (NumberFormatException ignore) {
                        // skip
                    }
                }
            }
        }
        return out;
    }

    private long pubsubChannels() {
        String out = cluster.valkeyCli("PUBSUB", "CHANNELS");
        if (out.isBlank()) {
            return 0;
        }
        return out.split("\\R").length;
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
