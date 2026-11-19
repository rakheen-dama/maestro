package io.b2mash.maestro.store.postgres.notify;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.spring.health.MaestroHealthIndicator;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleaner;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleanerScheduler;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two {@code maestro-store-postgres} loops in the {@code maestro} health
 * contributor (P2 task 6, Issue 65): supervised by the executor, a dead lock
 * cleaner reads {@code DOWN} like any dead loop, and a wake listener that
 * cannot connect for longer than the wake recheck interval reads {@code UP}
 * with {@code wake-listener} in {@code degraded} — the recheck is the
 * correctness fallback, so a lost {@code LISTEN} session costs latency only.
 *
 * <p>No database: the data sources are stubs ({@link ScriptedPgDataSource}).
 * It sits in the listener's package for that fixture.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test fixtures, confined to the test thread.
 */
@DisplayName("The Postgres loops in health")
class PostgresLoopsHealthTest {

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(
                    store, new PayloadSerializer(new ObjectMapper()), "svc")
            .distributedLock(new InMemoryDistributedLock())
            .lifecycleEventsEnabled(false)
            .wakeRecheckInterval(Duration.ofMillis(500))
            .build();
    private final MaestroHealthIndicator health = new MaestroHealthIndicator(store, executor, false, false);

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("a lock cleaner that a control-flow Error ended reads DOWN")
    void aDeadCleanerReadsDown() {
        DataSource ending = failing(() -> new ExecutorShutdownException("test"));
        var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(ending), Duration.ofMillis(20));
        try {
            scheduler.start(executor);
            await("the cleaner to end").atMost(Duration.ofSeconds(5))
                    .until(() -> health.health().getStatus().equals(Status.DOWN));
            var details = health.health().getDetails();
            assertEquals(false, details.get("lockCleanerRunning"), "details: " + details);
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("a wake listener disconnected for longer than the recheck interval is UP and degraded, not DOWN")
    void aDisconnectedWakeListenerIsDegradedNotDown() {
        DataSource refusing = failing(() -> new SQLException("connection refused"));
        var listener = new PostgresWakeListener(refusing, "maestro_signal_wake_svc_00000000");
        try {
            listener.start(executor);
            await("the listener to read failing").atMost(Duration.ofSeconds(10))
                    .until(() -> health.health().getDetails().get("degraded") instanceof List<?> d
                            && d.contains("wake-listener"));
            var reading = health.health();
            assertEquals(Status.UP, reading.getStatus(), "details: " + reading.getDetails());
            assertEquals(true, reading.getDetails().get("wakeListenerRunning"));
            assertEquals(false, reading.getDetails().get("wakeListenerConnected"));
            assertFalse(listener.isConnected());
        } finally {
            listener.close();
        }
    }

    @Test
    @DisplayName("a connected wake listener reads connected")
    void aConnectedWakeListenerReadsConnected() {
        var scripted = new ScriptedPgDataSource();
        var listener = scripted.newListener();
        try {
            listener.start(executor);
            await("connected").atMost(Duration.ofSeconds(5))
                    .until(() -> Boolean.TRUE.equals(health.health().getDetails().get("wakeListenerConnected")));
            assertTrue(listener.isConnected());
        } finally {
            listener.close();
        }
    }

    /** A data source whose {@code getConnection} throws {@code failure.get()}, sneakily if checked. */
    private static DataSource failing(java.util.function.Supplier<Throwable> failure) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw failure.get();
                    }
                    if (method.getName().equals("toString")) {
                        return "failing data source";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
