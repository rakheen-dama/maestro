package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP9's assertions over the history the build at {@code cbf5a2a} wrote
 * ({@link SeededHistory}): this build upgrades that database without losing a
 * row, and reads every seeded workflow back. Every other suite starts from an
 * empty schema, so this is the only one that can catch a migration valid on
 * an empty table but fatal on existing rows, or a read path that no longer
 * understands what an older build stored.
 */
@Tag("integration")
@DisplayName("Seeded history written by cbf5a2a, under this build")
class SeededHistoryUpgradeIT {

    private static final List<String> TABLES = List.of(
            "maestro_workflow_instance", "maestro_workflow_event",
            "maestro_workflow_signal", "maestro_workflow_timer");

    @Test
    @DisplayName("The dump was captured at cbf5a2a, from the frozen fixture source, with no psql meta-commands")
    void theDumpWasCapturedAtCbf5a2a() throws Exception {
        var seeded = SeededHistory.load();
        assertThat(seeded.manifest().getProperty("ref")).isEqualTo("cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7");
        assertThat(seeded.manifest().getProperty("serverVersion")).startsWith("16.");
        assertThat(sha256(Files.readAllBytes(Path.of(seeded.manifest().getProperty("fixtureSource.path")))))
                .as("SeededHistoryWorkflows.java is frozen: it produced the seeded history")
                .isEqualTo(seeded.manifest().getProperty("fixtureSource.sha256"));
        assertThat(dumpLines()).noneMatch(line -> line.startsWith("\\"));
    }

    @Test
    @DisplayName("This build's migrations upgrade the seeded database, validate over it, and keep every row")
    void theSeededDatabaseUpgradesUnderThisBuild() throws Exception {
        var seeded = SeededHistory.load();
        var before = rowCounts(seeded);          // maestro_workflow_{instance,event,signal,timer}
        assertThat(appliedVersions(seeded)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
        seeded.upgrade(SchemaMode.FLYWAY);
        seeded.upgrade(SchemaMode.VALIDATE);     // throws if anything this build ships is unrecorded or edited
        assertThat(appliedVersions(seeded)).startsWith("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
        assertThat(rowCounts(seeded)).isEqualTo(before);
    }

    @Test
    @DisplayName("Every seeded workflow reads back through this build's store with its status and event types")
    void everySeededWorkflowReadsBackThroughThisBuildsStore() throws Exception {
        var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY);
        Seeds.STATUS_AT_CAPTURE.forEach((id, status) ->
                assertThat(seeded.store().getInstance(id)).get().extracting(WorkflowInstance::status).as(id)
                        .isEqualTo(status));
        var types = Seeds.STATUS_AT_CAPTURE.keySet().stream().flatMap(id -> seeded.events(id).stream())
                .map(WorkflowEvent::eventType).collect(toCollection(() -> EnumSet.noneOf(EventType.class)));
        assertThat(types).isEqualTo(Seeds.EVENT_TYPES);          // includes: no UNKNOWN
        // WP1 input: the store wrote the decimal exactly (raw JSONB text of seed-wp1-decimal's ACTIVITY_COMPLETED payload)
        assertThat(rawPayload(seeded, Seeds.WP1_DECIMAL, "ACTIVITY_COMPLETED")).contains("12345678901234567890.123456789");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Map<String, Long> rowCounts(SeededHistory seeded) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (var conn = seeded.dataSource().getConnection();
             var st = conn.createStatement()) {
            for (var table : TABLES) {
                try (var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private static List<String> appliedVersions(SeededHistory seeded) throws SQLException {
        var versions = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT version FROM maestro_schema_history"
                     + " WHERE type = 'SQL' AND success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    /** The raw JSONB text of the workflow's one event of {@code eventType}, as Postgres holds it. */
    private static String rawPayload(SeededHistory seeded, String workflowId, String eventType) throws SQLException {
        var payloads = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("""
                     SELECT e.payload::text FROM maestro_workflow_event e
                     JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id
                     WHERE i.workflow_id = ? AND e.event_type = ?
                     ORDER BY e.sequence_number""")) {
            ps.setString(1, workflowId);
            ps.setString(2, eventType);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    payloads.add(rs.getString(1));
                }
            }
        }
        assertThat(payloads).as("%s events of %s", eventType, workflowId).hasSize(1);
        return payloads.getFirst();
    }

    private static List<String> dumpLines() throws Exception {
        try (var in = Objects.requireNonNull(
                SeededHistoryUpgradeIT.class.getClassLoader().getResourceAsStream(SeededHistory.DUMP_RESOURCE),
                SeededHistory.DUMP_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
