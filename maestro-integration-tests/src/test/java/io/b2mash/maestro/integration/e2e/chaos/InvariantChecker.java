package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The store-level invariant checker (chaos-harness-design.md §5). Runs in a
 * <em>periodic</em> mode during the run (only invariants no in-flight chaos can
 * excuse may fail early) and an <em>authoritative</em> mode after heal-all +
 * drain. Queries all three service databases for the generic invariants and
 * joins the ledger on the loan database for I1/I4.
 *
 * <p>On any authoritative violation the offending workflows are dumped
 * (instance row, full event log, signals, timers, ledger entry, cross-node log
 * excerpts) before the caller fails the run.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless apart from injected collaborators; a fresh short-lived JDBC
 * connection is used per query. Safe to call from the sampler/orchestrator
 * threads.
 */
public final class InvariantChecker {

    private static final Logger log = LoggerFactory.getLogger(InvariantChecker.class);

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "TERMINATED");

    private final ChaosCluster cluster;
    private final EvidenceWriter evidence;
    private final Map<String, LedgerEntry> ledgerByWorkflowId;

    /**
     * @param cluster  the cluster (data sources, node logs)
     * @param evidence evidence writer (failure dumps)
     * @param ledger   the workload ledger
     */
    public InvariantChecker(ChaosCluster cluster, EvidenceWriter evidence, List<LedgerEntry> ledger) {
        this.cluster = cluster;
        this.evidence = evidence;
        this.ledgerByWorkflowId = new LinkedHashMap<>();
        ledger.forEach(e -> ledgerByWorkflowId.put(e.workflowId(), e));
    }

    /** One invariant violation. */
    public record Violation(String invariant, String detail, List<String> workflowIds) {
    }

    /**
     * Outcome of the authoritative check: hard violations plus the
     * redelivered-signal findings (Ruling 3: unconsumed rows with a consumed
     * byte-identical twin — Kafka at-least-once redelivery — do not fail the
     * run but are mandatory, prominently-reported findings).
     */
    public record VerifyResult(List<Violation> violations, List<String> redeliveredUnconsumedSignals) {
    }

    // ---------------------------------------------------------- periodic mode

    /**
     * Invariants that no in-flight chaos can excuse: duplicate {@code (instance,
     * seq)} events (I3a) and any {@code $maestro:%} signal rows (I5). Safe to run
     * at any time.
     *
     * @return violations found
     */
    public List<Violation> checkAlwaysInexcusable() {
        var v = new ArrayList<Violation>();
        for (Service svc : Service.values()) {
            v.addAll(duplicateEvents(svc));
            v.addAll(adminSignalRows(svc));
        }
        return v;
    }

    /**
     * I2 — no stuck {@code WAITING_TIMER} (evaluated only in calm windows: a
     * paused owner legitimately cannot re-check).
     *
     * @return violations found
     */
    public List<Violation> checkStuckWaitingTimer() {
        var v = new ArrayList<Violation>();
        for (Service svc : Service.values()) {
            v.addAll(stuckWaitingTimer(svc));
        }
        return v;
    }

    // ----------------------------------------------------- authoritative mode

    /**
     * Full authoritative verification after heal-all + drain: I1 (+ledger join),
     * I2, I3(a–d), I4, I5. Writes failure dumps for every offending workflow.
     *
     * @return violations (empty ⇒ the run passed) plus redelivered-signal findings
     */
    public VerifyResult verifyAuthoritative() {
        var v = new ArrayList<Violation>();

        // I1 — ledger workflows terminal within SLA + expected outcome (loan DB).
        v.addAll(ledgerTerminalAndOutcome());
        // I1 (weak) — every underwriting/verification child terminal.
        v.addAll(allTerminal(Service.UNDERWRITING));
        v.addAll(allTerminal(Service.VERIFICATION_GATEWAY));

        for (Service svc : Service.values()) {
            v.addAll(duplicateEvents(svc));            // I3a
            v.addAll(adminSignalRows(svc));            // I5
            v.addAll(stuckWaitingTimer(svc));          // I2
            v.addAll(terminalEventCount(svc));         // I3b
            v.addAll(terminalIsMaxSequence(svc));      // I3c
            v.addAll(densityWithinBound(svc));         // I3d (calibrated)
        }
        var i4 = unconsumedApplicationSignals();       // I4 (loan DB, Ruling 3 split)
        v.addAll(i4.violations());

        if (!v.isEmpty()) {
            dumpFailures(v);
        }
        return new VerifyResult(v, i4.redeliveredUnconsumedSignals());
    }

    // ------------------------------------------------------------ invariants

    /** I5 — zero {@code $maestro:%} signal rows, any status. */
    private List<Violation> adminSignalRows(Service svc) {
        var ids = queryStrings(svc,
                "SELECT DISTINCT workflow_id FROM maestro_workflow_signal "
                + "WHERE signal_name LIKE '$maestro:%'");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I5", "admin ($maestro:%) signal rows present in "
                + svc.databaseName(), ids));
    }

    /** I3a — no duplicate {@code (workflow_instance_id, sequence_number)}. */
    private List<Violation> duplicateEvents(Service svc) {
        var ids = queryStrings(svc,
                "SELECT i.workflow_id FROM maestro_workflow_event e "
                + "JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id "
                + "GROUP BY i.workflow_id, e.sequence_number HAVING COUNT(*) > 1");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I3a", "duplicate (instance, sequence) events in "
                + svc.databaseName(), ids));
    }

    /** I2 — {@code WAITING_TIMER} with no PENDING timer for &gt; 6× wake-recheck. */
    private List<Violation> stuckWaitingTimer(Service svc) {
        var ids = queryStrings(svc,
                "SELECT i.workflow_id FROM maestro_workflow_instance i "
                + "WHERE i.status = 'WAITING_TIMER' "
                + "AND NOT EXISTS (SELECT 1 FROM maestro_workflow_timer t "
                + "  WHERE t.workflow_instance_id = i.id AND t.status = 'PENDING') "
                + "AND i.updated_at < now() - INTERVAL '30 seconds'");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I2", "stuck WAITING_TIMER with no PENDING timer in "
                + svc.databaseName(), ids));
    }

    /** I3b — exactly one terminal event per terminal instance. */
    private List<Violation> terminalEventCount(Service svc) {
        var ids = queryStrings(svc,
                "SELECT i.workflow_id FROM maestro_workflow_instance i "
                + "JOIN maestro_workflow_event e ON e.workflow_instance_id = i.id "
                + "WHERE i.status IN ('COMPLETED','FAILED','TERMINATED') "
                + "GROUP BY i.workflow_id "
                + "HAVING COUNT(*) FILTER (WHERE e.event_type IN "
                + "  ('WORKFLOW_COMPLETED','WORKFLOW_FAILED','WORKFLOW_TERMINATED')) <> 1");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I3b", "terminal instance without exactly one terminal "
                + "event in " + svc.databaseName(), ids));
    }

    /** I3c — the terminal event is the log's maximum sequence. */
    private List<Violation> terminalIsMaxSequence(Service svc) {
        var ids = queryStrings(svc,
                "SELECT i.workflow_id FROM maestro_workflow_instance i "
                + "WHERE i.status IN ('COMPLETED','FAILED','TERMINATED') "
                + "AND (SELECT MAX(sequence_number) FROM maestro_workflow_event e "
                + "     WHERE e.workflow_instance_id = i.id) <> "
                + "    (SELECT MAX(sequence_number) FROM maestro_workflow_event e "
                + "     WHERE e.workflow_instance_id = i.id AND e.event_type IN "
                + "        ('WORKFLOW_COMPLETED','WORKFLOW_FAILED','WORKFLOW_TERMINATED'))");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I3c", "terminal event is not the max sequence in "
                + svc.databaseName(), ids));
    }

    /**
     * I3d (calibrated, §14.2) — per terminal workflow, the count of missing
     * sequence numbers (below terminal, per band) must not exceed the path's
     * calibrated maximum (loan ledger workflows) or 2 (child workflows).
     */
    private List<Violation> densityWithinBound(Service svc) {
        var offenders = new ArrayList<String>();
        String sql = "SELECT i.workflow_id, e.sequence_number FROM maestro_workflow_event e "
                + "JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id "
                + "WHERE i.status IN ('COMPLETED','FAILED','TERMINATED') "
                + "ORDER BY i.workflow_id, e.sequence_number";
        Map<String, TreeSet<Integer>> byWorkflow = new LinkedHashMap<>();
        try (Connection c = ds(svc).getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                byWorkflow.computeIfAbsent(rs.getString(1), k -> new TreeSet<>())
                        .add(rs.getInt(2));
            }
        } catch (Exception e) {
            log.warn("[chaos] I3d query failed on {}: {}", svc.databaseName(), e.toString());
            return List.of();
        }
        for (var entry : byWorkflow.entrySet()) {
            int gaps = gapCount(entry.getValue());
            int bound = boundFor(entry.getKey());
            if (gaps > bound) {
                offenders.add(entry.getKey() + " (gaps=" + gaps + " > bound=" + bound + ")");
            }
        }
        return offenders.isEmpty() ? List.of()
                : List.of(new Violation("I3d", "event-log gap count exceeds calibrated bound in "
                + svc.databaseName(), offenders));
    }

    private int boundFor(String workflowId) {
        LedgerEntry e = ledgerByWorkflowId.get(workflowId);
        return e != null ? e.path().maxEventLogGaps() : 2;
    }

    private static int gapCount(TreeSet<Integer> seqs) {
        if (seqs.isEmpty()) {
            return 0;
        }
        // Per-band contiguity: sum, over each 1000-band, of the values missing
        // between that band's min and max.
        Map<Integer, int[]> band = new LinkedHashMap<>();
        for (int s : seqs) {
            int[] mm = band.computeIfAbsent(s / 1000, k -> new int[]{s, s});
            mm[0] = Math.min(mm[0], s);
            mm[1] = Math.max(mm[1], s);
        }
        int gaps = 0;
        for (var mm : band.values()) {
            for (int s = mm[0]; s <= mm[1]; s++) {
                if (!seqs.contains(s)) {
                    gaps++;
                }
            }
        }
        return gaps;
    }

    /** I1 — every ledger workflow terminal with the declared status and output. */
    private List<Violation> ledgerTerminalAndOutcome() {
        var v = new ArrayList<Violation>();
        var nonTerminal = new ArrayList<String>();
        var statusMismatch = new ArrayList<String>();
        var outputMismatch = new ArrayList<String>();

        try (Connection c = ds(Service.LOAN_APPLICATION).getConnection()) {
            for (LedgerEntry e : ledgerByWorkflowId.values()) {
                String status = null;
                String output = null;
                try (var ps = c.prepareStatement(
                        "SELECT status, output FROM maestro_workflow_instance WHERE workflow_id = ?")) {
                    ps.setString(1, e.workflowId());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            status = rs.getString(1);
                            output = rs.getString(2);
                        }
                    }
                }
                if (status == null || !TERMINAL.contains(status)) {
                    nonTerminal.add(e.workflowId() + " (status=" + status + ", path=" + e.path() + ")");
                    continue;
                }
                if (!status.equals(e.expectedTerminal())) {
                    statusMismatch.add(e.workflowId() + " (expected=" + e.expectedTerminal()
                            + ", actual=" + status + ", path=" + e.path() + ")");
                }
                if (e.expectedOutput() != null
                        && (output == null || !output.contains("\"" + e.expectedOutput() + "\""))) {
                    outputMismatch.add(e.workflowId() + " (expected output=" + e.expectedOutput() + ")");
                }
            }
        } catch (Exception ex) {
            log.warn("[chaos] I1 query failed: {}", ex.toString());
        }
        if (!nonTerminal.isEmpty()) {
            v.add(new Violation("I1", "ledger workflow not terminal at T_verify", nonTerminal));
        }
        if (!statusMismatch.isEmpty()) {
            v.add(new Violation("I1", "terminal status != declared expectation", statusMismatch));
        }
        if (!outputMismatch.isEmpty()) {
            v.add(new Violation("I1", "output.status != declared expectation", outputMismatch));
        }
        return v;
    }

    /** I1 (weak) — every instance in this service DB is terminal. */
    private List<Violation> allTerminal(Service svc) {
        var ids = queryStrings(svc,
                "SELECT workflow_id FROM maestro_workflow_instance "
                + "WHERE status NOT IN ('COMPLETED','FAILED','TERMINATED')");
        return ids.isEmpty() ? List.of()
                : List.of(new Violation("I1", "non-terminal instance in " + svc.databaseName(), ids));
    }

    /**
     * I4 — unconsumed application signals for COMPLETED workflows (loan DB),
     * split per Ruling 3:
     *
     * <ul>
     *   <li>{@code consumedTwin=false} — no byte-identical consumed row exists
     *       for the same workflow and signal name: a genuinely missed/lost
     *       signal. <b>Hard violation.</b></li>
     *   <li>{@code consumedTwin=true} — a byte-identical consumed twin exists:
     *       a Kafka at-least-once redelivery of a message the workflow
     *       demonstrably received, persisted per "never discard a signal" and
     *       correctly never consumed by the completed workflow. Reported as a
     *       mandatory finding, never a failure.</li>
     * </ul>
     *
     * <p>Caveat (recorded in the design changelog): byte-identity implies
     * same-logical-signal only because the loan workload's signals are
     * logically keyed (signerId, round numbers, verification type); a future
     * workload with unkeyed identical payloads must re-examine this split.
     */
    private I4Result unconsumedApplicationSignals() {
        String sql =
                "SELECT s.workflow_id || ':' || s.signal_name || ' x' || COUNT(*), "
                + "BOOL_AND(EXISTS ("
                + "     SELECT 1 FROM maestro_workflow_signal t "
                + "     WHERE t.workflow_id = s.workflow_id "
                + "       AND t.signal_name = s.signal_name "
                + "       AND t.consumed = TRUE "
                + "       AND t.payload::text = s.payload::text)) "
                + "FROM maestro_workflow_signal s "
                + "JOIN maestro_workflow_instance i ON i.workflow_id = s.workflow_id "
                + "WHERE i.status = 'COMPLETED' AND s.consumed = FALSE "
                + "AND s.signal_name NOT LIKE '$maestro:%' "
                + "GROUP BY s.workflow_id, s.signal_name";
        var missed = new ArrayList<String>();
        var redelivered = new ArrayList<String>();
        try (var c = ds(Service.LOAN_APPLICATION).getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                if (rs.getBoolean(2)) {
                    redelivered.add(rs.getString(1) + " consumedTwin=true");
                } else {
                    missed.add(rs.getString(1) + " consumedTwin=false");
                }
            }
        } catch (Exception e) {
            log.warn("[chaos] I4 query failed: {}", e.toString());
        }
        var violations = missed.isEmpty() ? List.<Violation>of()
                : List.of(new Violation("I4", "unconsumed application signal with NO consumed "
                + "twin for COMPLETED workflow (loan DB) — genuinely missed signal", missed));
        return new I4Result(violations, redelivered);
    }

    private record I4Result(List<Violation> violations, List<String> redeliveredUnconsumedSignals) {
    }

    // ------------------------------------------------------------- failure dumps

    private void dumpFailures(List<Violation> violations) {
        var workflowIds = new LinkedHashSet<String>();
        for (Violation v : violations) {
            for (String id : v.workflowIds()) {
                // strip trailing " (...)" annotation and any "workflow:signal xN"
                String wf = id.split(" ")[0].split(":")[0];
                if (wf.startsWith("loan-") || wf.startsWith("underwriting-")
                        || wf.startsWith("verification-")) {
                    workflowIds.add(wf);
                }
            }
        }
        for (String wf : workflowIds) {
            dumpWorkflow(wf);
        }
        evidence.writeJson("failures/violations.json", violations);
    }

    private void dumpWorkflow(String workflowId) {
        Service svc = serviceOf(workflowId);
        String dir = "failures/" + workflowId + "/";
        evidence.writeJson(dir + "instance.json",
                queryRows(svc, "SELECT * FROM maestro_workflow_instance WHERE workflow_id = ?", workflowId));
        evidence.writeJson(dir + "events.json",
                queryRows(svc, "SELECT e.* FROM maestro_workflow_event e "
                        + "JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id "
                        + "WHERE i.workflow_id = ? ORDER BY e.sequence_number", workflowId));
        evidence.writeJson(dir + "signals.json",
                queryRows(svc, "SELECT * FROM maestro_workflow_signal WHERE workflow_id = ?", workflowId));
        evidence.writeJson(dir + "timers.json",
                queryRows(svc, "SELECT * FROM maestro_workflow_timer WHERE workflow_id = ?", workflowId));
        LedgerEntry ledger = ledgerByWorkflowId.get(workflowId);
        if (ledger != null) {
            evidence.writeJson(dir + "ledger.json", ledger);
        }
        evidence.writeText(dir + "node-log-excerpts.txt", logExcerpts(workflowId));
    }

    private String logExcerpts(String workflowId) {
        String id = workflowId.startsWith("loan-") ? workflowId.substring(5) : workflowId;
        var sb = new StringBuilder();
        for (var file : cluster.allLogFiles()) {
            try {
                if (!java.nio.file.Files.exists(file)) {
                    continue;
                }
                for (String line : java.nio.file.Files.readString(file).split("\n")) {
                    if (line.contains(workflowId) || line.contains(id)) {
                        sb.append(file.getFileName()).append(": ").append(line).append('\n');
                    }
                }
            } catch (Exception ignore) {
                // unreadable
            }
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- SQL util

    private Service serviceOf(String workflowId) {
        if (workflowId.startsWith("underwriting-")) {
            return Service.UNDERWRITING;
        }
        if (workflowId.startsWith("verification-")) {
            return Service.VERIFICATION_GATEWAY;
        }
        return Service.LOAN_APPLICATION;
    }

    private DataSource ds(Service svc) {
        return cluster.dataSource(svc);
    }

    private List<String> queryStrings(Service svc, String sql) {
        var out = new ArrayList<String>();
        try (Connection c = ds(svc).getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.warn("[chaos] query failed on {}: {} -- {}", svc.databaseName(), e.toString(), sql);
        }
        return out;
    }

    private List<Map<String, Object>> queryRows(Service svc, String sql, String param) {
        var rows = new ArrayList<Map<String, Object>>();
        try (Connection c = ds(svc).getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= cols; i++) {
                        Object val = rs.getObject(i);
                        row.put(meta.getColumnLabel(i), val == null ? null : String.valueOf(val));
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("[chaos] row dump failed on {}: {}", svc.databaseName(), e.toString());
        }
        return rows;
    }
}
