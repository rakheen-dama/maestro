package io.b2mash.maestro.store.postgres.notify;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleaner;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleanerScheduler;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 4: under an executor the lock cleaner and the wake
 * listener start through {@code WorkflowExecutor.supervise}, so each is a
 * loop member of the executor's lifecycle registry. {@code shutdown()} asks
 * them to stop with every other member and returns only once both have ended
 * — the listener's {@code UNLISTEN} done and its connection back — and a
 * start after shutdown is refused before any thread starts.
 *
 * <p>No database: the data sources are stubs.
 */
@Timeout(60)
@DisplayName("The Postgres loops are lifecycle-registry members under an executor")
class PostgresLoopsRegistryTest {

    @Test
    @DisplayName("shutdown waits for both Postgres loops, and a start after it is refused")
    void shutdownWaitsForBothLoopsAndAStartAfterItIsRefused() {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                        new PayloadSerializer(new ObjectMapper()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .wakeRecheckInterval(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ofSeconds(10))
                .build();
        var scripted = new ScriptedPgDataSource();
        var listener = scripted.newListener();
        var cleaner = new PostgresLockCleanerScheduler(new PostgresLockCleaner(refusing()), Duration.ofMillis(20));
        try {
            listener.start(executor);
            cleaner.start(executor);
            await("connected").atMost(Duration.ofSeconds(5)).until(listener::isConnected);

            executor.shutdown();

            assertTrue(executor.loops().stream()
                    .filter(l -> Set.of(PostgresWakeListener.LOOP_NAME, PostgresLockCleanerScheduler.LOOP_NAME)
                            .contains(l.name()))
                    .allMatch(l -> l.state().ended()), "both loops ended when shutdown returned: " + executor.loops());
            assertTrue(!listener.isConnected(), "the listener's session was given back before shutdown returned");
            var late = new PostgresLockCleanerScheduler(new PostgresLockCleaner(refusing()), Duration.ofMillis(20));
            var refusal = assertThrows(IllegalStateException.class, () -> late.start(executor));
            assertTrue(refusal.getMessage().contains("shutting down"), refusal.getMessage());
        } finally {
            listener.close();
            cleaner.close();
            executor.shutdown();
        }
    }

    /**
     * Review L3: under an executor the wake listener's {@code close()} does not
     * wait again for a poll still stuck past shutdown's deadline — the
     * executor's shutdown already waited for it on its one deadline (and named
     * it in a WARN); a second, private bound would be a shutdown step with a
     * timeout of its own. The poll keeps its connection until it returns.
     */
    @Test
    @DisplayName("under an executor, close() does not wait a second time for a poll stuck past the deadline")
    void underAnExecutorCloseDoesNotWaitAgainForAStuckPoll() {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                        new PayloadSerializer(new ObjectMapper()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .wakeRecheckInterval(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ofMillis(300))
                .build();
        var scripted = new ScriptedPgDataSource();
        var listener = new PostgresWakeListener(scripted, ScriptedPgDataSource.CHANNEL, Duration.ofSeconds(5));
        try {
            listener.start(executor);
            await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
            scripted.blockPolls();
            await().atMost(Duration.ofSeconds(5)).until(scripted::inBlockedPoll);
            executor.shutdown(); // hits its 300 ms deadline with the poll stuck

            var started = System.nanoTime();
            listener.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0,
                    "close() returned without its own 5 s wait");
            assertTrue(scripted.events().stream().noneMatch(e -> e.startsWith("UNLISTEN") || e.equals("close")),
                    "the stuck poll keeps its connection");
        } finally {
            scripted.releasePolls();
            listener.close();
            executor.shutdown();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.events().contains("close"));
    }

    /** A data source whose {@code getConnection} fails: the cleaner's runs fail, its loop keeps running. */
    private static DataSource refusing() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("connection refused (test)");
                    }
                    if (method.getName().equals("toString")) {
                        return "refusing data source";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
