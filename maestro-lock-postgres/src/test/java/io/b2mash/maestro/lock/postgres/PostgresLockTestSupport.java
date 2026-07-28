package io.b2mash.maestro.lock.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Instant;

/**
 * Base class for {@code maestro-lock-postgres} integration tests.
 *
 * <p>Provides a Postgres container shared by every suite in the JVM, the
 * module's Flyway migration applied once, and per-test truncation of the lock
 * tables.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension: that extension
 * stops a static container when its test <em>class</em> finishes, so an
 * inherited container would be torn down and recreated per subclass, leaving
 * later suites on an unmigrated database. Ryuk removes the container at JVM exit.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread; the container and
 * the one-shot migration are guarded statically.
 */
abstract class PostgresLockTestSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_lock_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private static final Object MIGRATION_LOCK = new Object();
    private static boolean migrated = false;

    protected PGSimpleDataSource dataSource;
    protected PostgresDistributedLock lock;

    @BeforeEach
    void setUpLockBackend() throws SQLException {
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

        lock = new PostgresDistributedLock(dataSource);
        truncateLockTables();
    }

    /**
     * @return a new data source against the shared container — use this when a
     * test needs an independent connection source
     */
    protected static PGSimpleDataSource newDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /**
     * Removes all lock and leader-election rows.
     *
     * @throws SQLException if truncation fails
     */
    protected void truncateLockTables() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE maestro_distributed_lock, maestro_leader_election");
        }
    }

    /**
     * Forces a lock row to look expired, so TTL-expiry behaviour can be tested
     * without waiting in real time.
     *
     * @param key the lock key to expire
     * @throws SQLException if the update fails
     */
    protected void expireLock(String key) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE maestro_distributed_lock SET expires_at = now() - interval '1 second' "
                             + "WHERE lock_key = ?")) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        }
    }

    /**
     * Forces a leader-election row to look expired.
     *
     * @param electionKey the election key to expire
     * @throws SQLException if the update fails
     */
    protected void expireLeader(String electionKey) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE maestro_leader_election SET expires_at = now() - interval '1 second' "
                             + "WHERE election_key = ?")) {
            stmt.setString(1, electionKey);
            stmt.executeUpdate();
        }
    }

    /**
     * @return the database's current time, for diagnosing host/container clock
     * differences in failure messages
     */
    protected Instant databaseNowUnchecked() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT now()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (SQLException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * @param key the lock key
     * @return how many rows exist for the key
     * @throws SQLException if the query fails
     */
    protected int lockRowCount(String key) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM maestro_distributed_lock WHERE lock_key = ?")) {
            stmt.setString(1, key);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
