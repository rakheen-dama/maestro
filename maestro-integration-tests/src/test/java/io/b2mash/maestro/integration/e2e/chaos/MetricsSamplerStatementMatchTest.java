package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MetricsSampler}'s {@code pg_stat_statements} text patterns against
 * the statements the real store and lock actually send — not against strings
 * copied from their source. The chaos harness that runs the sampler is
 * {@code @Tag("e2e")} and too slow for the build, and a pattern that stops
 * matching only WARNs at the end of a run, so a store SQL change would
 * otherwise silently zero a benchmark column.
 *
 * <p>A Postgres with {@code pg_stat_statements} preloaded (the chaos cluster's
 * flags) runs the real {@link PostgresWorkflowStore} recovery scans — the
 * paged one, first page and cursor page, and the legacy unbounded one a
 * pre-change node still sends — and the real {@link PostgresDistributedLock}
 * statements; the sampler's own SQL then has to count each exactly.
 *
 * <h2>Thread Safety</h2>
 * <p>Single-threaded test; the container is shared across its methods.
 */
@Tag("integration")
@DisplayName("MetricsSampler's pg_stat_statements patterns match the statements the store and lock send")
class MetricsSamplerStatementMatchTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCommand("postgres",
                            "-c", "shared_preload_libraries=pg_stat_statements",
                            "-c", "pg_stat_statements.track=all",
                            "-c", "pg_stat_statements.track_utility=on");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void startPostgres() throws SQLException {
        postgres.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/maestro/store", "classpath:db/maestro/lock")
                .load().migrate();
        execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    @DisplayName("recovery calls sum the paged scan (both shapes) and the legacy scan; heldKeys has its own key")
    void samplerCountsTheRecoveryScansAndTheHeldKeysRead() throws SQLException {
        var store = new PostgresWorkflowStore(dataSource, JsonMapper.builder().build());
        var lock = new PostgresDistributedLock(dataSource);
        var instance = store.createInstance(running("wf-1", "svc-a"));
        execute("SELECT pg_stat_statements_reset()");

        store.getRecoverableInstances("svc-a", null, 200);                              // paged, first page
        store.getRecoverableInstances("svc-a", null, 200);
        store.getRecoverableInstances("svc-a", RecoveryCursor.of(instance), 200);       // paged, cursor page
        store.getRecoverableInstances();                                                // legacy (pre-change node)
        lock.tryAcquire("maestro:lock:workflow:wf-1", Duration.ofSeconds(30));
        lock.heldKeys(List.of("maestro:lock:workflow:wf-1", "maestro:lock:workflow:wf-2"));
        lock.heldKeys(List.of("maestro:lock:workflow:wf-3"));
        lock.heldKeys(List.of("maestro:lock:workflow:wf-4"));

        var normalised = statementsMentioning("maestro_");
        System.out.println("pg_stat_statements (normalised):\n  " + String.join("\n  ", normalised));

        var lockCalls = lockCalls();
        assertAll(
                () -> assertEquals(4, recoveryCalls(),
                        "3 paged scans (two statement shapes) + 1 legacy scan, summed: " + normalised),
                () -> assertTrue(Arrays.asList(MetricsSampler.LOCK_CALL_KEYS).contains("heldKeys"),
                        "the heldKeys read needs its own key: " + Arrays.toString(MetricsSampler.LOCK_CALL_KEYS)),
                () -> assertEquals(3L, lockCalls.get("heldKeys"), "three heldKeys reads: " + normalised),
                () -> assertEquals(1L, lockCalls.get("acquire"), "the heldKeys read is not an acquire"),
                () -> assertEquals(0L, lockCalls.get("renew"), "nor a renew"),
                () -> assertEquals(0L, lockCalls.get("leader"), "nor a leader election"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static long recoveryCalls() throws SQLException {
        try (var c = dataSource.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery(MetricsSampler.RECOVERY_CALLS_SQL)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<String, Long> lockCalls() throws SQLException {
        var out = new HashMap<String, Long>();
        try (var c = dataSource.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery(MetricsSampler.LOCK_CALLS_SQL)) {
            rs.next();
            for (int i = 0; i < MetricsSampler.LOCK_CALL_KEYS.length; i++) {
                out.put(MetricsSampler.LOCK_CALL_KEYS[i], rs.getLong(i + 1));
            }
        }
        return out;
    }

    private static List<String> statementsMentioning(String fragment) throws SQLException {
        var out = new ArrayList<String>();
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement("SELECT calls, query FROM pg_stat_statements "
                     + "WHERE query LIKE '%' || ? || '%' AND query NOT LIKE '%pg_stat_statements%' ORDER BY query")) {
            ps.setString(1, fragment);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1) + " x " + rs.getString(2).replace('\n', ' '));
                }
            }
        }
        return out;
    }

    private static void execute(String sql) throws SQLException {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static WorkflowInstance running(String workflowId, String serviceName) {
        var now = Instant.now();
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("chain")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName(serviceName)
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }
}
