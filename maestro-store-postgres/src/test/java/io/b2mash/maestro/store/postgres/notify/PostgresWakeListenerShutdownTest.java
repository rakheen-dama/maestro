package io.b2mash.maestro.store.postgres.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue 38 on real Postgres and a real Hikari pool: closing the wake listener
 * must hand its connection back to the pool intact — no {@code WARN} from
 * {@code com.zaxxer.hikari}, the same physical backend reused, nothing left
 * {@code LISTEN}ing on it and its network timeout as the pool lent it.
 *
 * <p>The listener the support class starts on a {@code PGSimpleDataSource} is
 * unused here; the support class closes it.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test fixtures, confined to the test thread; the log appender is read
 * under its own monitor, which logback's {@code doAppend} also holds.
 */
@DisplayName("PostgresWakeListener closes cleanly on a Hikari pool (Issue 38)")
class PostgresWakeListenerShutdownTest extends PostgresNotifyTestSupport {

    private HikariDataSource pool;
    private ListAppender<ILoggingEvent> hikariLog;
    private Logger hikariLogger;

    @BeforeEach
    void setUpPool() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setPoolName("wake-shutdown-test");
        pool = new HikariDataSource(config);

        hikariLogger = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari");
        hikariLog = new ListAppender<>();
        hikariLog.start();
        hikariLogger.addAppender(hikariLog);
    }

    @AfterEach
    void tearDownPool() {
        hikariLogger.detachAppender(hikariLog);
        hikariLog.stop();
        pool.close();
    }

    @Test
    @DisplayName("closing a connected listener on a Hikari pool logs no WARN from the pool (Issue 38)")
    void closingTheListenerLogsNoWarnFromThePool() {
        var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("shutdown-" + unique()));
        listener.start();
        await().atMost(Duration.ofSeconds(10)).until(listener::isConnected);
        await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
        listener.close();
        assertEquals(List.of(), poolWarnings());
    }

    @Test
    @DisplayName("the listener's connection goes back to the pool and is reused, not evicted")
    void theListenersConnectionGoesBackToThePoolAndIsReused() throws Exception {
        var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("reuse-" + unique()));
        listener.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
        var pid = listenBackendPid(listener.channel());
        listener.close();
        assertEquals(pid, borrowedBackendPid(), "the pool (size 1) must hand out the same backend");
    }

    @Test
    @DisplayName("the connection returned to the pool listens on nothing")
    void theConnectionReturnedToThePoolListensOnNothing() throws Exception {
        var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("clean-" + unique()));
        listener.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
        listener.close();
        assertEquals(0, borrowedListeningChannels());
    }

    @Test
    @DisplayName("the connection returned to the pool keeps the network timeout it was lent with")
    void theConnectionReturnedToThePoolKeepsItsNetworkTimeout() throws Exception {
        var lentWith = borrowedNetworkTimeout();
        var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("timeout-" + unique()));
        listener.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
        listener.close();
        assertEquals(lentWith, borrowedNetworkTimeout());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /** @return the formatted messages the pool logged at {@code WARN} or above */
    private List<String> poolWarnings() {
        synchronized (hikariLog) {
            return hikariLog.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    /** @return the pid of the backend whose last query is {@code LISTEN <channel>}, or -1 */
    private static int listenBackendPid(String channel) throws SQLException {
        try (var conn = newDataSource().getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT pid FROM pg_stat_activity WHERE query = ? AND pid <> pg_backend_pid()")) {
            stmt.setString(1, "LISTEN " + channel);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** @return the backend pid of the connection the pool hands out next */
    private int borrowedBackendPid() throws SQLException {
        try (var conn = pool.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** @return the network timeout (ms) of the connection the pool hands out next */
    private int borrowedNetworkTimeout() throws SQLException {
        try (var conn = pool.getConnection()) {
            return conn.getNetworkTimeout();
        }
    }

    /** @return how many channels the connection the pool hands out next is listening on */
    private int borrowedListeningChannels() throws SQLException {
        try (var conn = pool.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM pg_listening_channels()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
