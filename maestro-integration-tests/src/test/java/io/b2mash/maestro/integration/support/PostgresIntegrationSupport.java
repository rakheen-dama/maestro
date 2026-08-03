package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Base class for integration suites that run the real engine against a real
 * PostgreSQL backend.
 *
 * <p>One Postgres container is shared by every integration suite in the JVM,
 * Flyway migrated once, with per-test truncation for isolation. The container
 * is never restarted — truncation is what isolates.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension. That extension
 * stops a static container when its test <em>class</em> finishes, so an
 * inherited container is torn down and recreated for every subclass; the second
 * suite onwards would then run against a fresh, unmigrated database. Ryuk
 * removes this container when the JVM exits.
 *
 * <p>Migrations are applied from {@code classpath:db/migration}, which spans
 * every Maestro module on the test classpath: the store (version band 1–99),
 * the lock backend (100–199) and the messaging backend (200–299). That
 * composition is itself pinned by
 * {@code io.b2mash.maestro.integration.schema.MaestroMigrationsCoexistIT}.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread. The container is
 * started once by class initialisation and the one-shot migration is guarded by
 * a monitor, so suites may share both safely.
 */
@Tag("integration")
public abstract class PostgresIntegrationSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_it")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
        migrate();
    }

    /** Tables truncated before every test, child-first so CASCADE has nothing to chase. */
    private static final String TRUNCATE_SQL = """
            TRUNCATE maestro_workflow_signal, maestro_workflow_timer,
                     maestro_workflow_event, maestro_workflow_instance,
                     maestro_distributed_lock, maestro_leader_election,
                     maestro_task_queue, maestro_signal_queue,
                     maestro_lifecycle_event_queue
            CASCADE""";

    protected PGSimpleDataSource dataSource;
    protected PostgresWorkflowStore store;
    protected ObjectMapper objectMapper;
    protected PayloadSerializer serializer;

    /**
     * Applies every module's migrations once, at class-initialisation time.
     *
     * <p>Deliberately not done in {@code @BeforeEach}: Spring refreshes an
     * {@code @SpringBootTest} context — and {@code StartupRecoveryRunner}
     * queries {@code maestro_workflow_instance} — before any {@code @BeforeEach}
     * method runs, so a Spring-based suite would meet an unmigrated database.
     */
    private static void migrate() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUpPostgres() throws SQLException {
        dataSource = newDataSource();

        objectMapper = JsonMapper.builder().build();
        serializer = new PayloadSerializer(objectMapper);
        store = new PostgresWorkflowStore(dataSource, objectMapper);

        truncateAll();
    }

    /**
     * Creates an independent {@link javax.sql.DataSource} against the shared
     * container — use this when a test needs a second connection source (for
     * example to simulate a second node).
     *
     * @return a new data source pointed at the test container
     */
    protected static PGSimpleDataSource newDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /**
     * Creates a Postgres-backed distributed lock over the shared container.
     *
     * @return a new lock instance
     */
    protected PostgresDistributedLock newLock() {
        return new PostgresDistributedLock(dataSource);
    }

    /**
     * Waits until a workflow is finished in the durable log — terminal status
     * <em>and</em> its terminal event appended.
     *
     * <p>Use this, never a bare {@code status().isTerminal()} poll, whenever the
     * assertions that follow read the event log. See {@link TerminalWait} for
     * why the two are not the same thing.
     *
     * @param workflowId the business workflow ID
     * @param timeout    the bound
     * @return the finalised instance
     */
    protected WorkflowInstance awaitTerminal(String workflowId, Duration timeout) {
        return TerminalWait.awaitTerminal(store, workflowId, timeout);
    }

    /**
     * Waits until a workflow reaches a specific status; when that status is
     * terminal, also waits for the terminal event.
     *
     * @param workflowId the business workflow ID
     * @param expected   the awaited status
     * @param timeout    the bound
     * @return the instance in that status
     */
    protected WorkflowInstance awaitStatus(String workflowId, WorkflowStatus expected,
                                           Duration timeout) {
        return TerminalWait.awaitStatus(store, workflowId, expected, timeout);
    }

    /**
     * Removes all Maestro rows, leaving the schema intact.
     *
     * @throws SQLException if truncation fails
     */
    protected void truncateAll() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(TRUNCATE_SQL);
        }
    }
}
