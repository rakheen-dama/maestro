package io.b2mash.maestro.store.postgres.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
    }

    @Test
    @DisplayName("flyway mode is idempotent: a second run applies nothing")
    void flywayModeIsIdempotent() throws SQLException {
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
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
     * Issue 35: a database whose history recorded the lock band's V100 before
     * store migrations shipped later. Every database first migrated between
     * 762a5d6 (Maestro's own history) and the merge of V6 (PR #38) holds V1–V5
     * and V100 — V100 as the highest applied version. V6–V9 sort below it;
     * the bands are independent, so the initializer applies them out of
     * order — before that, Flyway refused them ("Detected resolved migration
     * not applied to database: 6") and the node failed startup in the default
     * mode. V101 then applies in order.
     */
    @Test
    @DisplayName("flyway mode applies store migrations below V100 to a database that already has the lock band")
    void flywayModeAppliesANewStoreMigrationToADatabaseThatAlreadyHasTheLockBand() throws SQLException {
        migrateLikeAnEarlierBuild("5");

        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize())
                .doesNotThrowAnyException();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "100", "6", "7", "8", "9", "101");
        assertThat(indexOid("idx_wf_instance_recovery")).isPositive();
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    /**
     * The general guard behind the test above: for every store version below
     * the lock band (V2–V9 — the band that sorts below V100), a database
     * holding the store band up to the version before it plus the whole lock
     * band takes it, out of order, in {@code flyway} mode, and then validates.
     * Versions above V100 are applied in order by the numbering rule
     * ({@link #migrationVersionsFollowTheNumberingRule}), so they need no
     * out-of-order case.
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

    /** Every shipped version below the lock band's V100 but the first, ascending. */
    static List<String> storeVersionsAfterTheFirst() {
        var belowLockBand = shippedVersions().stream().filter(v -> Integer.parseInt(v) < 100).toList();
        return belowLockBand.subList(1, belowLockBand.size());
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
     * The documented V101 upgrade under {@code maestro.store.schema.mode:
     * validate} (docs/configuration.md § Recovery Configuration, release
     * notes "Host load" Upgrade item 3). Creating the index by hand does not
     * record V101 in {@value MaestroSchemaInitializer#HISTORY_TABLE}, so
     * validate still refuses to start; one {@code flyway}-mode run records it —
     * finding the hand-made index and leaving it as it is — and validate then
     * passes with no checksum complaint.
     */
    @Test
    @DisplayName("validate mode: a hand-made V101 index is not enough; one flyway run records V101 over it")
    void validateModeNeedsV101RecordedNotJustTheIndex() throws SQLException {
        // A database main @ a27a275 took to V9 and V100, recorded in Maestro's history.
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
                .as("the index alone does not record V101: validate still refuses to start")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending migrations [101]");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).contains("101");
        assertThat(indexOid("idx_wf_instance_recovery"))
                .as("V101's IF NOT EXISTS found the hand-made index and did not rebuild it")
                .isEqualTo(handMade);
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .as("V101 recorded by Maestro's own run: nothing pending, checksum matches")
                .doesNotThrowAnyException();
    }

    /**
     * The migrations main @ a27a275 ships — V1–V9 and the lock band's V100,
     * nothing newer — as resources of this module, which still ships each of
     * them unchanged.
     */
    private static final List<String> SHIPPED_AT_A27A275 = List.of(
            "db/maestro/store/V1__create_maestro_schema.sql",
            "db/maestro/store/V2__create_indexes.sql",
            "db/maestro/store/V3__timer_lookup_index.sql",
            "db/maestro/store/V4__signal_trace_context.sql",
            "db/maestro/store/V5__signal_received_at_default.sql",
            "db/maestro/store/V6__instance_lease_epoch.sql",
            "db/maestro/store/V7__workflow_event_args_hash.sql",
            "db/maestro/store/V8__retention_indexes.sql",
            "db/maestro/store/V9__signal_message_id.sql",
            "db/maestro/lock/V100__maestro_lock_postgres.sql");

    /**
     * Rolling-deploy safety towards main @ a27a275 (R10): a database that
     * build migrated, then this build migrated, must still start a node of
     * that build — a pod restarted mid-rollout, or a rollback. To a build that
     * does not ship a migration, an applied version below its own highest
     * resolved one (V100) is "missing" and fails Flyway's validation; one
     * above it is "future", which Flyway's default
     * {@code ignoreMigrationPatterns=*:future} ignores. The older Flyway is
     * configured exactly as a27a275's {@code MaestroSchemaInitializer} (no
     * {@code outOfOrder}) over exactly a27a275's migration set, copied out of
     * the classpath so no shipped resource is touched.
     */
    @Test
    @DisplayName("a node on main @ a27a275 starts against a database that already recorded this build's migrations")
    void anOlderBuildStartsAgainstADatabaseThatAlreadyRecordedV101(@TempDir Path older) throws Exception {
        var storeDir = Files.createDirectories(older.resolve("store"));
        var lockDir = Files.createDirectories(older.resolve("lock"));
        for (var resource : SHIPPED_AT_A27A275) {
            var dir = resource.startsWith("db/maestro/lock/") ? lockDir : storeDir;
            try (var in = cl.getResourceAsStream(resource)) {
                Files.copy(Objects.requireNonNull(in, resource),
                        dir.resolve(resource.substring(resource.lastIndexOf('/') + 1)));
            }
        }
        // main @ a27a275's MaestroSchemaInitializer, verbatim: no outOfOrder.
        var a27a275 = Flyway.configure(cl)
                .dataSource(ds)
                .table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations("filesystem:" + storeDir, "filesystem:" + lockDir)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        a27a275.migrate();
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .as("this build recorded every migration it ships, a27a275's and newer")
                .containsAll(shippedVersions())
                .hasSizeGreaterThan(SHIPPED_AT_A27A275.size());

        var restarted = new AtomicReference<MigrateResult>();
        assertThatCode(() -> restarted.set(a27a275.migrate()))
                .as("a27a275's flyway-mode start against the newer history")
                .doesNotThrowAnyException();
        assertThat(restarted.get().migrationsExecuted).isZero();
        assertThat(a27a275.info().pending())
                .as("a27a275's validate mode names nothing pending")
                .isEmpty();
        assertThatCode(a27a275::validate)
                .as("a27a275's validate-mode check against the newer history")
                .doesNotThrowAnyException();
    }

    /**
     * Upgrade safety from main @ {@code cbf5a2a} (WP9 b2): a database that
     * exactly the migration set {@code cbf5a2a} shipped migrated — the frozen
     * copy, not this build's resources — is taken over by this build in both
     * {@code flyway} and {@code validate} mode. An edited shipped migration
     * fails here with Flyway's checksum mismatch; a renamed or deleted one
     * with "applied migration not resolved locally".
     */
    @Test
    @DisplayName("a database migrated by exactly the set shipped at cbf5a2a validates under this build")
    void aDatabaseMigratedByTheFrozenCbf5a2aSetValidatesUnderThisBuild() throws SQLException {
        // cbf5a2a's initializer had outOfOrder(true).
        FrozenMigrations.named("cbf5a2a").olderBuild(ds, true).migrate();
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");

        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize())
                .doesNotThrowAnyException();
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    /**
     * The numbering rule (CLAUDE.md, docs/configuration.md § Schema
     * management): every new Maestro migration, store or lock, takes the next
     * version above the highest shipped, within 101–199. Versions 1–9 and 100
     * are history; band 200–299 is retired. A new version in 10–99 would sort
     * below V100, which every build since the lock band was folded into this
     * module resolves, so a node on such a build could not restart against a
     * database that recorded it (see the test above).
     */
    @Test
    @DisplayName("every migration after V100 takes the next version in 101–199; 1–9 and 100 are history")
    void migrationVersionsFollowTheNumberingRule() {
        var shipped = shippedVersions().stream().map(Integer::parseInt).toList();

        assertThat(shipped)
                .as("no shipped version in 10–99 (an older build reads it as missing) or at or above 200 (retired band)")
                .noneMatch(v -> (v >= 10 && v <= 99) || v >= 200);
        assertThat(shipped.stream().filter(v -> v <= 100).toList())
                .as("versions up to 100 are history: V1–V9 and V100, never added to")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 100);
        var newer = shipped.stream().filter(v -> v > 100).toList();
        assertThat(newer)
                .as("versions above 100 run contiguously from 101")
                .isEqualTo(IntStream.range(101, 101 + newer.size()).boxed().toList());
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
