package io.b2mash.maestro.store.postgres.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link MaestroSchemaInitializer}: Maestro applies its own migrations
 * into its own history table ({@code maestro_schema_history}), never the host
 * application's {@code flyway_schema_history}, and each {@link SchemaMode}
 * does what it documents.
 *
 * <p>Every test gets a fresh, empty database ({@code CREATE DATABASE} on the
 * shared container) so that "nothing applied yet" is a real starting state.
 * The container is started from a static initialiser for the reason
 * {@code PostgresTestSupport} documents.
 */
@DisplayName("MaestroSchemaInitializer")
class MaestroSchemaInitializerTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_schema_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private final ClassLoader cl = getClass().getClassLoader();
    private PGSimpleDataSource ds;

    @BeforeEach
    void freshDatabase() throws SQLException {
        var name = "schema_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("discovers this module's schema location from META-INF/maestro/schema-locations")
    void discoverLocationsFindsThisModule() {
        assertThat(MaestroSchemaInitializer.discoverLocations(cl))
                .contains("classpath:db/maestro/store");
    }

    @Test
    @DisplayName("flyway mode creates the tables, recorded in Maestro's own history table")
    void flywayModeCreatesTablesInMaestrosOwnHistory() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(tables())
                .contains("maestro_workflow_instance", MaestroSchemaInitializer.HISTORY_TABLE)
                .doesNotContain("flyway_schema_history");
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("flyway mode is idempotent: a second run applies nothing")
    void flywayModeIsIdempotent() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5");
    }

    /**
     * A host database is never empty: the host's own tables — at the very
     * least its {@code flyway_schema_history} — are already there when Maestro
     * first starts. Flyway refuses to migrate a non-empty schema that has no
     * history table of its own unless told to baseline, so this is the case
     * every real deployment meets.
     */
    @Test
    @DisplayName("flyway mode applies into a schema that already holds the host's tables")
    void flywayModeAppliesIntoANonEmptyHostSchema() throws SQLException {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE host_customer (id UUID PRIMARY KEY)");
        }

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(tables()).contains("host_customer", "maestro_workflow_instance");
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5");
    }

    /**
     * The documented upgrade path (docs/configuration.md, "Upgrading an
     * existing database"): a database whose Maestro tables were created by an
     * earlier build — through the host's Flyway, into
     * {@code flyway_schema_history} — fails loudly rather than being silently
     * baselined over, and succeeds once the old tables are dropped.
     */
    @Test
    @DisplayName("a database migrated by an earlier build fails loudly until its old Maestro tables are dropped")
    void databaseFromAnEarlierBuildFailsLoudlyUntilDropped() throws SQLException {
        Flyway.configure(cl).dataSource(ds)
                .locations("classpath:db/maestro/store").load().migrate();

        assertThatThrownBy(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize())
                .hasMessageContaining("already exists");

        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE maestro_workflow_signal, maestro_workflow_timer, "
                    + "maestro_workflow_event, maestro_workflow_instance");
            stmt.execute("DELETE FROM flyway_schema_history");
        }
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("validate mode fails on an empty database, naming the pending versions")
    void validateModeFailsOnAnEmptyDatabaseNamingPendingVersions() {
        assertThatThrownBy(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending")
                .hasMessageContaining("1")
                .hasMessageContaining("classpath:db/maestro/store");
    }

    @Test
    @DisplayName("validate mode passes once every migration is applied")
    void validateModePassesOnceMigrated() {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("none mode touches nothing")
    void noneModeTouchesNothing() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.NONE, cl).initialize();

        assertThat(tables()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────

    private List<String> tables() throws SQLException {
        var names = new ArrayList<String>();
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** Applied SQL migration versions, in order; Flyway's baseline marker row is excluded. */
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
