package io.b2mash.maestro.store.postgres.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.flywaydb.core.api.MigrationInfo;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "100");
    }

    @Test
    @DisplayName("flyway mode is idempotent: a second run applies nothing")
    void flywayModeIsIdempotent() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "100");
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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "100");
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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "100");
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

    /**
     * The upgrade every existing database takes: an earlier build recorded the
     * store band up to V9 and the lock band's V100 in
     * {@value MaestroSchemaInitializer#HISTORY_TABLE}; this build ships V10,
     * which sorts below V100. The bands are independent, so the initializer
     * applies it out of order — before that, Flyway refused it ("Detected
     * resolved migration not applied to database: 10") and the node failed
     * startup in the default mode.
     */
    @Test
    @DisplayName("flyway mode applies a new store migration to a database that already has the lock band")
    void flywayModeAppliesANewStoreMigrationToADatabaseThatAlreadyHasTheLockBand() throws SQLException {
        migrateLikeAnEarlierBuild("9");

        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize())
                .doesNotThrowAnyException();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "10");
        assertThat(indexOid("idx_wf_instance_recovery")).isPositive();
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    /**
     * The general guard behind the test above, so a V11+ store migration cannot
     * reintroduce the failure: for every shipped store-band version, a database
     * holding the store band up to the version before it plus the whole lock
     * band takes it in {@code flyway} mode, and then validates.
     */
    @ParameterizedTest(name = "store V{0} after the lock band")
    @MethodSource("storeVersionsAfterTheFirst")
    void everyStoreMigrationAppliesAfterTheLockBand(String version) throws SQLException {
        var shipped = shippedVersions();
        var previous = shipped.get(shipped.indexOf(version) - 1);
        migrateLikeAnEarlierBuild(previous);
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).doesNotContain(version).contains("100");

        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize())
                .doesNotThrowAnyException();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).containsExactlyInAnyOrderElementsOf(shipped);
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    /** Every shipped store-band version but the first, ascending. */
    static List<String> storeVersionsAfterTheFirst() {
        var store = shippedVersions().stream().filter(v -> Integer.parseInt(v) < 100).toList();
        return store.subList(1, store.size());
    }

    /** Every shipped Maestro migration version, store and lock bands, ascending. */
    private static List<String> shippedVersions() {
        var cl = MaestroSchemaInitializerTest.class.getClassLoader();
        // info() needs a connection even to list resolved migrations: the
        // container's base database, under a history table that never exists,
        // so every shipped migration reads as pending and nothing is written.
        return Arrays.stream(Flyway.configure(cl)
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .table("maestro_shipped_versions_probe")
                        .locations(MaestroSchemaInitializer.discoverLocations(cl).toArray(String[]::new))
                        .load().info().all())
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();
    }

    /**
     * The state an earlier build left: the store band applied up to
     * {@code storeTarget}, then the lock band — so the lock band's versions
     * are the highest recorded in {@value MaestroSchemaInitializer#HISTORY_TABLE}.
     */
    private void migrateLikeAnEarlierBuild(String storeTarget) {
        Flyway.configure(cl).dataSource(ds).table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations("classpath:db/maestro/store").target(storeTarget).load().migrate();
        Flyway.configure(cl).dataSource(ds).table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations("classpath:db/maestro/lock").validateOnMigrate(false).load().migrate();
    }

    /**
     * The documented V10 upgrade under {@code maestro.store.schema.mode:
     * validate} (docs/configuration.md § Recovery Configuration, release
     * notes "Host load" Upgrade item 3). Creating the index by hand does not
     * record V10 in {@value MaestroSchemaInitializer#HISTORY_TABLE}, so
     * validate still refuses to start; one {@code flyway}-mode run records it —
     * finding the hand-made index and leaving it as it is — and validate then
     * passes with no checksum complaint.
     */
    @Test
    @DisplayName("validate mode: a hand-made V10 index is not enough; one flyway run records V10 over it")
    void validateModeNeedsV10RecordedNotJustTheIndex() throws SQLException {
        // A database an earlier build took to V9 and V100, recorded in Maestro's history.
        migrateLikeAnEarlierBuild("9");
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100");

        // The documented CONCURRENTLY line, by hand.
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_recovery
                        ON maestro_workflow_instance (service_name, started_at, id)
                        WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING')""");
        }
        var handMade = indexOid("idx_wf_instance_recovery");

        assertThatThrownBy(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .as("the index alone does not record V10: validate still refuses to start")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending migrations [10]");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).contains("10");
        assertThat(indexOid("idx_wf_instance_recovery"))
                .as("V10's IF NOT EXISTS found the hand-made index and did not rebuild it")
                .isEqualTo(handMade);
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .as("V10 recorded by Maestro's own run: nothing pending, checksum matches")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("none mode touches nothing")
    void noneModeTouchesNothing() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.NONE, cl).initialize();

        assertThat(tables()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────

    private long indexOid(String name) throws SQLException {
        try (var conn = ds.getConnection();
             var stmt = conn.prepareStatement("SELECT ?::regclass::oid")) {
            stmt.setString(1, name);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

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
