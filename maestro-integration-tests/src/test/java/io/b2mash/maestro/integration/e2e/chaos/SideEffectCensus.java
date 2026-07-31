package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue 11 duplicate side-effect measurement (chaos-harness-design.md §7).
 * Counts the three loan-funding side effects (rate-lock reservation,
 * disbursement, rate-lock-release compensation) per workflow across all node
 * logs (all container generations), computes duplicates, and correlates each
 * duplicate against the chaos action log: <em>explained</em> if the workflow
 * wrote events during a lock-loss-with-live-process window (a loan node
 * {@code PAUSE_RESUME}/{@code PARTITION}, or {@code BACKEND_OUTAGE(valkey)}),
 * else <em>unexplained</em>.
 *
 * <p>Duplicates never fail the run (ruling Q8). Explained duplicates are the
 * Issue 11 evidence base; unexplained duplicates are surfaced prominently as
 * candidate defects for triage. The census is written to
 * {@code side-effects.json}.
 *
 * <h2>Thread Safety</h2>
 * <p>Invoked once, after drain, from the orchestrator thread.
 */
public final class SideEffectCensus {

    private static final Logger log = LoggerFactory.getLogger(SideEffectCensus.class);

    private final ChaosCluster cluster;
    private final EvidenceWriter evidence;
    private final List<LedgerEntry> ledger;

    /**
     * @param cluster  the cluster (node logs, loan DB)
     * @param evidence evidence writer (reads the action log, writes the census)
     * @param ledger   the workload ledger
     */
    public SideEffectCensus(ChaosCluster cluster, EvidenceWriter evidence, List<LedgerEntry> ledger) {
        this.cluster = cluster;
        this.evidence = evidence;
        this.ledger = ledger;
    }

    /** Outcome summary the orchestrator surfaces. */
    public record Result(long totalDuplicates, long explained, long unexplained,
                         List<String> unexplainedWorkflows, List<String> missingSagaCompensation) {
    }

    /**
     * Runs the census and writes {@code side-effects.json}.
     *
     * @return the summary result
     */
    public Result run() {
        String allLogs = readAllLoanLogs();
        List<Window> windows = loadQualifyingWindows();

        var perEffectTotals = new LinkedHashMap<String, Long>();
        var perPath = new LinkedHashMap<String, Map<String, Long>>();
        var perWorkflow = new ArrayList<Map<String, Object>>();
        var unexplainedWorkflows = new ArrayList<String>();
        var missingSagaCompensation = new ArrayList<String>();
        long totalDup = 0;
        long explained = 0;
        long unexplained = 0;

        for (LedgerEntry e : ledger) {
            String id = e.applicationId();
            long rateLock = count(allLogs, "Reserved rate lock", id);
            long disburse = count(allLogs, "Disbursed", id);
            long comp = count(allLogs, "COMPENSATION: released rate lock", id);

            perEffectTotals.merge("rateLock", rateLock, Long::sum);
            perEffectTotals.merge("disbursement", disburse, Long::sum);
            perEffectTotals.merge("compensation", comp, Long::sum);
            var pathEffects = perPath.computeIfAbsent(e.path().name(), k -> new LinkedHashMap<>());
            pathEffects.merge("rateLock", rateLock, Long::sum);
            pathEffects.merge("disbursement", disburse, Long::sum);
            pathEffects.merge("compensation", comp, Long::sum);

            // A leak only if a rate lock was actually reserved but never released;
            // a SAGA that failed earlier (before reservation) needs no compensation.
            if (e.compensationExpected() && rateLock >= 1 && comp < 1) {
                missingSagaCompensation.add(e.workflowId());
            }

            long dups = Math.max(0, rateLock - 1) + Math.max(0, disburse - 1) + Math.max(0, comp - 1);
            if (dups > 0) {
                boolean explainedByChaos = wroteEventsDuringWindow(e.workflowId(), windows);
                totalDup += dups;
                if (explainedByChaos) {
                    explained += dups;
                } else {
                    unexplained += dups;
                    unexplainedWorkflows.add(e.workflowId());
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("workflowId", e.workflowId());
                row.put("path", e.path().name());
                row.put("rateLock", rateLock);
                row.put("disbursement", disburse);
                row.put("compensation", comp);
                row.put("duplicates", dups);
                row.put("verdict", explainedByChaos ? "explained" : "unexplained");
                perWorkflow.add(row);
            }
        }

        var census = new LinkedHashMap<String, Object>();
        census.put("perEffectTotals", perEffectTotals);
        census.put("perPathBreakdown", perPath);
        census.put("duplicatedWorkflows", perWorkflow);
        census.put("totalDuplicates", totalDup);
        census.put("explainedDuplicates", explained);
        census.put("unexplainedDuplicates", unexplained);
        census.put("unexplainedWorkflows", unexplainedWorkflows);
        census.put("missingSagaCompensation", missingSagaCompensation);
        evidence.writeJson("side-effects.json", census);

        log.info("[chaos] side-effect census: totalDup={} explained={} unexplained={} "
                + "missingSagaComp={}", totalDup, explained, unexplained, missingSagaCompensation.size());
        return new Result(totalDup, explained, unexplained, unexplainedWorkflows, missingSagaCompensation);
    }

    // ------------------------------------------------------------------ helpers

    private record Window(Instant start, Instant end) {
    }

    private List<Window> loadQualifyingWindows() {
        var windows = new ArrayList<Window>();
        Path actionLog = evidence.runDir().resolve("chaos-actions.jsonl");
        if (!Files.exists(actionLog)) {
            return windows;
        }
        try {
            for (String line : Files.readString(actionLog).split("\\R")) {
                if (line.isBlank() || line.contains("\"_identity\"")) {
                    continue;
                }
                JsonNode n = evidence.mapper().readTree(line);
                String action = text(n, "action");
                String target = text(n, "target");
                boolean qualifies =
                        (("PAUSE_RESUME".equals(action) || "PARTITION".equals(action))
                                && target != null && target.startsWith("LOAN_"))
                        || ("BACKEND_OUTAGE".equals(action) && "valkey".equals(target));
                if (qualifies) {
                    windows.add(new Window(Instant.parse(text(n, "startedTs")),
                            Instant.parse(text(n, "healedTs"))));
                }
            }
        } catch (Exception e) {
            log.warn("[chaos] could not parse action log for correlation: {}", e.toString());
        }
        return windows;
    }

    private boolean wroteEventsDuringWindow(String workflowId, List<Window> windows) {
        if (windows.isEmpty()) {
            return false;
        }
        try (Connection c = cluster.dataSource(Service.LOAN_APPLICATION).getConnection();
             var ps = c.prepareStatement(
                     "SELECT e.created_at FROM maestro_workflow_event e "
                     + "JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id "
                     + "WHERE i.workflow_id = ?")) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant t = rs.getTimestamp(1).toInstant();
                    for (Window w : windows) {
                        if (!t.isBefore(w.start()) && !t.isAfter(w.end())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[chaos] correlation query failed for {}: {}", workflowId, e.toString());
        }
        return false;
    }

    private String readAllLoanLogs() {
        var sb = new StringBuilder();
        for (NodeRole role : List.of(NodeRole.LOAN_A, NodeRole.LOAN_B)) {
            for (Path file : cluster.logFiles(role)) {
                try {
                    if (Files.exists(file)) {
                        sb.append(Files.readString(file)).append('\n');
                    }
                } catch (Exception ignore) {
                    // unreadable generation
                }
            }
        }
        return sb.toString();
    }

    /**
     * Counts lines carrying {@code marker} and the exact loan id — with a
     * boundary check so {@code chaos-101-1} never matches a
     * {@code chaos-101-10} line (the id is always followed by a space or end
     * of line in the {@code SimulatedFundingActivities} messages).
     */
    private long count(String logs, String marker, String loanId) {
        var pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote("for loan " + loanId) + "(?![\\w-])");
        long count = 0;
        for (String line : logs.split("\\R")) {
            if (line.contains(marker) && pattern.matcher(line).find()) {
                count++;
            }
        }
        return count;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
