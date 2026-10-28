package io.b2mash.maestro.integration.schema;

import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that Maestro's migrations coexist with a host application's own Flyway
 * on one database, and with each other.
 *
 * <p>Maestro's migrations used to live in {@code classpath:db/migration} —
 * Spring Boot's default Flyway location — and were applied by the host's
 * Flyway into the host's {@code flyway_schema_history}, so a host's own
 * {@code V1__init.sql} collided with Maestro's {@code V1}. Every Maestro
 * Postgres module now ships its SQL under {@code classpath:db/maestro/<module>}
 * and {@link MaestroSchemaInitializer} applies them into Maestro's own
 * {@code maestro_schema_history}. This suite puts a host {@code V1__init.sql}
 * in {@code classpath:db/migration} (test resources) and proves the two
 * histories never meet.
 *
 * <p>The Postgres-only profile ({@code maestro-samples/sample-postgres-only})
 * puts {@code maestro-store-postgres}, {@code maestro-lock-postgres} and
 * {@code maestro-messaging-postgres} on one classpath; their versions share
 * Maestro's one history and must not collide either (bands 1–99, 100–199,
 * 200–299).
 *
 * <p>This is an integration test rather than a module unit test because the
 * defect only exists when the modules are composed; no single module owns it.
 * Each test gets a fresh database.
 */
@Testcontainers
@Tag("integration")
@DisplayName("Maestro Flyway migrations coexist with the host's and with each other")
class MaestroMigrationsCoexistIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private PGSimpleDataSource ds;

    @BeforeEach
    void freshDatabase() throws SQLException {
        var name = "coexist_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + name);
        }
        ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {postgres.getHost()});
        ds.setPortNumbers(new int[] {postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        ds.setDatabaseName(name);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
    }

    @Test
    @DisplayName("the host's Flyway and Maestro's history coexist without a version collision")
    void hostFlywayAndMaestroHistoryCoexistWithoutVersionCollision() throws SQLException {
        // Host: Spring Boot's default location + default history table
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        assertEquals(List.of("1"), versions("flyway_schema_history"),
                "classpath:db/migration contains only the host's V1 now");
        assertTrue(tables().contains("host_customer"));

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, getClass().getClassLoader()).initialize();

        assertEquals(List.of("1", "2", "3", "4", "5", "100", "200", "201", "202"),
                versions(MaestroSchemaInitializer.HISTORY_TABLE));
        assertEquals(List.of("1"), versions("flyway_schema_history"),
                "Maestro wrote nothing into the host's history");
        var tables = tables();
        assertTrue(tables.containsAll(List.of(
                        "maestro_workflow_instance", "maestro_distributed_lock", "maestro_signal_queue")),
                "every module's tables must be present, found: " + tables);
        // V202: the task-dispatch channel and the never-read lifecycle outbox are gone.
        assertFalse(tables.contains("maestro_task_queue"), "V202 must drop maestro_task_queue: " + tables);
        assertFalse(tables.contains("maestro_lifecycle_event_queue"),
                "V202 must drop maestro_lifecycle_event_queue: " + tables);
    }

    /**
     * Design §8.6: V4 lands on databases that are already at V3 with live
     * data. Applying it in sequence from an empty database — through the same
     * initializer every application uses — is what proves it is not shadowed
     * by a same-version sibling and that {@code trace_context} really exists
     * for {@code AbstractJdbcWorkflowStore}'s insert/select to name.
     */
    @Test
    @DisplayName("V4 adds a nullable maestro_workflow_signal.trace_context on top of V1–V3")
    void v4AddsTheNullableSignalTraceContextColumn() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, getClass().getClassLoader()).initialize();

        try (var conn = ds.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT data_type, is_nullable, character_maximum_length "
                             + "FROM information_schema.columns "
                             + "WHERE table_name = 'maestro_workflow_signal' AND column_name = 'trace_context'");
             var rs = stmt.executeQuery()) {
            assertTrue(rs.next(), "V4 must have added maestro_workflow_signal.trace_context");
            assertEquals("character varying", rs.getString("data_type"));
            assertEquals("YES", rs.getString("is_nullable"),
                    "the column must be nullable — absence of trace context is normal, not an error");
            assertEquals(128, rs.getInt("character_maximum_length"));
        }
    }

    @Test
    @DisplayName("no two Maestro migrations declare the same version, across every module")
    void maestroMigrationVersionsAreUniqueAcrossModules() {
        var locations = MaestroSchemaInitializer.discoverLocations(getClass().getClassLoader());
        assertEquals(List.of("classpath:db/maestro/lock", "classpath:db/maestro/messaging",
                "classpath:db/maestro/store"), locations);

        var flyway = Flyway.configure()
                .dataSource(ds)
                .table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations(locations.toArray(String[]::new))
                .load();

        var versions = new ArrayList<String>();
        for (var migration : flyway.info().all()) {
            versions.add(migration.getVersion().toString());
        }

        assertEquals(versions.size(), versions.stream().distinct().count(),
                "Duplicate migration versions across maestro modules: " + versions);
    }

    /**
     * R50: the documented one-time upgrade of a 0.4.0 database, keep-the-data
     * path (docs/configuration.md § Schema management → "Upgrading an existing
     * database", step for step). The SQL below is the documented SQL verbatim.
     */
    @Test
    @DisplayName("upgrade procedure, keep the data: mode none + V4/V5/V202 by hand + scoped DELETE")
    void upgradeProcedureKeepingTheData(@TempDir Path released, @TempDir Path hostMigrations,
                                        @TempDir Path noMigrations) throws Exception {
        simulateReleased040Database(released, hostMigrations);
        assertEquals(List.of("1", "2", "3", "100", "200", "201", "1000"), versions("flyway_schema_history"));
        var store = new PostgresWorkflowStore(ds, JsonMapper.builder().build());
        assertThrows(RuntimeException.class, () -> store.saveSignal(preDeliverySignal()),
                "without V4/V5 every saveSignal fails — the reason the procedure applies them");
        // A host Flyway that resolves no migrations validates nothing, so it does not trip
        // over Maestro's rows (docs: such a host fails on Maestro's V1 instead).
        Flyway.configure().dataSource(ds).locations("filesystem:" + noMigrations).load().migrate();
        var hostFlyway = Flyway.configure().dataSource(ds)
                .locations("filesystem:" + hostMigrations).load(); // the host's own migrations only, as now
        var unresolved = assertThrows(FlywayException.class, hostFlyway::migrate);
        assertTrue(unresolved.getMessage().contains("not resolved locally"), unresolved.getMessage());

        // Step 2, keep the data.
        execute("""
                ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS trace_context VARCHAR(128);  -- V4
                ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();         -- V5
                DROP TABLE IF EXISTS maestro_task_queue;                                                 -- V202
                DROP TABLE IF EXISTS maestro_lifecycle_event_queue;                                      -- V202
                """);
        // Step 3.
        assertEquals(6, execute(DELETE_MAESTRO_HISTORY_ROWS), "every row a 0.4.0 build wrote is matched");

        assertEquals(List.of("1000"), versions("flyway_schema_history"), "the host's own row is untouched");

        // Step 4: the new build starts — the host's Flyway, then Maestro in mode none.
        hostFlyway.migrate();
        new MaestroSchemaInitializer(ds, SchemaMode.NONE, getClass().getClassLoader()).initialize();
        store.saveSignal(preDeliverySignal());
        assertTrue(store.getUnconsumedSignals("wf-upgrade", "go").size() >= 1);
        var tables = tables();
        assertFalse(tables.contains("maestro_task_queue"), tables.toString());
        assertFalse(tables.contains("maestro_lifecycle_event_queue"), tables.toString());
        assertFalse(tables.contains(MaestroSchemaInitializer.HISTORY_TABLE), "mode none writes no history");
    }

    /** R50: the same procedure's fresh-start path. */
    @Test
    @DisplayName("upgrade procedure, fresh start: DROP + scoped DELETE, then Maestro recreates its schema")
    void upgradeProcedureFreshStart(@TempDir Path released, @TempDir Path hostMigrations) throws Exception {
        simulateReleased040Database(released, hostMigrations);

        // Step 2, fresh start.
        execute("""
                DROP TABLE IF EXISTS maestro_workflow_signal, maestro_workflow_timer, maestro_workflow_event,
                    maestro_workflow_instance, maestro_distributed_lock, maestro_leader_election,
                    maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;
                """);
        assertTrue(tables().stream().noneMatch(t -> t.startsWith("maestro_")), "every Maestro table dropped");
        assertTrue(tables().contains("host_orders"), "the host's own table is untouched");
        // Step 3.
        assertEquals(6, execute(DELETE_MAESTRO_HISTORY_ROWS));
        assertEquals(List.of("1000"), versions("flyway_schema_history"));

        // Step 4.
        Flyway.configure().dataSource(ds).locations("filesystem:" + hostMigrations).load().migrate();
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, getClass().getClassLoader()).initialize();
        assertEquals(List.of("1", "2", "3", "4", "5", "100", "200", "201", "202"),
                versions(MaestroSchemaInitializer.HISTORY_TABLE));
        new PostgresWorkflowStore(ds, JsonMapper.builder().build()).saveSignal(preDeliverySignal());
    }

    /** The documented step-3 statement, verbatim. */
    private static final String DELETE_MAESTRO_HISTORY_ROWS = """
            DELETE FROM flyway_schema_history
            WHERE (version, script) IN (
                ('1',   'V1__create_maestro_schema.sql'),
                ('2',   'V2__create_indexes.sql'),
                ('3',   'V3__timer_lookup_index.sql'),
                ('4',   'V4__signal_trace_context.sql'),
                ('5',   'V5__signal_received_at_default.sql'),
                ('100', 'V100__maestro_lock_postgres.sql'),
                ('200', 'V200__maestro_messaging_postgres.sql'),
                ('201', 'V201__redelivery_dead_letter.sql'));
            """;

    /** The migrations 0.4.0 released, under the old layout: one location, the host's history table. */
    private static final List<String> RELEASED_040 = List.of(
            "db/maestro/store/V1__create_maestro_schema.sql",
            "db/maestro/store/V2__create_indexes.sql",
            "db/maestro/store/V3__timer_lookup_index.sql",
            "db/maestro/lock/V100__maestro_lock_postgres.sql",
            "db/maestro/messaging/V200__maestro_messaging_postgres.sql",
            "db/maestro/messaging/V201__redelivery_dead_letter.sql");

    /**
     * A database as a 0.4.0 host left it: Maestro's released scripts and the
     * host's own migration (a version that did not collide with Maestro's)
     * resolved from one flat location — the old shared
     * {@code classpath:db/migration} — and applied by the host's Flyway into
     * {@code flyway_schema_history}. {@code hostDir} receives the host's
     * migration alone, as the host's location after the upgrade.
     */
    private void simulateReleased040Database(Path dir, Path hostDir) throws Exception {
        for (var resource : RELEASED_040) {
            try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
                Files.copy(Objects.requireNonNull(in, resource),
                        dir.resolve(resource.substring(resource.lastIndexOf('/') + 1)));
            }
        }
        var hostMigration = "CREATE TABLE host_orders (id BIGINT PRIMARY KEY);";
        Files.writeString(dir.resolve("V1000__host_orders.sql"), hostMigration);
        Files.writeString(hostDir.resolve("V1000__host_orders.sql"), hostMigration);
        Flyway.configure().dataSource(ds).locations("filesystem:" + dir).load().migrate();
    }

    private static WorkflowSignal preDeliverySignal() {
        return new WorkflowSignal(UUID.randomUUID(), null, "wf-upgrade", "go", null, false, Instant.now());
    }

    /** Runs {@code sql}; returns the update count of its last statement. */
    private int execute(String sql) throws SQLException {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(sql);
            return stmt.getUpdateCount();
        }
    }

    private List<String> tables() throws SQLException {
        var names = new ArrayList<String>();
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * Applied SQL migration versions, in order. Flyway's baseline marker row is
     * excluded: Maestro's history baselines at version 0 when it first meets a
     * schema that already holds the host's tables.
     */
    private List<String> versions(String historyTable) throws SQLException {
        var versions = new ArrayList<String>();
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT version FROM " + historyTable
                     + " WHERE type = 'SQL' AND success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }
}
