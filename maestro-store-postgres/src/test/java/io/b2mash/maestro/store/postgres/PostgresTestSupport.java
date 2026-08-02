package io.b2mash.maestro.store.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.postgresql.ds.PGSimpleDataSource;

import java.sql.SQLException;

/**
 * Base class for Postgres integration tests.
 *
 * <p>Provides a shared Testcontainers {@link PostgreSQLContainer}, Flyway
 * migration on first use, and per-test table truncation for isolation.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension. That extension
 * stops a static container when its test <em>class</em> finishes, so as soon as
 * this base has more than one subclass the container is torn down and recreated
 * per subclass — and every suite after the first meets a fresh, unmigrated
 * database. Ryuk removes this container when the JVM exits. (The integration
 * module's {@code PostgresIntegrationSupport} carries the same note for the
 * same reason.)
 */
abstract class PostgresTestSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private static final Object MIGRATION_LOCK = new Object();
    private static boolean migrated = false;

    protected PGSimpleDataSource dataSource;
    protected PostgresWorkflowStore store;
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // Run migrations once per container lifecycle
        synchronized (MIGRATION_LOCK) {
            if (!migrated) {
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                migrated = true;
            }
        }

        objectMapper = JsonMapper.builder().build();
        store = new PostgresWorkflowStore(dataSource, objectMapper);

        truncateTables();
    }

    private void truncateTables() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE maestro_workflow_signal, maestro_workflow_timer, "
                    + "maestro_workflow_event, maestro_workflow_instance CASCADE");
        }
    }
}
