package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in infrastructure smoke test for the chaos cluster ({@code
 * -Dmaestro.chaos.smoke=true}). Proves the six-node cluster boots, every node
 * answers its HTTP readiness probe, and each service database is reachable with
 * the Maestro schema migrated. Excluded from the PR-gate triple (it only runs
 * when the smoke property is set) so it never inflates the chaos gate.
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "maestro.chaos.smoke", matches = "true")
@DisplayName("Chaos cluster boots and all six nodes are reachable")
class ClusterBootSmokeIT {

    @Test
    @DisplayName("cluster boot brings up 6 healthy nodes with migrated per-service schemas")
    void clusterBoot_bringsUpSixHealthyNodesWithMigratedSchema() throws Exception {
        var config = ChaosConfig.fromSystemProperties(ChaosMode.PR_GATE);
        var identity = RunIdentity.capture(config.seed(), ChaosMode.PR_GATE);
        var evidence = new EvidenceWriter(identity, config.evidenceRoot());
        System.out.println("[chaos] smoke runId=" + identity.runId() + " seed=" + config.seed());

        try (var cluster = new ChaosCluster(config, evidence)) {
            cluster.start();

            for (NodeRole role : NodeRole.values()) {
                assertTrue(cluster.isHttpUp(role), "node not HTTP-ready: " + role);
            }

            for (NodeRole.Service svc : NodeRole.Service.values()) {
                try (Connection c = cluster.dataSource(svc).getConnection();
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM maestro_workflow_instance")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "expected empty instance table for " + svc);
                }
            }
        }
    }
}
