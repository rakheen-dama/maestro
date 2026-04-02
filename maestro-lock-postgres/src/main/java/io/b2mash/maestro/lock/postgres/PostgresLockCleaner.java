package io.b2mash.maestro.lock.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Cleans up expired lock and leader election rows from PostgreSQL.
 *
 * <p>Expired rows are harmless — the {@code ON CONFLICT DO UPDATE WHERE expires_at < now()}
 * pattern in {@link PostgresDistributedLock} naturally reclaims them. However, periodic
 * cleanup prevents unbounded table growth when lock keys are transient (e.g., one key
 * per workflow instance).
 *
 * <p>Cleanup uses a 1-minute grace period beyond expiry to avoid racing with
 * in-flight renewal attempts.
 *
 * <h2>Usage</h2>
 * <p>Call {@link #clean()} on a schedule (e.g., every 5 minutes via
 * {@code @Scheduled}). The auto-configuration does not schedule this
 * automatically — the application controls the cadence.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each invocation obtains its own JDBC connection.
 */
public final class PostgresLockCleaner {

    private static final Logger logger = LoggerFactory.getLogger(PostgresLockCleaner.class);

    // language=SQL
    private static final String CLEAN_LOCKS_SQL = """
            DELETE FROM maestro_distributed_lock
            WHERE expires_at < now() - interval '1 minute'
            """;

    // language=SQL
    private static final String CLEAN_LEADERS_SQL = """
            DELETE FROM maestro_leader_election
            WHERE expires_at < now() - interval '1 minute'
            """;

    private final DataSource dataSource;

    /**
     * Creates a new lock cleaner.
     *
     * @param dataSource the JDBC DataSource for obtaining connections
     */
    public PostgresLockCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Deletes expired lock and leader election rows with a 1-minute grace period.
     *
     * <p>Returns the total number of rows deleted across both tables.
     *
     * @return total rows deleted
     */
    public int clean() {
        int total = 0;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            try (var stmt = conn.prepareStatement(CLEAN_LOCKS_SQL)) {
                int deleted = stmt.executeUpdate();
                total += deleted;
                if (deleted > 0) {
                    logger.debug("Cleaned {} expired lock(s)", deleted);
                }
            }

            try (var stmt = conn.prepareStatement(CLEAN_LEADERS_SQL)) {
                int deleted = stmt.executeUpdate();
                total += deleted;
                if (deleted > 0) {
                    logger.debug("Cleaned {} expired leader election(s)", deleted);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to clean expired locks", e);
        }

        return total;
    }
}
