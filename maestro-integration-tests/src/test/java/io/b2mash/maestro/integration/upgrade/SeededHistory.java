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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
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
 * <h2>Thread Safety</h2>
 * <p>{@link #load()} is thread-safe. An instance is meant for one test thread;
 * the store, data source and serializer it hands out are thread-safe.
 */
public final class SeededHistory {

    /** Classpath location of the dump. */
    static final String DUMP_RESOURCE = "upgrade/cbf5a2a/seeded-history.sql";
    /** Classpath location of the capture manifest. */
    static final String MANIFEST_RESOURCE = "upgrade/cbf5a2a/capture.properties";

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
     * Polls every 100 ms until the workflow's status equals {@code expected}
     * — and, for {@code COMPLETED}/{@code FAILED}, its terminal event is in
     * the log ({@link TerminalWait#isFinalised}) — or {@code within} elapses.
     * Returns the last status seen rather than throwing, so an assertion on it
     * reads "expected COMPLETED but was WAITING_SIGNAL" instead of a timeout.
     *
     * @param workflowId the workflow id
     * @param expected   the awaited status
     * @param within     the bound
     * @return {@code expected} once reached, else the last status seen
     * @throws IllegalArgumentException if no instance has that id
     */
    public WorkflowStatus awaitStatusOrLast(String workflowId, WorkflowStatus expected, Duration within) {
        var deadline = Instant.now().plus(within);
        while (true) {
            var instance = store.getInstance(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("No seeded instance '" + workflowId + "'"));
            var status = instance.status();
            var reached = status == expected
                    && (!expected.isTerminal() || TerminalWait.isFinalised(store, instance));
            if (reached || !Instant.now().isBefore(deadline)) {
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
