package io.maestro.store.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.postgresql.ds.PGSimpleDataSource;

import java.sql.SQLException;

/**
 * Base class for Postgres integration tests.
 *
 * <p>Provides a shared Testcontainers {@link PostgreSQLContainer}, Flyway
 * migration on first use, and per-test table truncation for isolation.
 */
@Testcontainers
abstract class PostgresTestSupport {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_test")
                    .withUsername("test")
                    .withPassword("test");

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
