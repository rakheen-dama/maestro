package io.b2mash.maestro.integration.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that every Maestro module shipping Flyway migrations can be applied
 * together on a single database.
 *
 * <p>The Postgres-only profile ({@code maestro-samples/sample-postgres-only})
 * puts {@code maestro-store-postgres}, {@code maestro-lock-postgres} and
 * {@code maestro-messaging-postgres} on one classpath. All three ship their
 * migrations into {@code classpath:db/migration}, which Flyway scans as a
 * single location — so their version numbers share one namespace and must not
 * collide.
 *
 * <p>This is an integration test rather than a module unit test because the
 * defect only exists when the modules are composed; no single module owns it.
 */
@Testcontainers
@Tag("integration")
@DisplayName("Maestro Flyway migrations coexist on one database")
class MaestroMigrationsCoexistIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    @Test
    @DisplayName("store + lock + messaging migrations apply cleanly to one schema")
    void allModuleMigrationsApplyTogether() throws SQLException {
        var flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertTrue(result.success, "Flyway migration must succeed");

        // Every module's tables must be present — proves no migration was
        // silently skipped or shadowed by a same-version sibling.
        var tables = tableNames();
        assertTrue(tables.contains("maestro_workflow_instance"),
                "store migration must have run, found: " + tables);
        assertTrue(tables.contains("maestro_distributed_lock"),
                "lock migration must have run, found: " + tables);
        assertTrue(tables.contains("maestro_task_queue"),
                "messaging migration must have run, found: " + tables);
    }

    /**
     * Design §8.6: V4 is the cycle's only schema change, and it lands on
     * databases that are already at V3 with live data. Applying it in sequence
     * from an empty database — through the same shared {@code db/migration}
     * location every other module writes into — is what proves it is not
     * shadowed by a same-version sibling and that {@code trace_context} really
     * exists for {@code AbstractJdbcWorkflowStore}'s insert/select to name.
     */
    @Test
    @DisplayName("V4 adds a nullable maestro_workflow_signal.trace_context on top of V1–V3")
    void v4AddsTheNullableSignalTraceContextColumn() throws SQLException {
        Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var conn = dataSource().getConnection();
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
    @DisplayName("no two migrations declare the same version")
    void migrationVersionsAreUnique() {
        var flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load();

        var versions = new ArrayList<String>();
        for (var migration : flyway.info().all()) {
            versions.add(migration.getVersion().toString());
        }

        assertEquals(versions.size(), versions.stream().distinct().count(),
                "Duplicate migration versions across maestro modules: " + versions);
    }

    private static PGSimpleDataSource dataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static List<String> tableNames() throws SQLException {
        var names = new ArrayList<String>();
        try (var conn = dataSource().getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
