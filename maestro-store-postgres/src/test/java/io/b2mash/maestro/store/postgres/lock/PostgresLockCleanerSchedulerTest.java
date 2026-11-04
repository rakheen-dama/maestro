package io.b2mash.maestro.store.postgres.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cleaner used to be a bean nothing called. With Postgres the only lock
 * backend, every crashed run leaves a row behind, so the scheduler must
 * actually run it.
 */
@DisplayName("PostgresLockCleanerScheduler")
class PostgresLockCleanerSchedulerTest extends PostgresLockTestSupport {

    @Test
    @DisplayName("an expired lock row is deleted within the configured interval")
    void expiredRowIsDeletedOnSchedule() throws SQLException {
        var key = "scheduler-test-" + System.nanoTime();
        insertExpiredLock(key);
        assertEquals(1, lockRowCount(key));

        try (var scheduler = new PostgresLockCleanerScheduler(
                new PostgresLockCleaner(dataSource), Duration.ofMillis(200))) {
            scheduler.start();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> lockRowCount(key) == 0);
        }
    }

    @Test
    @DisplayName("a live lock row is left alone")
    void liveRowIsKept() throws SQLException {
        var handle = lock.tryAcquire("scheduler-live-" + System.nanoTime(), Duration.ofMinutes(5)).orElseThrow();
        try (var scheduler = new PostgresLockCleanerScheduler(
                new PostgresLockCleaner(dataSource), Duration.ofMillis(100))) {
            scheduler.start();
            await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
                    .until(() -> lockRowCount(handle.key()) == 1);
        } finally {
            lock.release(handle);
        }
    }

    @Test
    @DisplayName("a non-positive interval is rejected")
    void nonPositiveIntervalRejected() {
        var cleaner = new PostgresLockCleaner(dataSource);
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresLockCleanerScheduler(cleaner, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresLockCleanerScheduler(cleaner, Duration.ofSeconds(-1)));
    }

    /** A row whose expiry is well past the cleaner's one-minute grace period. */
    private void insertExpiredLock(String key) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO maestro_distributed_lock (lock_key, token, expires_at) "
                             + "VALUES (?, 'dead-token', now() - interval '10 minutes')")) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        }
    }
}
