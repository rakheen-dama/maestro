package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.TerminalWait;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Invocations;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedRiskActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedResultWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.StepGate;
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
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The seeded history written by the build at {@code cbf5a2a}, loaded into a
 * fresh database: the fixture WP9, WP1 and WP3 use to prove that this build
 * upgrades and drives history an older build left behind.
 *
 * <p>{@code upgrade/cbf5a2a/seeded-history.sql} is a {@code pg_dump} of every
 * {@code maestro_*} table — DDL, {@code maestro_schema_history} and rows —
 * taken after {@code SeededHistoryCapture} ran the frozen
 * {@code SeededHistoryWorkflows} on {@code cbf5a2a}'s own engine and
 * migrations. {@code capture.properties} records which commit, which server
 * and which fixture source produced it. Neither is ever regenerated at a
 * later commit (the plan's "Regeneration rules"); every other suite starts
 * from an empty schema, so this is the only fixture holding rows an older
 * build wrote.
 *
 * <p>{@link #load()} gives every caller its own database, so a test may
 * upgrade, recover and mutate it freely. One {@code postgres:16-alpine}
 * container serves every load in the JVM; Ryuk removes it at exit.
 *
 * <h2>Using it from a later work package</h2>
 * <ul>
 *   <li>New assertions go in new test classes that call {@link #load()}.
 *       WP9's assertions in {@code SeededHistoryUpgradeIT} change only as a
 *       named plan step. Its class Javadoc's "Pins" section says which ones
 *       may change at all: the pins on history {@code cbf5a2a} wrote
 *       (including {@link #DUMP_SHA256}, {@link #CAPTURE_MANIFEST_SHA256} and
 *       the clock-window constants below) never do; only the pins on what
 *       this build appends may.</li>
 *   <li>WP1 adopts the dormant {@code upgrade-fixture-wp1} runs with
 *       {@code node(Seeds.WP1_SERVICE, …)}. Each parked on {@code go} before
 *       its consumer activity ran, so no recorded step depends on the first
 *       result and none stands down: they complete on this build, each
 *       consumer receiving the first result as it reads back (the exact
 *       decimal, the stamp in UTC). WP1 pins this, and that this build writes
 *       the bytes {@code cbf5a2a} wrote, in
 *       {@code SeededHistoryReplayFidelityIT}.</li>
 *   <li>WP3 calls {@code node.retry(id, SeedFailingWorkflow.class)} or
 *       {@code node.retry(id, SeedSagaWorkflow.class)} on the {@code FAILED}
 *       ids. It may first re-register
 *       {@code new SeedActivities(invocations, StepGate.OPEN, false)} so that
 *       {@code explode} succeeds.</li>
 *   <li>Activity-step keys and expected payload values are in the
 *       {@code Seeds} Javadoc.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #load()} is thread-safe. An instance is meant for one test thread;
 * the store, data source and serializer it hands out are thread-safe.
 */
public final class SeededHistory {

    /** Classpath location of the dump. */
    static final String DUMP_RESOURCE = "upgrade/cbf5a2a/seeded-history.sql";
    /** Classpath location of the capture manifest. */
    static final String MANIFEST_RESOURCE = "upgrade/cbf5a2a/capture.properties";

    /**
     * SHA-256 of the raw bytes of {@link #DUMP_RESOURCE} as the capture at
     * {@code cbf5a2a} wrote it (the value the capture script printed). The
     * row-level guards (counts, statuses, event types, the WP1 decimal) cannot
     * see a hand-edited payload or timer row; this digest can. Never update it
     * without re-capturing under the regeneration rules.
     */
    public static final String DUMP_SHA256 = "ee06b7f9359e2b4cf7ca5b6ff493ae42afc9624073ff58ba7b316476ebffa134";

    /**
     * SHA-256 of the raw bytes of {@link #MANIFEST_RESOURCE} as committed with
     * the dump. The manifest records the frozen workflow source's path
     * ({@code fixtureSource.path}) and SHA-256 ({@code fixtureSource.sha256}),
     * and {@code theDumpWasCapturedAtCbf5a2a} checks that file's raw bytes
     * against them; pinning the manifest therefore pins the source too, so
     * editing the source together with its recorded hash, or pointing the path
     * at another file, must also change this literal, in a diff a reviewer
     * sees. Never update it without re-capturing under the regeneration rules.
     */
    public static final String CAPTURE_MANIFEST_SHA256 = "b71ca8e04bf77f91baf7a0bd77b22799e7980a13f5127d5f7e23f562557f93af";

    /** The directory, relative to the repository root, that {@code fixtureSource.path} is relative to. */
    static final String MODULE_DIR = "maestro-integration-tests";

    /**
     * The seeded {@code PENDING} timer rows a replay needs <em>already due</em>,
     * as {@code "<workflowId>/<timerId>"}: {@code seed-parked-timer}'s 10-minute
     * nap (fired by the timer poller) and {@code seed-parked-express}'s
     * 10-minute {@code express} deadline (self-fired by {@code awaitSignal}
     * during replay).
     */
    static final Set<String> MUST_BE_DUE = Set.of("seed-parked-timer/sleep-8002", "seed-parked-express/await-signal-8004");

    /**
     * The latest {@code fire_at} among {@link #MUST_BE_DUE}
     * ({@code seed-parked-express}'s {@code await-signal-8004}). Before this
     * instant the parked runs would wait out real time instead of replaying.
     */
    public static final Instant LATEST_MUST_BE_DUE = Instant.parse("2026-11-12T15:02:18.853649Z");

    /**
     * The earliest {@code fire_at} among every other seeded {@code PENDING}
     * timer row ({@code seed-parked-approval}'s 10-year {@code approval}
     * deadline; {@code seed-terminated}'s and the three {@code seed-wp1-*} ones
     * follow within seconds). {@code getDueTimers} has no service filter, so
     * from this instant on a timer poller would fire them: the parked approval
     * would time out, and the terminated and WP1 rows would change.
     */
    public static final Instant EARLIEST_MUST_STAY_PENDING = Instant.parse("2036-11-09T14:52:08.619137Z");

    private static final String DUMP_IN_CONTAINER = "/tmp/seeded-history-cbf5a2a.sql";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static @Nullable PostgreSQLContainer<?> postgres;

    private final PGSimpleDataSource dataSource;
    private final PostgresWorkflowStore store;
    private final PayloadSerializer serializer;
    private final Properties manifest;

    private SeededHistory(PGSimpleDataSource dataSource, Properties manifest) {
        this.dataSource = dataSource;
        var mapper = JsonMapper.builder().build();
        this.store = new PostgresWorkflowStore(dataSource, mapper);
        this.serializer = new PayloadSerializer(mapper);
        this.manifest = manifest;
    }

    /**
     * Loads the dump into a fresh database. The schema is exactly as
     * {@code cbf5a2a} left it: nothing of this build has touched it until
     * {@link #upgrade(SchemaMode)}.
     *
     * @return the loaded history
     * @throws IllegalStateException if the dump or manifest is missing, or
     *                               {@code psql} fails to restore the dump
     */
    public static SeededHistory load() {
        var container = container();
        var database = "seeded_" + DATABASES.incrementAndGet();
        try (var conn = dataSource(container, container.getDatabaseName()).getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create database " + database, e);
        }
        try {
            var result = container.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q",
                    "-U", "test", "-d", database, "-f", DUMP_IN_CONTAINER);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("psql could not restore " + DUMP_RESOURCE + " (exit "
                        + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted restoring " + DUMP_RESOURCE, e);
        }
        return new SeededHistory(dataSource(container, database), readManifest());
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
     * wall clock lies after {@link #LATEST_MUST_BE_DUE} and before
     * {@link #EARLIEST_MUST_STAY_PENDING}. It first checks that both constants
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
                if (MUST_BE_DUE.contains(rs.getString(1) + "/" + rs.getString(2))) {
                    latestDue = latestDue == null || fireAt.isAfter(latestDue) ? fireAt : latestDue;
                } else {
                    earliestPending = earliestPending == null || fireAt.isBefore(earliestPending) ? fireAt : earliestPending;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the seeded timer rows", e);
        }
        if (!LATEST_MUST_BE_DUE.equals(latestDue) || !EARLIEST_MUST_STAY_PENDING.equals(earliestPending)) {
            throw new IllegalStateException("The clock window no longer matches the seeded PENDING timers: latest"
                    + " must-be-due " + latestDue + " (constant " + LATEST_MUST_BE_DUE + "), earliest must-stay-pending "
                    + earliestPending + " (constant " + EARLIEST_MUST_STAY_PENDING + ")");
        }
        var now = Instant.now();
        if (!now.isAfter(LATEST_MUST_BE_DUE) || !now.isBefore(EARLIEST_MUST_STAY_PENDING)) {
            throw new IllegalStateException("Wall clock " + now + " is outside the seeded-history window: it must be"
                    + " after " + LATEST_MUST_BE_DUE + " (the latest seeded timer a replay needs already due) and"
                    + " before " + EARLIEST_MUST_STAY_PENDING + " (the earliest seeded PENDING timer that must still"
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
            throw new IllegalStateException("The frozen fixture source named by " + MANIFEST_RESOURCE
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
     * a 500 ms wake re-check, every frozen workflow registered, and one
     * {@code SeedActivities(invocations, StepGate.OPEN, true)} registered for
     * all five activity interfaces. A caller may re-register an activity
     * interface to override it. Nothing is started: the caller decides
     * whether to recover, poll timers or start the recovery poller, and
     * closes the node.
     *
     * @param serviceName the node's service name ({@code Seeds.SERVICE} adopts the seeded runs)
     * @param observer    observes the node's executor and activity proxies
     * @param invocations counts the activity calls the node makes
     * @return the node
     */
    public MaestroEngineHarness node(String serviceName, RecordingObserver observer, Invocations invocations) {
        var node = MaestroEngineHarness.builder(store, JsonMapper.builder().build())
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(dataSource))
                .wakeRecheckInterval(Duration.ofMillis(500))
                .observer(observer)
                .build();
        node.registerWorkflow(SeedOrderWorkflow.class);
        node.registerWorkflow(SeedSagaWorkflow.class);
        node.registerWorkflow(SeedFailingWorkflow.class);
        node.registerWorkflow(SeedTypedResultWorkflow.class);
        var activities = new SeedActivities(invocations, StepGate.OPEN, true);
        node.registerActivities(SeedOrderActivities.class, activities);
        node.registerActivities(SeedRiskActivities.class, activities);
        node.registerActivities(SeedSagaActivities.class, activities);
        node.registerActivities(SeedFailingActivities.class, activities);
        node.registerActivities(SeedTypedActivities.class, activities);
        return node;
    }

    // ── container ─────────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static synchronized PostgreSQLContainer<?> container() {
        if (postgres != null) {
            return postgres;
        }
        // Checked first: a missing dump must say so, not surface as a
        // container or copy failure.
        for (var resource : List.of(DUMP_RESOURCE, MANIFEST_RESOURCE)) {
            if (SeededHistory.class.getClassLoader().getResource(resource) == null) {
                throw new IllegalStateException("Seeded history resource '" + resource + "' is not on the test"
                        + " classpath; it is produced by scripts/capture-upgrade-fixture.sh history cbf5a2a cbf5a2a");
            }
        }
        var started = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("seeded_template")
                .withUsername("test")
                .withPassword("test");
        started.start();
        started.copyFileToContainer(MountableFile.forClasspathResource(DUMP_RESOURCE), DUMP_IN_CONTAINER);
        postgres = started;
        return started;
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

    private static Properties readManifest() {
        var properties = new Properties();
        try (var in = SeededHistory.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + MANIFEST_RESOURCE);
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
