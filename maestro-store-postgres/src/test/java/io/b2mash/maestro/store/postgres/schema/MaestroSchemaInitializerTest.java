package io.b2mash.maestro.store.postgres.schema;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.RunLease;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleaner;
import io.b2mash.maestro.store.postgres.notify.PostgresSignalNotifier;
import io.b2mash.maestro.store.postgres.notify.PostgresWakeListener;
import io.b2mash.maestro.store.postgres.notify.WakeChannel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
     * On a database whose highest recorded version is V100, versions above it
     * are applied in order by the numbering rule
     * ({@link #migrationVersionsFollowTheNumberingRule}). On a database that
     * holds the retired V200–V202 rows they are not: V101 and every later
     * migration sort below those rows and apply only out of order, which
     * {@link #aDatabaseHoldingTheRetiredMessagingBandUpgradesToThisBuild} and
     * {@link #aMigrationAddedAfterV101AppliesOnADatabaseHoldingTheRetiredBand}
     * pin.
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
     * A database that ran the retired Postgres messaging transport, exactly as
     * main @ {@code c01d498} left it: V1–V5, V100 and V200–V202, migrated by
     * that build's initializer (no {@code outOfOrder}) over the frozen copy of
     * what it shipped. V202 is the highest applied version, so V6–V9 and V101
     * apply only out of order. The retired rows and the orphan
     * {@code maestro_signal_queue} stay; {@code maestro_task_queue}, which
     * V202 dropped, stays dropped.
     */
    @Test
    @DisplayName("a database holding the retired V200–V202 band upgrades to this build and validates")
    void aDatabaseHoldingTheRetiredMessagingBandUpgradesToThisBuild() throws SQLException {
        FrozenMigrations.named("c01d498").olderBuild(ds, false).migrate();    // c01d498's initializer: no outOfOrder
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "100", "200", "201", "202");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).containsExactly(
                "1", "2", "3", "4", "5", "100", "200", "201", "202", "6", "7", "8", "9", "101");
        assertThat(tables()).contains("maestro_signal_queue").doesNotContain("maestro_task_queue");   // orphan kept, V202's drop kept
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();
    }

    /**
     * Every future V102–V199 sorts below the retired rows on a database
     * holding the band; it applies only out of order. V102 sorts above V100
     * and V101, so only the V200–V202 rows can make it out of order: Flyway
     * reports it {@code OUT_OF_ORDER} here and {@code SUCCESS} on a database
     * without them (the test below). The setup applies this build's
     * migrations through {@link #applyThisBuildsMigrationsOutOfOrder()}, so
     * only the V102 step runs {@link MaestroSchemaInitializer}.
     */
    @Test
    @DisplayName("a migration added after V101 still applies on a database holding the retired band, out of order")
    void aMigrationAddedAfterV101AppliesOnADatabaseHoldingTheRetiredBand(@TempDir Path dir) throws Exception {
        FrozenMigrations.named("c01d498").olderBuild(ds, false).migrate();
        applyThisBuildsMigrationsOutOfOrder();
        var future = withFutureMigration(dir, "V102__future_probe.sql", "CREATE TABLE maestro_future_probe (id INT);");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, future).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).endsWith("101", "102");
        assertThat(stateOf(future, "102")).as("V102 sorts below the retired rows").isEqualTo(MigrationState.OUT_OF_ORDER);
        assertThat(tables()).contains("maestro_future_probe");
        assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, future).initialize())
                .doesNotThrowAnyException();
    }

    /**
     * The control for the test above: the same V102 step on a database the
     * frozen {@code cbf5a2a} set migrated (no V200–V202) applies V102 in
     * order, an ordinary {@code SUCCESS}. The two databases differ in three
     * ways: the band database holds the retired V200–V202 history rows, the
     * orphan {@code maestro_signal_queue} table, and V6–V9 and V101 recorded
     * out of order (after V202). Flyway marks an applied migration
     * {@code OUT_OF_ORDER} when a higher version was recorded before it; of
     * the three, only the retired rows are versions above 102. V6–V9 and V101
     * sort below it, and the orphan table is not in the history at all, so the
     * retired rows are what makes V102 out of order there.
     */
    @Test
    @DisplayName("without the retired band, a migration added after V101 applies in order")
    void aMigrationAddedAfterV101AppliesInOrderWithoutTheRetiredBand(@TempDir Path dir) throws Exception {
        FrozenMigrations.named("cbf5a2a").olderBuild(ds, true).migrate();    // cbf5a2a's initializer: outOfOrder(true)
        var future = withFutureMigration(dir, "V102__future_probe.sql", "CREATE TABLE maestro_future_probe (id INT);");

        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, future).initialize();

        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).endsWith("101", "102");
        assertThat(stateOf(future, "102")).as("V102 sorts above every applied version").isEqualTo(MigrationState.SUCCESS);
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

    // ── the production recipe: docs/configuration.md § Schema management, "In production" ──
    //
    // The six SQL constants below are exactly the sql blocks printed in that
    // section, no more and no fewer; theDocumentedSqlIsTheTestedSql fails if
    // the two drift apart.

    /**
     * The schema grants, issued by the schema's owner (here the container's
     * superuser): a non-superuser migration role needs {@code USAGE} and
     * {@code CREATE}, the nodes' role {@code USAGE}. Issued by a role without
     * the right to grant on the schema (such as {@code maestro_owner}), a
     * {@code GRANT} grants nothing.
     */
    static final String SCHEMA_GRANTS = """
            GRANT USAGE, CREATE ON SCHEMA public TO maestro_owner;
            GRANT USAGE ON SCHEMA public TO maestro_app;
            """;

    /**
     * The runtime table grants, issued by {@code maestro_owner}, which owns the
     * tables. With {@link #SCHEMA_GRANTS}, everything a node does under
     * {@code validate} must work with these and nothing more. No {@code UPDATE}
     * on {@code maestro_workflow_event}: the engine appends and purges history
     * but never rewrites it.
     */
    static final String RUNTIME_GRANTS = """
            GRANT SELECT, INSERT, UPDATE, DELETE ON maestro_workflow_instance, maestro_workflow_timer,
                maestro_workflow_signal, maestro_distributed_lock, maestro_leader_election TO maestro_app;
            GRANT SELECT, INSERT, DELETE ON maestro_workflow_event TO maestro_app;
            GRANT SELECT ON maestro_schema_history TO maestro_app;
            """;

    /**
     * Grants on every table the owner creates in the schema from now on, so a
     * table added by a later migration is usable by the nodes without a new
     * grant. Issued by the owner, after the first migration and
     * {@link #RUNTIME_GRANTS}: issued before the first migration it also
     * covers the tables that migration creates.
     */
    static final String DEFAULT_PRIVILEGES = """
            ALTER DEFAULT PRIVILEGES FOR ROLE maestro_owner IN SCHEMA public
                GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO maestro_app;
            """;

    /**
     * Lists what the nodes' role holds on the two tables where
     * {@link #RUNTIME_GRANTS} grants less than {@link #DEFAULT_PRIVILEGES}
     * would, in the session's current schema only — the schema the
     * unqualified names in the grants and in {@link #WRONG_ORDER_REPAIR}
     * resolve to — so Maestro's tables in another schema granted to the same
     * role add no rows. Run as {@code maestro_owner}:
     * {@code information_schema.role_table_grants} shows a grant only to its
     * grantor, its grantee, a member of either, or a superuser.
     */
    static final String PRIVILEGE_CHECK = """
            SELECT table_name, privilege_type FROM information_schema.role_table_grants
                WHERE grantee = 'maestro_app' AND table_schema = current_schema()
                    AND table_name IN ('maestro_workflow_event', 'maestro_schema_history')
                ORDER BY table_name, privilege_type;
            """;

    /**
     * What the nodes' role can actually do on the same two tables in the
     * session's current schema, wherever the privilege comes from: granted to
     * it, to {@code PUBLIC}, or to a role it inherits from, at table or (for
     * {@code INSERT}, {@code SELECT}, {@code UPDATE}) column level. Unlike
     * {@link #PRIVILEGE_CHECK} it does not depend on who runs it; the caller
     * only needs to resolve the tables ({@code USAGE} on the schema), and
     * gets an error, not an empty result, when it cannot.
     */
    static final String EFFECTIVE_PRIVILEGE_CHECK = """
            SELECT t.table_name, p.privilege_type
                FROM (VALUES ('maestro_schema_history'), ('maestro_workflow_event')) AS t (table_name)
                CROSS JOIN (VALUES ('DELETE'), ('INSERT'), ('SELECT'), ('TRUNCATE'), ('UPDATE')) AS p (privilege_type)
                WHERE CASE WHEN p.privilege_type IN ('DELETE', 'TRUNCATE')
                    THEN has_table_privilege('maestro_app', format('%I.%I', current_schema(), t.table_name), p.privilege_type)
                    ELSE has_any_column_privilege('maestro_app', format('%I.%I', current_schema(), t.table_name), p.privilege_type)
                    END
                ORDER BY t.table_name, p.privilege_type;
            """;

    /** Takes back what {@link #DEFAULT_PRIVILEGES} issued too early added. Issued by {@code maestro_owner}. */
    static final String WRONG_ORDER_REPAIR = """
            REVOKE UPDATE ON maestro_workflow_event FROM maestro_app;
            REVOKE INSERT, UPDATE, DELETE ON maestro_schema_history FROM maestro_app;
            """;

    /** {@link #PRIVILEGE_CHECK}'s rows in the documented order, as "table privilege". */
    private static final List<String> LEAST_PRIVILEGE = List.of(
            "maestro_schema_history SELECT",
            "maestro_workflow_event DELETE", "maestro_workflow_event INSERT", "maestro_workflow_event SELECT");

    /**
     * The production recipe in docs/configuration.md § Schema management, run:
     * a non-superuser owner migrates, and a node then runs under
     * {@code validate} as a role holding only its {@code USAGE} from
     * {@link #SCHEMA_GRANTS} and {@link #RUNTIME_GRANTS}. That role passes
     * validation and drives the store, the lock, the lock cleaner and the
     * signal wake through each kind of statement they issue
     * ({@code SELECT}, {@code INSERT}, {@code INSERT ... ON CONFLICT},
     * {@code UPDATE}, {@code DELETE}, {@code SELECT ... FOR UPDATE SKIP LOCKED},
     * {@code INSERT ... SELECT ... FOR SHARE}, {@code LISTEN},
     * {@code pg_notify}); and it cannot migrate or run any other DDL.
     */
    @Test
    @DisplayName("a role with only the documented DML grants runs validate mode and the store, and cannot migrate")
    void aDmlOnlyRoleRunsValidateModeButCannotMigrate(@TempDir Path dir) throws Exception {
        var owner = lockedDownSchemaMigratedByANonSuperuserOwner();
        try (var conn = owner.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(RUNTIME_GRANTS);
            // Rows a crashed node left behind, expired for more than a minute: the lock cleaner's work.
            stmt.execute("INSERT INTO maestro_distributed_lock (lock_key, token, expires_at)"
                    + " VALUES ('maestro:lock:workflow:crashed', 't', now() - interval '2 minutes')");
            stmt.execute("INSERT INTO maestro_leader_election (election_key, candidate_id, expires_at)"
                    + " VALUES ('maestro:leader:retention:svc', 'crashed', now() - interval '2 minutes')");
        }
        var app = roleDataSource("maestro_app", "app");

        // Startup under validate.
        assertThatCode(() -> new MaestroSchemaInitializer(app, SchemaMode.VALIDATE, cl).initialize())
                .doesNotThrowAnyException();

        // Lock and leader election, then the lock cleaner. release() and clean() log
        // a failure rather than throw it, so their effect is asserted.
        var lock = new PostgresDistributedLock(app);
        var handle = lock.tryAcquire("maestro:lock:workflow:wf-dml", Duration.ofSeconds(30)).orElseThrow();
        assertThat(lock.renew(handle, Duration.ofSeconds(30))).isTrue();
        assertThat(lock.renewAll(List.of(handle), Duration.ofSeconds(30))).isEmpty();
        assertThat(lock.heldKeys(List.of(handle.key()))).containsExactly(handle.key());
        lock.release(handle);
        assertThat(lock.heldKeys(List.of(handle.key()))).as("released").isEmpty();
        assertThat(lock.trySetLeader("maestro:leader:timer-poller:svc", "node-a", Duration.ofSeconds(15))).isTrue();
        assertThat(new PostgresLockCleaner(app).clean()).as("both expired rows deleted").isEqualTo(2);

        // A workflow's life in the store: signals before start, adoption, run-scoped
        // writes, signal consumption, a timer, completion, then the janitor and the sweeper.
        var json = JsonMapper.builder().build();
        var store = new PostgresWorkflowStore(app, json);
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.saveSignal(new WorkflowSignal(UUID.randomUUID(), null, "wf-dml", "approve",
                json.readTree("{\"ok\":true}"), false, now));
        assertThat(store.saveSignalIfAbsent(new WorkflowSignal(UUID.randomUUID(), null, "wf-dml", "approve",
                json.readTree("{\"ok\":true}"), false, now, null, "client:dml-1"))).isTrue();
        var created = store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID()).workflowId("wf-dml").runId(UUID.randomUUID())
                .workflowType("dml").taskQueue("default").status(WorkflowStatus.RUNNING)
                .serviceName("svc").eventSequence(0).startedAt(now).updatedAt(now).version(0)
                .build());
        var lease = new RunLease(created.id(), created.leaseEpoch());
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), created.id(), 1, EventType.ACTIVITY_COMPLETED,
                "step-1", json.readTree("\"done\""), now), lease);
        assertThat(store.getRecoverableInstances("svc", null, 10)).extracting(WorkflowInstance::workflowId)
                .containsExactly("wf-dml");
        var signals = store.getUnconsumedSignals("wf-dml", "approve");
        assertThat(signals).hasSize(2).allSatisfy(s -> assertThat(s.workflowInstanceId()).isEqualTo(created.id()));
        assertThat(store.appendEventAndConsumeSignal(new WorkflowEvent(UUID.randomUUID(), created.id(), 2,
                EventType.SIGNAL_RECEIVED, "approve", json.readTree("{\"ok\":true}"), now), lease,
                signals.get(0).id())).isTrue();
        assertThat(store.markSignalConsumed(signals.get(1).id(), lease)).isTrue();
        var timer = new WorkflowTimer(UUID.randomUUID(), created.id(), "wf-dml", "t-1",
                now.minusSeconds(1), TimerStatus.PENDING, now);
        store.saveTimer(timer, lease);
        assertThat(store.getDueTimers(Instant.now(), 10)).extracting(WorkflowTimer::id).containsExactly(timer.id());
        assertThat(store.markTimerFired(timer.id(), lease)).isTrue();
        var running = created.toBuilder().eventSequence(3).updatedAt(now).version(1).build();
        store.updateInstance(running, lease);
        var completedAt = now.minusSeconds(60);
        assertThat(store.completeInstance(running.toBuilder().status(WorkflowStatus.COMPLETED)
                        .completedAt(completedAt).updatedAt(completedAt).version(2).build(),
                new WorkflowEvent(UUID.randomUUID(), created.id(), 4, EventType.WORKFLOW_COMPLETED,
                        null, json.readTree("\"done\""), now), lease)).isTrue();
        assertThat(store.deleteUnconsumedSignalsForTerminalInstancesBefore(now)).isZero();
        assertThat(store.purgeTerminalInstancesBefore(WorkflowStatus.COMPLETED, now, 10).instances()).isOne();
        assertThat(store.getInstance("wf-dml")).isEmpty();

        // Cross-node signal wake: LISTEN and pg_notify as the role.
        var channel = WakeChannel.channelFor("svc");
        var woken = new LinkedBlockingQueue<String>();
        try (var listener = new PostgresWakeListener(app, channel);
             var notifier = new PostgresSignalNotifier(app, listener, channel)) {
            listener.start();
            assertThat(listener.isConnected()).isTrue();
            listener.register("wf-dml", woken::add);
            notifier.publish("wf-dml", "approve");
            assertThat(woken.poll(10, TimeUnit.SECONDS)).isEqualTo("approve");
        }

        // No DDL: a migration attempted as the role fails on a permission check, and records nothing.
        var future = withFutureMigration(dir, "V102__future_probe.sql", "CREATE TABLE maestro_future_probe (id INT);");
        assertThatThrownBy(() -> new MaestroSchemaInitializer(app, SchemaMode.FLYWAY, future).initialize())
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"))
                .hasMessageContaining("permission denied for schema public");
        assertThat(tables()).doesNotContain("maestro_future_probe");
        assertThat(versions(MaestroSchemaInitializer.HISTORY_TABLE)).doesNotContain("102");
        for (var ddl : List.of(
                "CREATE TABLE maestro_future_probe (id INT)",
                "CREATE INDEX IF NOT EXISTS idx_wf_instance_recovery ON maestro_workflow_instance (service_name)",
                "ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS probe TEXT")) {
            try (var conn = app.getConnection(); var stmt = conn.createStatement()) {
                assertThatThrownBy(() -> stmt.execute(ddl))
                        .as(ddl)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            }
        }
    }

    /**
     * A table added by a later migration, with and without the documented
     * {@link #DEFAULT_PRIVILEGES}. {@code validate} checks migration history,
     * never grants, so without them the node starts and the new table then
     * refuses every statement with SQLSTATE {@code 42501}. With them issued
     * first, the next migration's table is usable at once; they are not
     * retroactive, so the earlier table stays refused.
     */
    @Test
    @DisplayName("a table added by a later migration is refused to the app role unless default privileges were issued first")
    void aTableAddedByALaterMigrationNeedsDefaultPrivileges(@TempDir Path dir) throws Exception {
        var owner = lockedDownSchemaMigratedByANonSuperuserOwner();
        execute(owner, RUNTIME_GRANTS);
        var app = roleDataSource("maestro_app", "app");

        var v102 = withFutureMigration(dir, "V102__future_table.sql", "CREATE TABLE maestro_future_probe (id INT);");
        new MaestroSchemaInitializer(owner, SchemaMode.FLYWAY, v102).initialize();
        assertThatCode(() -> new MaestroSchemaInitializer(app, SchemaMode.VALIDATE, v102).initialize())
                .as("validate checks history, never grants")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> execute(app, "INSERT INTO maestro_future_probe VALUES (1)"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"))
                .hasMessageContaining("permission denied for table maestro_future_probe");

        execute(owner, DEFAULT_PRIVILEGES);                 // the documented order: migrate, table grants, then this
        assertThat(privilegeCheck(owner)).as("documented order: least privilege").isEqualTo(LEAST_PRIVILEGE);
        assertThat(effectivePrivilegeCheck(owner)).as("documented order: effective").isEqualTo(LEAST_PRIVILEGE);
        assertAppCannotRewriteHistory(app);
        var v103 = withFutureMigration(dir, "V103__future_table.sql", "CREATE TABLE maestro_future_probe_2 (id INT);");
        new MaestroSchemaInitializer(owner, SchemaMode.FLYWAY, v103).initialize();
        assertThatCode(() -> new MaestroSchemaInitializer(app, SchemaMode.VALIDATE, v103).initialize())
                .doesNotThrowAnyException();
        execute(app, "INSERT INTO maestro_future_probe_2 VALUES (1)");
        execute(app, "UPDATE maestro_future_probe_2 SET id = 2 WHERE id = 1");
        try (var conn = app.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT id FROM maestro_future_probe_2")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        execute(app, "DELETE FROM maestro_future_probe_2");
        assertThatThrownBy(() -> execute(app, "SELECT * FROM maestro_future_probe"))
                .as("default privileges cover only tables created after them")
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    /**
     * {@link #DEFAULT_PRIVILEGES} issued in the wrong order, before the first
     * migration: they then cover the tables that migration creates, so the
     * nodes' role can rewrite {@code maestro_workflow_event} and write
     * {@code maestro_schema_history}. {@link #PRIVILEGE_CHECK} shows it, and
     * {@link #WRONG_ORDER_REPAIR} restores least privilege. A second schema
     * holding Maestro's tables, over-granted to the same {@code maestro_app}
     * (one role for several services' schemas, "Isolating services" in
     * docs/configuration.md), adds no rows to the check. A role that is
     * neither grantor nor grantee, nor a member of either, sees no rows at
     * all. A fresh database of the shared container, like every test here; no
     * extra container.
     */
    @Test
    @DisplayName("default privileges issued before the first migration over-grant; the documented check finds it and the repair undoes it")
    void defaultPrivilegesBeforeTheFirstMigrationOverGrant() throws Exception {
        var owner = lockedDownSchemaWithANonSuperuserOwner();
        anotherServicesSchemaOverGrantedToTheSameAppRole();
        execute(owner, DEFAULT_PRIVILEGES);                 // wrong: before the first migration
        new MaestroSchemaInitializer(owner, SchemaMode.FLYWAY, cl).initialize();
        execute(owner, RUNTIME_GRANTS);
        var app = roleDataSource("maestro_app", "app");

        assertThat(privilegeCheck(owner)).as("wrong order: the check shows the extra privileges").containsExactly(
                "maestro_schema_history DELETE", "maestro_schema_history INSERT", "maestro_schema_history SELECT",
                "maestro_schema_history UPDATE",
                "maestro_workflow_event DELETE", "maestro_workflow_event INSERT", "maestro_workflow_event SELECT",
                "maestro_workflow_event UPDATE");
        assertThat(effectivePrivilegeCheck(owner)).as("wrong order: effective").isEqualTo(privilegeCheck(owner));
        assertThatCode(() -> execute(app, "UPDATE maestro_workflow_event SET step_name = step_name"))
                .as("wrong order: the app role can rewrite workflow history").doesNotThrowAnyException();
        assertThatCode(() -> execute(app, "UPDATE maestro_schema_history SET description = description"))
                .as("wrong order: the app role can write the migration history").doesNotThrowAnyException();
        assertThat(privilegeCheck(ds)).as("a superuser sees every row").isEqualTo(privilegeCheck(owner));
        var unrelated = unrelatedRole();
        assertThat(currentSchema(unrelated)).isEqualTo("public");
        assertThat(privilegeCheck(unrelated))
                .as("a role neither grantor nor grantee, nor a member of either, sees no rows: not a pass")
                .isEmpty();
        assertThat(effectivePrivilegeCheck(unrelated)).as("the effective check does not depend on who runs it")
                .isEqualTo(effectivePrivilegeCheck(owner));

        execute(owner, WRONG_ORDER_REPAIR);

        assertThat(privilegeCheck(owner)).as("repaired: least privilege").isEqualTo(LEAST_PRIVILEGE);
        assertThat(effectivePrivilegeCheck(owner)).as("repaired: effective").isEqualTo(LEAST_PRIVILEGE);
        assertAppCannotRewriteHistory(app);
    }

    /**
     * {@link #PRIVILEGE_CHECK} reads grants recorded <em>to</em>
     * {@code maestro_app}. A privilege the role holds through {@code PUBLIC},
     * or through a role it inherits from — here a column-level
     * {@code UPDATE} — is not among them: the check still returns exactly
     * {@link #LEAST_PRIVILEGE} while the role can rewrite workflow history.
     * {@link #EFFECTIVE_PRIVILEGE_CHECK} asks PostgreSQL what the role can
     * actually do, and shows both.
     */
    @Test
    @DisplayName("privileges held through PUBLIC or a parent role escape the grants check; the effective-privilege check shows them")
    void privilegesHeldThroughPublicOrAParentRoleEscapeTheGrantsCheck() throws Exception {
        var owner = lockedDownSchemaMigratedByANonSuperuserOwner();
        execute(owner, RUNTIME_GRANTS);
        var app = roleDataSource("maestro_app", "app");
        assertThat(privilegeCheck(owner)).isEqualTo(LEAST_PRIVILEGE);
        assertThat(effectivePrivilegeCheck(owner)).as("documented state").isEqualTo(LEAST_PRIVILEGE);

        execute(owner, "GRANT UPDATE ON maestro_workflow_event TO PUBLIC");
        assertThat(privilegeCheck(owner)).as("through PUBLIC: the grants check sees nothing").isEqualTo(LEAST_PRIVILEGE);
        assertThatCode(() -> execute(app, "UPDATE maestro_workflow_event SET step_name = step_name"))
                .as("through PUBLIC: the app role can rewrite workflow history").doesNotThrowAnyException();
        assertThat(effectivePrivilegeCheck(owner)).as("through PUBLIC").containsExactly(
                "maestro_schema_history SELECT",
                "maestro_workflow_event DELETE", "maestro_workflow_event INSERT", "maestro_workflow_event SELECT",
                "maestro_workflow_event UPDATE");
        execute(owner, "REVOKE UPDATE ON maestro_workflow_event FROM PUBLIC");

        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'maestro_app_parent') THEN"
                    + " CREATE ROLE maestro_app_parent NOLOGIN; END IF; END $$");
            stmt.execute("GRANT maestro_app_parent TO maestro_app");
        }
        try {
            execute(owner, "GRANT UPDATE (step_name) ON maestro_workflow_event TO maestro_app_parent");
            assertThat(privilegeCheck(owner)).as("through a parent role: the grants check sees nothing")
                    .isEqualTo(LEAST_PRIVILEGE);
            assertThatCode(() -> execute(app, "UPDATE maestro_workflow_event SET step_name = step_name"))
                    .as("through a parent role: the app role can rewrite workflow history").doesNotThrowAnyException();
            assertThat(effectivePrivilegeCheck(owner)).as("through a parent role, one column").containsExactly(
                    "maestro_schema_history SELECT",
                    "maestro_workflow_event DELETE", "maestro_workflow_event INSERT", "maestro_workflow_event SELECT",
                    "maestro_workflow_event UPDATE");
            execute(owner, "REVOKE UPDATE (step_name) ON maestro_workflow_event FROM maestro_app_parent");
        } finally {
            try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
                stmt.execute("REVOKE maestro_app_parent FROM maestro_app");
            }
        }

        assertThat(effectivePrivilegeCheck(owner)).as("revoked where granted").isEqualTo(LEAST_PRIVILEGE);
        assertAppCannotRewriteHistory(app);
    }

    /**
     * A second schema, {@code other_service}, holding Maestro's tables:
     * migrated by {@code maestro_owner} and granted to {@code maestro_app}
     * with every privilege on the two tables {@link #PRIVILEGE_CHECK} lists,
     * so any row of it that leaked into the check would show.
     */
    private void anotherServicesSchemaOverGrantedToTheSameAppRole() throws SQLException {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA other_service");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA other_service TO maestro_owner");
            stmt.execute("GRANT USAGE ON SCHEMA other_service TO maestro_app");
        }
        var otherOwner = roleDataSource("maestro_owner", "owner");
        otherOwner.setCurrentSchema("other_service");
        new MaestroSchemaInitializer(otherOwner, SchemaMode.FLYWAY, cl).initialize();
        execute(otherOwner, "GRANT ALL ON maestro_workflow_event, maestro_schema_history TO maestro_app");
        assertThat(privilegeCheck(otherOwner)).as("the other schema is over-granted").contains(
                "maestro_schema_history UPDATE", "maestro_workflow_event UPDATE");
    }

    /**
     * A login role with no tie to {@code maestro_owner} or {@code maestro_app},
     * and no superuser. It holds {@code USAGE} on {@code public}, so its
     * {@code current_schema()} is {@code public} like the owner's: what hides
     * the rows from it is the view's visibility rule, not the schema filter.
     */
    private PGSimpleDataSource unrelatedRole() throws SQLException {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            createLoginRole(stmt, "maestro_unrelated", "unrelated");
            stmt.execute("GRANT USAGE ON SCHEMA public TO maestro_unrelated");
        }
        return roleDataSource("maestro_unrelated", "unrelated");
    }

    /**
     * docs/configuration.md prints the SQL the role tests execute: the set of
     * {@code sql} blocks in its "In production" text is exactly the set of
     * tested constants, line endings normalised, so an extra statement inside
     * a block or an extra block fails here, and a CRLF checkout does not. The
     * docs file is an input of this module's {@code test} task
     * (build.gradle.kts), so a doc-only edit reruns this test instead of being
     * served from the build cache. It is found from the repository root
     * ({@link #repositoryRoot}), so the test runs from any working directory
     * inside the repository. The guard sees only fenced {@code sql} blocks
     * between its two delimiting headings ("In production" and
     * "{@code validate} and {@code none}"): SQL in a fence of another language,
     * or anywhere after the end marker, is not checked.
     */
    @Test
    @DisplayName("docs/configuration.md prints exactly the SQL the role tests execute")
    void theDocumentedSqlIsTheTestedSql() throws IOException {
        var path = repositoryRoot(Path.of("").toAbsolutePath()).resolve("docs/configuration.md");
        assertThat(path).as("the documented production grants").isRegularFile();
        var doc = Files.readString(path).replace("\r\n", "\n");
        var start = doc.indexOf("**In production.**");
        var end = doc.indexOf("**`validate` and `none`.**", start);
        assertThat(start).as("the \"In production\" text").isNotNegative();
        assertThat(end).as("the \"In production\" text ends where \"validate and none\" starts").isGreaterThan(start);

        var blocks = new ArrayList<String>();
        var fence = Pattern.compile("^```sql\n(.*?)^```$", Pattern.DOTALL | Pattern.MULTILINE).matcher(doc.substring(start, end));
        while (fence.find()) {
            blocks.add(fence.group(1));
        }

        assertThat(blocks).containsExactlyInAnyOrder(
                SCHEMA_GRANTS, RUNTIME_GRANTS, DEFAULT_PRIVILEGES, PRIVILEGE_CHECK, EFFECTIVE_PRIVILEGE_CHECK,
                WRONG_ORDER_REPAIR);
    }

    /**
     * {@link #repositoryRoot} must not depend on the working directory: Gradle
     * runs tests in the module directory, an IDE often in the repository
     * root. Both find the same root; a tree without {@code settings.gradle.kts}
     * fails naming where it looked.
     */
    @Test
    @DisplayName("repositoryRoot finds the root from the root and from a module directory, and names the start when there is none")
    void repositoryRootIsFoundFromTheRootAndFromAModule(@TempDir Path dir) throws IOException {
        var root = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(root.resolve("settings.gradle.kts"), "");
        var module = Files.createDirectories(root.resolve("maestro-store-postgres"));
        var outside = Files.createDirectories(dir.resolve("elsewhere"));

        assertThat(repositoryRoot(root)).isEqualTo(root);
        assertThat(repositoryRoot(module)).isEqualTo(root);
        assertThat(repositoryRoot(module.resolve("src/../"))).isEqualTo(root);
        assertThatThrownBy(() -> repositoryRoot(outside))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(outside.toString());
    }

    /**
     * The repository root: the nearest directory, from {@code start} upwards,
     * that holds {@code settings.gradle.kts}.
     *
     * @throws IllegalStateException naming {@code start} if there is none
     */
    static Path repositoryRoot(Path start) {
        for (var dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
        }
        throw new IllegalStateException("No settings.gradle.kts in " + start.toAbsolutePath().normalize()
                + " or any directory above it: cannot find the repository root");
    }

    // ── helpers ─────────────────────────────────────────────────────

    /**
     * The setup of the production recipe on this test's database. {@code PUBLIC}
     * is stripped of its rights on the schema, so every right a role uses is
     * one granted here; the schema's owner issues {@link #SCHEMA_GRANTS};
     * {@code maestro_owner}, a non-superuser holding only those, migrates and
     * so owns every {@code maestro_*} table; {@code maestro_app} holds only
     * {@code USAGE} on the schema.
     *
     * @return the owner's data source
     */
    private PGSimpleDataSource lockedDownSchemaMigratedByANonSuperuserOwner() throws SQLException {
        var owner = lockedDownSchemaWithANonSuperuserOwner();
        new MaestroSchemaInitializer(owner, SchemaMode.FLYWAY, cl).initialize();
        return owner;
    }

    /** {@link #lockedDownSchemaMigratedByANonSuperuserOwner()} before the first migration. */
    private PGSimpleDataSource lockedDownSchemaWithANonSuperuserOwner() throws SQLException {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            createLoginRole(stmt, "maestro_owner", "owner");
            createLoginRole(stmt, "maestro_app", "app");
            stmt.execute(SCHEMA_GRANTS);
        }
        return roleDataSource("maestro_owner", "owner");
    }

    /** {@link #PRIVILEGE_CHECK}'s rows, as "table privilege", run as {@code owner}. */
    private static List<String> privilegeCheck(PGSimpleDataSource owner) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = owner.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery(PRIVILEGE_CHECK)) {
            while (rs.next()) {
                rows.add(rs.getString(1) + " " + rs.getString(2));
            }
        }
        return rows;
    }

    /** {@link #EFFECTIVE_PRIVILEGE_CHECK}'s rows, as "table privilege", run as {@code on}. */
    private static List<String> effectivePrivilegeCheck(PGSimpleDataSource on) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = on.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery(EFFECTIVE_PRIVILEGE_CHECK)) {
            while (rs.next()) {
                rows.add(rs.getString(1) + " " + rs.getString(2));
            }
        }
        return rows;
    }

    private static String currentSchema(PGSimpleDataSource on) throws SQLException {
        try (var conn = on.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT current_schema()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** The nodes' role may append and purge workflow history, never rewrite it, and never write the migration history. */
    private static void assertAppCannotRewriteHistory(PGSimpleDataSource app) {
        for (var write : List.of(
                "UPDATE maestro_workflow_event SET step_name = step_name",
                "UPDATE maestro_schema_history SET description = description",
                "DELETE FROM maestro_schema_history WHERE false")) {
            assertThatThrownBy(() -> execute(app, write))
                    .as(write)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    /** Roles are cluster-wide and every test here shares one server: create {@code role} once. */
    private static void createLoginRole(Statement stmt, String role, String password) throws SQLException {
        stmt.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + role + "') THEN"
                + " CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'; END IF; END $$");
    }

    /** A data source on this test's database, connecting as {@code role}. */
    private PGSimpleDataSource roleDataSource(String role, String password) {
        var roleDs = new PGSimpleDataSource();
        roleDs.setServerNames(ds.getServerNames());
        roleDs.setPortNumbers(ds.getPortNumbers());
        roleDs.setDatabaseName(ds.getDatabaseName());
        roleDs.setUser(role);
        roleDs.setPassword(password);
        return roleDs;
    }

    private static void execute(PGSimpleDataSource on, String sql) throws SQLException {
        try (var conn = on.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Applies every migration this build ships, as {@link MaestroSchemaInitializer}
     * does, but through an explicit Flyway with {@code outOfOrder(true)}: test
     * setup that does not depend on the production setting under test.
     */
    private void applyThisBuildsMigrationsOutOfOrder() {
        Flyway.configure(cl).dataSource(ds).table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations(MaestroSchemaInitializer.discoverLocations(cl).toArray(String[]::new))
                .baselineOnMigrate(true).baselineVersion("0")
                .outOfOrder(true)
                .load().migrate();
    }

    /**
     * Flyway's {@code info()} state of applied {@code version}, read through
     * {@code loader}'s locations. Flyway derives {@code OUT_OF_ORDER} from the
     * recorded history (a version installed after a higher one), not from the
     * {@code outOfOrder} setting of the Flyway that reads it.
     */
    private MigrationState stateOf(ClassLoader loader, String version) {
        return Arrays.stream(Flyway.configure(loader).dataSource(ds).table(MaestroSchemaInitializer.HISTORY_TABLE)
                        .locations(MaestroSchemaInitializer.discoverLocations(loader).toArray(String[]::new))
                        .load().info().applied())
                .filter(migration -> migration.getVersion() != null && migration.getVersion().getVersion().equals(version))
                .map(MigrationInfo::getState)
                .findFirst()
                .orElseThrow(() -> new AssertionError("version " + version + " is not applied"));
    }

    /**
     * This build plus one migration it does not ship: a class loader (parent:
     * the test class loader) over {@code dir}, which holds a
     * {@value MaestroSchemaInitializer#LOCATIONS_RESOURCE} resource naming
     * {@code filesystem:<dir>/future}, and {@code <dir>/future/<fileName>}
     * holding {@code sql}. No shipped resource is touched.
     */
    @SuppressWarnings("resource") // lives as long as the @TempDir it reads
    private ClassLoader withFutureMigration(Path dir, String fileName, String sql) throws IOException {
        var future = Files.createDirectories(dir.resolve("future"));
        Files.writeString(future.resolve(fileName), sql);
        var locations = dir.resolve(MaestroSchemaInitializer.LOCATIONS_RESOURCE);
        Files.createDirectories(locations.getParent());
        Files.writeString(locations, "filesystem:" + future + "\n");
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, cl);
    }

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
