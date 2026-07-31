package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-run calibration (chaos-harness-design.md §5 I3(d), ruling Q6): runs each
 * of the four paths once with <em>no</em> chaos, asserts the declared terminal
 * outcome, and characterises the per-path event-log gap sets and side-effect log
 * counts. The measured gaps calibrate the I3(d) density predicate and the record
 * is written to {@code calibration.json}; the summary is echoed to the design
 * doc's changelog.
 *
 * <p>Opt-in ({@code -Dmaestro.chaos.golden=true}) — excluded from the PR-gate
 * triple.
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "maestro.chaos.golden", matches = "true")
@DisplayName("Golden calibration: each path chaos-free, gap sets characterised")
class ChaosGoldenRunE2EIT {

    @Test
    @DisplayName("all four paths reach their declared terminal chaos-free and gaps are recorded")
    void goldenRun_allPathsReachDeclaredTerminal_gapsRecorded() throws Exception {
        var config = ChaosConfig.fromSystemProperties(ChaosMode.GOLDEN);
        var identity = RunIdentity.capture(config.seed(), ChaosMode.GOLDEN);
        var evidence = new EvidenceWriter(identity, config.evidenceRoot());
        System.out.println("[chaos] GOLDEN runId=" + identity.runId() + " seed=" + config.seed());

        var master = new SplittableRandom(config.seed());
        var driverRng = master.split();

        try (var cluster = new ChaosCluster(config, evidence)) {
            cluster.start();
            var driver = new WorkloadDriver(cluster, config, evidence, driverRng);

            var calibration = new LinkedHashMap<String, Object>();
            try {
                for (LoanPath path : LoanPath.values()) {
                    String workflowId = driver.runSingleBlocking(path, 1);

                    String status = queryStatus(cluster, workflowId);
                    assertEquals(path.expectedTerminal(), status,
                            "path " + path + " terminal mismatch (" + workflowId + ")");
                    if (path.expectedOutput() != null) {
                        assertEquals(path.expectedOutput(),
                                driver.outputStatus(Service.LOAN_APPLICATION, workflowId).orElse(null),
                                "path " + path + " output mismatch");
                    }

                    var gaps = gapSet(cluster, workflowId);
                    var effects = sideEffectCounts(cluster, workflowId);
                    var pathRecord = new LinkedHashMap<String, Object>();
                    pathRecord.put("workflowId", workflowId);
                    pathRecord.put("terminal", status);
                    pathRecord.put("gapSet", new ArrayList<>(gaps));
                    pathRecord.put("gapCount", gaps.size());
                    pathRecord.put("sideEffects", effects);
                    calibration.put(path.name(), pathRecord);
                    System.out.printf("[chaos] GOLDEN %-16s terminal=%-9s gaps=%s effects=%s%n",
                            path, status, gaps, effects);
                }
            } finally {
                driver.close();
            }
            evidence.writeJson("calibration.json", calibration);
        }
    }

    private String queryStatus(ChaosCluster cluster, String workflowId) throws Exception {
        try (Connection c = cluster.dataSource(Service.LOAN_APPLICATION).getConnection();
             var ps = c.prepareStatement(
                     "SELECT status FROM maestro_workflow_instance WHERE workflow_id = ?")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "<absent>";
            }
        }
    }

    /** Missing sequence numbers below the max, computed per 1000-band. */
    private TreeSet<Integer> gapSet(ChaosCluster cluster, String workflowId) throws Exception {
        var seqs = new TreeSet<Integer>();
        try (Connection c = cluster.dataSource(Service.LOAN_APPLICATION).getConnection();
             var ps = c.prepareStatement(
                     "SELECT e.sequence_number FROM maestro_workflow_event e "
                     + "JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id "
                     + "WHERE i.workflow_id = ? ORDER BY e.sequence_number")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    seqs.add(rs.getInt(1));
                }
            }
        }
        var gaps = new TreeSet<Integer>();
        if (seqs.isEmpty()) {
            return gaps;
        }
        // Per-band contiguity: within each 1000-band, every value between the
        // band's min and max present in the log should exist; the missing ones
        // are the (designed, gate-timeout) gaps.
        Map<Integer, int[]> bandMinMax = new LinkedHashMap<>();
        for (int s : seqs) {
            bandMinMax.computeIfAbsent(s / 1000, k -> new int[]{s, s});
            int[] mm = bandMinMax.get(s / 1000);
            mm[0] = Math.min(mm[0], s);
            mm[1] = Math.max(mm[1], s);
        }
        for (var mm : bandMinMax.values()) {
            for (int s = mm[0]; s <= mm[1]; s++) {
                if (!seqs.contains(s)) {
                    gaps.add(s);
                }
            }
        }
        return gaps;
    }

    private Map<String, Long> sideEffectCounts(ChaosCluster cluster, String workflowId) {
        String id = workflowId.substring("loan-".length());
        var counts = new LinkedHashMap<String, Long>();
        counts.put("rateLock", countLogMatches(cluster, "Reserved rate lock", "for loan " + id));
        counts.put("disbursement", countLogMatches(cluster, "Disbursed", "for loan " + id));
        counts.put("compensation",
                countLogMatches(cluster, "COMPENSATION: released rate lock", "for loan " + id));
        return counts;
    }

    private long countLogMatches(ChaosCluster cluster, String a, String b) {
        long count = 0;
        for (NodeRole role : List.of(NodeRole.LOAN_A, NodeRole.LOAN_B)) {
            for (var file : cluster.logFiles(role)) {
                try {
                    if (!java.nio.file.Files.exists(file)) {
                        continue;
                    }
                    for (String line : java.nio.file.Files.readString(file).split("\n")) {
                        if (line.contains(a) && line.contains(b)) {
                            count++;
                        }
                    }
                } catch (Exception ignore) {
                    // unreadable
                }
            }
        }
        return count;
    }
}
