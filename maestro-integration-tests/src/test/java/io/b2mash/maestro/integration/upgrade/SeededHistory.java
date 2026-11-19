package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.TerminalWait;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.jspecify.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One seeded-history fixture ({@link SeededFixture}) loaded into a fresh
 * database: the history a past build wrote, which WP9, WP1, WP3 and P4 use to
 * prove that this build upgrades and drives history an older build left
 * behind.
 *
 * <p>{@code upgrade/<name>/seeded-history.sql} is a {@code pg_dump} of every
 * {@code maestro_*} table — DDL, {@code maestro_schema_history} and rows —
 * taken after the fixture's {@code SeededHistoryCapture} ran its frozen
 * {@code SeededHistoryWorkflows} on that build's own engine and migrations.
 * {@code capture.properties} records which commit, which server and which
 * fixture source produced it. Neither is ever regenerated at a later commit
 * (RELEASING.md § Upgrade fixtures); every other suite starts from an empty
 * schema, so the fixtures are the only rows an older build wrote.
 *
 * <p>{@link #load(SeededFixture)} gives every caller its own database, so a
 * test may upgrade, recover and mutate it freely. One {@code postgres:16-alpine}
 * container serves every load in the JVM; Ryuk removes it at exit.
 * {@link #load()} loads {@link Cbf5a2aFixture}, the fixture the WP1 tests use.
 *
 * <h2>Using it from a later work package</h2>
 * <ul>
 *   <li>Assertions that hold for every fixture go in
 *       {@code SeededHistoryUpgradeIT}, which runs once per
 *       {@link SeededFixtures#all()} entry. Its class Javadoc's "Pins" section
 *       says which of its assertions may change at all: the pins on history a
 *       past build wrote (the fixture's SHA-256s, clock window and recorded
 *       events) never do; only the pins on what this build appends may.</li>
 *   <li>WP1 adopts {@code cbf5a2a}'s dormant {@code upgrade-fixture-wp1} runs
 *       with {@code node(Seeds.WP1_SERVICE, …)} in
 *       {@code SeededHistoryReplayFidelityIT}.</li>
 *   <li>WP3 calls {@code node.retry(id, fixture.failingWorkflow())} or
 *       {@code node.retry(id, fixture.sagaWorkflow())} on the {@code FAILED}
 *       ids, with the activities built with {@code explodeFails=false} so that
 *       {@code explode} succeeds.</li>
 *   <li>Activity-step keys and expected payload values are in each frozen
 *       {@code Seeds} Javadoc.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #load(SeededFixture)} is thread-safe. An instance is meant for one
 * test thread; the store, data source and serializer it hands out are
 * thread-safe.
 */
public final class SeededHistory {

    /** The directory, relative to the repository root, that {@code fixtureSource.path} is relative to. */
    static final String MODULE_DIR = "maestro-integration-tests";

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final Set<String> COPIED = new HashSet<>();
    private static @Nullable PostgreSQLContainer<?> postgres;

    private final SeededFixture fixture;
    private final PGSimpleDataSource dataSource;
    private final PostgresWorkflowStore store;
    private final PayloadSerializer serializer;
    private final Properties manifest;

    private SeededHistory(SeededFixture fixture, PGSimpleDataSource dataSource, Properties manifest) {
        this.fixture = fixture;
        this.dataSource = dataSource;
        var mapper = JsonMapper.builder().build();
        this.store = new PostgresWorkflowStore(dataSource, mapper);
        this.serializer = new PayloadSerializer(mapper);
        this.manifest = manifest;
    }

    /**
     * {@link #load(SeededFixture)} of {@link Cbf5a2aFixture}.
     *
     * @return the loaded history
     */
    public static SeededHistory load() {
        return load(Cbf5a2aFixture.INSTANCE);
    }

    /**
     * Loads the fixture's dump into a fresh database. The schema is exactly as
     * the fixture's build left it: nothing of this build has touched it until
     * {@link #upgrade(SchemaMode)}.
     *
     * @param fixture the fixture
     * @return the loaded history
     * @throws IllegalStateException if the dump or manifest is missing, or
     *                               {@code psql} fails to restore the dump
     */
    public static SeededHistory load(SeededFixture fixture) {
        var container = container();
        var dumpInContainer = copyDump(container, fixture);
        var database = "seeded_" + DATABASES.incrementAndGet();
        try (var conn = dataSource(container, container.getDatabaseName()).getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create database " + database, e);
        }
        try {
            var result = container.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q",
                    "-U", "test", "-d", database, "-f", dumpInContainer);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("psql could not restore " + fixture.dumpResource() + " (exit "
                        + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted restoring " + fixture.dumpResource(), e);
        }
        return new SeededHistory(fixture, dataSource(container, database), readManifest(fixture));
    }

    /** @return the fixture this history was loaded from */
    public SeededFixture fixture() {
        return fixture;
    }

    /**
     * Runs this build's {@link MaestroSchemaInitializer} over the seeded
     * database, with every Maestro migration location on the test classpath —
     * exactly what a node of this build does at startup.
     *
     * @param mode the schema mode
     * @return this
     */
    public SeededHistory upgrade(SchemaMode mode) {
        new MaestroSchemaInitializer(dataSource, mode, SeededHistory.class.getClassLoader()).initialize();
        return this;
    }

    /**
     * The precondition of every test that runs a node over this history: the
     * wall clock lies after the fixture's {@link SeededFixture#latestMustBeDue()}
     * and before its {@link SeededFixture#earliestMustStayPending()}. It first checks that both constants
     * still match the seeded {@code PENDING} timer rows, so neither can drift
     * from the data. Call it before a node touches the database.
     *
     * @return this
     * @throws IllegalStateException if a constant disagrees with the seeded rows,
     *                               or the clock is outside the window
     */
    public SeededHistory requireClockWithinFixtureWindow() {
        Instant latestDue = null;
        Instant earliestPending = null;
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT workflow_id, timer_id, fire_at FROM maestro_workflow_timer"
                     + " WHERE status = 'PENDING'")) {
            while (rs.next()) {
                var fireAt = rs.getObject(3, OffsetDateTime.class).toInstant();
                if (fixture.mustBeDue().contains(rs.getString(1) + "/" + rs.getString(2))) {
                    latestDue = latestDue == null || fireAt.isAfter(latestDue) ? fireAt : latestDue;
                } else {
                    earliestPending = earliestPending == null || fireAt.isBefore(earliestPending) ? fireAt : earliestPending;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the seeded timer rows", e);
        }
        var latestMustBeDue = fixture.latestMustBeDue();
        var earliestMustStayPending = fixture.earliestMustStayPending();
        if (!latestMustBeDue.equals(latestDue) || !earliestMustStayPending.equals(earliestPending)) {
            throw new IllegalStateException("The clock window of fixture " + fixture.name() + " no longer matches"
                    + " the seeded PENDING timers: latest must-be-due " + latestDue + " (constant " + latestMustBeDue
                    + "), earliest must-stay-pending " + earliestPending + " (constant " + earliestMustStayPending + ")");
        }
        var now = Instant.now();
        if (!now.isAfter(latestMustBeDue) || !now.isBefore(earliestMustStayPending)) {
            throw new IllegalStateException("Wall clock " + now + " is outside the window of fixture "
                    + fixture.name() + ": it must be"
                    + " after " + latestMustBeDue + " (the latest seeded timer a replay needs already due) and"
                    + " before " + earliestMustStayPending + " (the earliest seeded PENDING timer that must still"
                    + " be pending, which a timer poller would otherwise fire). Once the second instant has passed,"
                    + " the fixture must be re-captured (RELEASING.md, Upgrade fixtures).");
        }
        return this;
    }

    /** @return a data source on this history's database */
    public PGSimpleDataSource dataSource() {
        return dataSource;
    }

    /** @return this build's Postgres store over this history's database */
    public PostgresWorkflowStore store() {
        return store;
    }

    /** @return a serializer over the same mapper the store and {@link #node} use */
    public PayloadSerializer serializer() {
        return serializer;
    }

    /** @return {@code capture.properties}: {@code ref}, {@code capturedAt}, {@code serverVersion}, {@code fixtureSource.*} */
    public Properties manifest() {
        var copy = new Properties();
        copy.putAll(manifest);
        return copy;
    }

    /**
     * The frozen workflow source the manifest names, on disk:
     * {@code fixtureSource.path} is relative to {@value #MODULE_DIR}, which is
     * resolved from the repository root, so the result does not depend on the
     * working directory (Gradle runs tests in the module directory, an IDE
     * often in the repository root).
     *
     * @return the absolute path of the frozen source
     * @throws IllegalStateException if no repository root is found, or the file is not there
     */
    public Path fixtureSource() {
        var root = repositoryRoot(Path.of("").toAbsolutePath());
        var source = root.resolve(MODULE_DIR).resolve(manifest.getProperty("fixtureSource.path")).normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("The frozen fixture source named by " + fixture.manifestResource()
                    + " (fixtureSource.path) is not a file: " + source);
        }
        return source;
    }

    /**
     * The repository root: the nearest directory, from {@code start} upwards,
     * that holds {@code settings.gradle.kts}.
     *
     * @param start an absolute directory inside the repository
     * @return the repository root
     * @throws IllegalStateException naming {@code start} if no such directory exists
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

    /**
     * @param workflowId a seeded workflow id
     * @return its events in sequence order, read through this build's store
     * @throws IllegalArgumentException if no instance has that id
     */
    public List<WorkflowEvent> events(String workflowId) {
        var instance = store.getInstance(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("No seeded instance '" + workflowId + "'"));
        return store.getEvents(instance.id());
    }

    /**
     * Polls every 100 ms until the workflow has <em>reached</em>
     * {@code expected}, or {@code within} elapses.
     *
     * <p>Contract:
     * <ul>
     *   <li>"Reached" means the status equals {@code expected} and, when
     *       {@code expected} is terminal, the run is finalised in the log
     *       ({@link TerminalWait#isFinalised}: {@code COMPLETED}/{@code FAILED}
     *       need their terminal event; {@code TERMINATED} appends none).</li>
     *   <li>Returns {@code expected} only once it is reached.</li>
     *   <li>Otherwise, at the deadline, returns the status last observed, which
     *       then differs from {@code expected} — so an assertion on it reads
     *       "expected COMPLETED but was WAITING_SIGNAL" rather than a timeout.</li>
     *   <li>If at the deadline the status column equals {@code expected} but the
     *       terminal event is missing, it throws: returning {@code expected}
     *       there would let a caller's assertion pass on a run that did not
     *       finish.</li>
     * </ul>
     *
     * @param workflowId the workflow id
     * @param expected   the awaited status
     * @param within     the bound
     * @return {@code expected} once reached, else the last observed status (never {@code expected})
     * @throws IllegalArgumentException if no instance has that id
     * @throws IllegalStateException    if the status is {@code expected} and terminal but its
     *                                  terminal event never appeared within the bound
     */
    public WorkflowStatus awaitStatusOrLast(String workflowId, WorkflowStatus expected, Duration within) {
        var deadline = Instant.now().plus(within);
        while (true) {
            var instance = store.getInstance(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("No seeded instance '" + workflowId + "'"));
            var status = instance.status();
            var finalised = !status.isTerminal() || TerminalWait.isFinalised(store, instance);
            if (status == expected && finalised) {
                return status;
            }
            if (!Instant.now().isBefore(deadline)) {
                if (status == expected) {
                    throw new IllegalStateException("Workflow '" + workflowId + "' is " + status
                            + " but its terminal event is not in the log after " + within);
                }
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting " + workflowId, e);
            }
        }
    }

    /**
     * Builds a node of this build over the seeded database: the Postgres lock,
     * a 500 ms wake re-check, every frozen workflow of the fixture registered,
     * and {@code activities} registered for every frozen activity interface. A
     * caller may re-register an activity interface to override it. Nothing is
     * started: the caller decides whether to recover, poll timers or start the
     * recovery poller, and closes the node.
     *
     * @param serviceName the node's service name ({@code Seeds.SERVICE} adopts the seeded runs)
     * @param observer    observes the node's executor and activity proxies
     * @param activities  the fixture's activities ({@link SeededFixture#activities}), which count the calls
     * @return the node
     */
    public MaestroEngineHarness node(String serviceName, RecordingObserver observer, FrozenActivities activities) {
        var node = MaestroEngineHarness.builder(store, JsonMapper.builder().build())
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(dataSource))
                .wakeRecheckInterval(Duration.ofMillis(500))
                .observer(observer)
                .build();
        fixture.workflows().forEach(node::registerWorkflow);
        activities.registerOn(node);
        return node;
    }

    // ── container ─────────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static synchronized PostgreSQLContainer<?> container() {
        if (postgres != null) {
            return postgres;
        }
        var started = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("seeded_template")
                .withUsername("test")
                .withPassword("test");
        started.start();
        postgres = started;
        return started;
    }

    /** Copies the fixture's dump into the container once; returns its path there. */
    private static synchronized String copyDump(PostgreSQLContainer<?> container, SeededFixture fixture) {
        // Checked first: a missing dump must say so, not surface as a copy failure.
        for (var resource : List.of(fixture.dumpResource(), fixture.manifestResource())) {
            if (SeededHistory.class.getClassLoader().getResource(resource) == null) {
                throw new IllegalStateException("Seeded history resource '" + resource + "' is not on the test"
                        + " classpath; it is produced by scripts/capture-upgrade-fixture.sh history <ref> "
                        + fixture.name());
            }
        }
        var inContainer = "/tmp/seeded-history-" + fixture.name() + ".sql";
        if (!COPIED.contains(fixture.name())) {
            container.copyFileToContainer(MountableFile.forClasspathResource(fixture.dumpResource()), inContainer);
            COPIED.add(fixture.name());   // only once the copy succeeded
        }
        return inContainer;
    }

    private static PGSimpleDataSource dataSource(PostgreSQLContainer<?> container, String database) {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {container.getHost()});
        ds.setPortNumbers(new int[] {container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        ds.setDatabaseName(database);
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        return ds;
    }

    private static Properties readManifest(SeededFixture fixture) {
        var properties = new Properties();
        try (var in = SeededHistory.class.getClassLoader().getResourceAsStream(fixture.manifestResource())) {
            if (in == null) {
                throw new IllegalStateException("Missing " + fixture.manifestResource());
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
