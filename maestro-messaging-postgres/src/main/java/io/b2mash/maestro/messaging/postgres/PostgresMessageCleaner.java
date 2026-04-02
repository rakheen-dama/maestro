package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Utility for cleaning up processed and stale messages from the
 * PostgreSQL messaging tables.
 *
 * <p>Deletes rows in {@code COMPLETED} or {@code FAILED} status that are
 * older than a configurable retention period. This prevents unbounded
 * table growth while preserving recent messages for debugging.
 *
 * <h2>Usage</h2>
 * <p>Typically scheduled as a periodic task (e.g., via Spring's
 * {@code @Scheduled}) by the application or invoked from the admin dashboard.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each cleanup call uses its own connection
 * from the pool.
 *
 * @see PostgresWorkflowMessaging
 */
@NullMarked
public final class PostgresMessageCleaner {

    private static final Logger logger = LoggerFactory.getLogger(PostgresMessageCleaner.class);

    private static final String DELETE_TASK_QUEUE = """
            DELETE FROM maestro_task_queue
            WHERE status IN ('COMPLETED', 'FAILED')
              AND processed_at < now() - INTERVAL '%s seconds'
            """;

    private static final String DELETE_SIGNAL_QUEUE = """
            DELETE FROM maestro_signal_queue
            WHERE status IN ('COMPLETED', 'FAILED')
              AND processed_at < now() - INTERVAL '%s seconds'
            """;

    private static final String DELETE_LIFECYCLE_QUEUE = """
            DELETE FROM maestro_lifecycle_event_queue
            WHERE created_at < now() - INTERVAL '%s seconds'
            """;

    private final DataSource dataSource;
    private final Duration retention;

    /**
     * Creates a message cleaner with the specified retention period.
     *
     * @param dataSource the DataSource for database operations
     * @param retention  how long to retain completed/failed messages
     */
    public PostgresMessageCleaner(DataSource dataSource, Duration retention) {
        this.dataSource = dataSource;
        this.retention = retention;
    }

    /**
     * Deletes processed messages older than the retention period from all
     * messaging tables.
     *
     * @return the total number of rows deleted across all tables
     */
    public int cleanAll() {
        var retentionSeconds = retention.toSeconds();
        var total = 0;
        total += cleanTable("maestro_task_queue",
                String.format(DELETE_TASK_QUEUE, retentionSeconds));
        total += cleanTable("maestro_signal_queue",
                String.format(DELETE_SIGNAL_QUEUE, retentionSeconds));
        total += cleanTable("maestro_lifecycle_event_queue",
                String.format(DELETE_LIFECYCLE_QUEUE, retentionSeconds));
        if (total > 0) {
            logger.info("Cleaned {} stale messages (retention={}s)", total, retentionSeconds);
        }
        return total;
    }

    private int cleanTable(String tableName, String sql) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var deleted = stmt.executeUpdate(sql);
            if (deleted > 0) {
                logger.debug("Deleted {} rows from {}", deleted, tableName);
            }
            return deleted;
        } catch (SQLException e) {
            logger.warn("Failed to clean table {}: {}", tableName, e.getMessage(), e);
            return 0;
        }
    }
}
