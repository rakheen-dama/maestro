package io.b2mash.maestro.messaging.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;

/**
 * Base class for {@code maestro-messaging-postgres} integration tests.
 *
 * <p>Provides a Postgres container shared by every suite in the JVM, the
 * module's Flyway migration applied once, per-test truncation of the queue
 * tables, and a started {@link PostgresNotificationListener}.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension: that extension
 * stops a static container when its test <em>class</em> finishes, so an
 * inherited container would be recreated per subclass and later suites would
 * run against an unmigrated database. Ryuk removes it at JVM exit.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread; the container and
 * the one-shot migration are guarded statically.
 */
abstract class PostgresMessagingTestSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_messaging_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private static final Object MIGRATION_LOCK = new Object();
    private static boolean migrated = false;

    protected PGSimpleDataSource dataSource;
    protected ObjectMapper objectMapper;
    protected PostgresNotificationListener notificationListener;
    protected PostgresWorkflowMessaging messaging;

    @BeforeEach
    void setUpMessaging() throws SQLException {
        dataSource = newDataSource();

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

        truncateQueues();

        objectMapper = JsonMapper.builder().build();
        notificationListener = new PostgresNotificationListener(dataSource);
        notificationListener.start();
        messaging = new PostgresWorkflowMessaging(dataSource, objectMapper, notificationListener);
    }

    @AfterEach
    void tearDownMessaging() {
        if (messaging != null) {
            messaging.close();
        }
        if (notificationListener != null) {
            notificationListener.close();
        }
    }

    /** @return a new data source against the shared container */
    protected static PGSimpleDataSource newDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /**
     * Empties every queue table.
     *
     * @throws SQLException if truncation fails
     */
    protected void truncateQueues() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE maestro_task_queue, maestro_signal_queue, "
                    + "maestro_lifecycle_event_queue");
        }
    }

    /**
     * Reads a queue row's status.
     *
     * @param table the queue table
     * @param workflowId the workflow the row belongs to
     * @return the status string, or {@code null} if there is no such row
     * @throws SQLException if the query fails
     */
    protected String statusOf(String table, String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT status FROM " + table + " WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Reads a single text column for a workflow's row.
     *
     * @param table      the queue table
     * @param column     the column to read
     * @param workflowId the workflow ID
     * @return the column value, or {@code null} if there is no such row
     * @throws SQLException if the query fails
     */
    protected String columnOf(String table, String column, String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Counts rows for a workflow in a queue table.
     *
     * @param table the queue table
     * @param workflowId the workflow ID
     * @return the row count
     * @throws SQLException if the query fails
     */
    protected int rowCount(String table, String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Ages a row's {@code processed_at} so it looks like a stale PROCESSING
     * claim, which the poller should reclaim.
     *
     * @param table      the queue table
     * @param workflowId the workflow ID
     * @throws SQLException if the update fails
     */
    protected void ageProcessingClaim(String table, String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE " + table + " SET status = 'PROCESSING', "
                             + "processed_at = now() - interval '10 minutes' WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            stmt.executeUpdate();
        }
    }
}
