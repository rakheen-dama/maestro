package io.b2mash.maestro.lock.postgres;

import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PostgresDistributedLock} backend-failure behaviour.
 * Happy-path SQL behaviour is covered by the Testcontainers suites of the
 * consuming modules; here we verify the exception contract when the JDBC
 * backend is unavailable.
 */
class PostgresDistributedLockTest {

    private final DataSource failingDataSource = new ThrowingDataSource();
    private final PostgresDistributedLock lock = new PostgresDistributedLock(failingDataSource);

    @Test
    @DisplayName("tryAcquire wraps backend failures in LockBackendException with the SQLException cause")
    void tryAcquireWrapsBackendFailure() {
        var ex = assertThrows(LockBackendException.class,
                () -> lock.tryAcquire("maestro:lock:workflow:order-1", Duration.ofSeconds(30)));

        assertEquals("maestro:lock:workflow:order-1", ex.key());
        assertInstanceOf(SQLException.class, ex.getCause(),
                "the original SQLException must be preserved as the cause");
    }

    @Test
    @DisplayName("renew wraps backend failures in LockBackendException with the SQLException cause")
    void renewWrapsBackendFailure() {
        var handle = new LockHandle("maestro:lock:workflow:order-1", "token-1",
                Instant.now().plusSeconds(30));

        var ex = assertThrows(LockBackendException.class,
                () -> lock.renew(handle, Duration.ofSeconds(30)));

        assertEquals("maestro:lock:workflow:order-1", ex.key());
        assertInstanceOf(SQLException.class, ex.getCause());
    }

    @Test
    @DisplayName("release tolerates backend failures (TTL cleans up)")
    void releaseToleratesBackendFailure() {
        var handle = new LockHandle("maestro:lock:workflow:order-1", "token-1",
                Instant.now().plusSeconds(30));

        lock.release(handle); // must not throw
        assertTrue(true);
    }

    @Test
    @DisplayName("trySetLeader tolerates backend failures and reports not-leader")
    void trySetLeaderToleratesBackendFailure() {
        assertFalse(lock.trySetLeader("maestro:leader:timer-poller:svc", "node-1",
                Duration.ofSeconds(15)));
    }

    /** DataSource whose every connection attempt fails with a SQLException. */
    private static final class ThrowingDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("simulated backend outage");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("simulated backend outage");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
