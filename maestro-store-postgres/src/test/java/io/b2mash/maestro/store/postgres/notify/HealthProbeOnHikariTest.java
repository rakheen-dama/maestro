package io.b2mash.maestro.store.postgres.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.health.MaestroHealthIndicator;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue 66 on real Postgres and a real Hikari pool: a health probe that times
 * out mid-query is not interrupted, so its connection finishes the query and
 * goes back to the pool intact — no {@code WARN} from the pool, no evicted
 * connection. (Before P1 task 6 the timed-out probe was interrupted; on a
 * virtual thread inside pgjdbc's socket read that closes the connection, and
 * Hikari evicts it.)
 *
 * <p>Lives beside the wake-listener pool tests for their shared container.
 */
@DisplayName("A timed-out health probe on a Hikari pool evicts no connection (Issue 66)")
class HealthProbeOnHikariTest extends PostgresNotifyTestSupport {

    /** Longer than the probe's 2 s bound, so the probe times out mid-query. */
    private static final int QUERY_SECONDS = 4;

    private HikariDataSource pool;
    private ListAppender<ILoggingEvent> hikariLog;
    private Logger hikariLogger;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUpPool() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setPoolName("health-probe-test");
        pool = new HikariDataSource(config);

        hikariLogger = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari");
        hikariLog = new ListAppender<>();
        hikariLog.start();
        hikariLogger.addAppender(hikariLog);

        executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                        new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        hikariLogger.detachAppender(hikariLog);
        hikariLog.stop();
        pool.close();
    }

    @Test
    @DisplayName("a probe that times out mid-query logs no pool WARN and its connection is reused, not evicted")
    void aTimedOutProbeEvictsNoConnection() throws Exception {
        var probePid = new AtomicInteger(-1);
        var finished = new AtomicInteger();
        var indicator = new MaestroHealthIndicator(slowStore(probePid, finished), executor, false, false);

        var health = indicator.health();
        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("timed out", health.getDetails().get("store"));

        await().atMost(Duration.ofSeconds(QUERY_SECONDS + 10)).until(() -> finished.get() == 1);
        assertEquals(List.of(), poolWarnings());
        assertEquals(probePid.get(), borrowedBackendPid(),
                "the pool (size 1) hands out the probe's backend again: the connection was not evicted");
    }

    /** A store whose probe read runs {@code pg_sleep} on a pooled connection. */
    private WorkflowStore slowStore(AtomicInteger probePid, AtomicInteger finished) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInstance")) {
                        try (var conn = pool.getConnection(); var stmt = conn.createStatement()) {
                            try (var rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                                rs.next();
                                probePid.set(rs.getInt(1));
                            }
                            stmt.execute("SELECT pg_sleep(" + QUERY_SECONDS + ")");
                            return Optional.empty();
                        } finally {
                            finished.incrementAndGet();
                        }
                    }
                    if (method.getName().equals("toString")) {
                        return "slow store";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** @return the formatted messages the pool logged at {@code WARN} or above */
    private List<String> poolWarnings() {
        synchronized (hikariLog) {
            return hikariLog.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
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
}
