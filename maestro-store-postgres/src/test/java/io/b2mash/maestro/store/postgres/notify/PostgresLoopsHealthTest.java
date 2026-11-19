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

    /**
     * P2b review m3: under an executor the listener reads failing after two
     * failures in a row spanning at least the executor's wake recheck
     * interval, and backs off from 1 s doubling to 30 s.
     */
    @Test
    @DisplayName("the listener's failing rule is the executor's wake recheck interval")
    void theListenersFailingRuleIsTheExecutorsWakeRecheckInterval() {
        var executor45 = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), "svc45")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .wakeRecheckInterval(Duration.ofSeconds(45))
                .build();
        var listener = new ScriptedPgDataSource().newListener();
        try {
            listener.start(executor45);
            var spec = listener.startedSpec();
            assertEquals(io.b2mash.maestro.core.loop.FailingRule.streakFor(2, Duration.ofSeconds(45)), spec.failing());
            assertEquals(io.b2mash.maestro.core.loop.LoopRole.OPTIMISATION, spec.role());
            assertEquals(Duration.ofSeconds(1), spec.backoff().after(1));
            assertEquals(Duration.ofSeconds(30), spec.backoff().after(10));
        } finally {
            listener.close();
            executor45.shutdown();
        }
    }

    /**
     * PR #76 review 4629668602: an executor stop that lands while the
     * listener is blocked borrowing a connection (an exhausted pool) is
     * honoured when the borrow returns — no {@code LISTEN}, no poll, the
     * connection handed straight back. The executor's stop does not go
     * through the listener's own {@code close()}.
     */
    @Test
    @DisplayName("an executor stop during a blocked borrow is honoured when the borrow returns")
    void aStopDuringABlockedBorrowIsHonouredWhenTheBorrowReturns() {
        var scripted = new ScriptedPgDataSource();
        scripted.refuseConnections(true);                     // start()'s connect fails: the loop reconnects
        var stopping = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), "svc-stop")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofMillis(300))
                .build();
        var listener = scripted.newListener();
        try {
            listener.start(stopping);                         // refused: the loop retries 1 s later
            scripted.blockConnections();                      // ... and that borrow blocks
            scripted.refuseConnections(false);
            await("the reconnect is blocked in the pool").atMost(Duration.ofSeconds(5))
                    .until(scripted::inBlockedConnect);

            stopping.shutdown();                              // the executor stops the loop, not close()
            scripted.releaseConnections();

            await("the loop ended").atMost(Duration.ofSeconds(5)).until(() -> stopping.loops().stream()
                    .filter(l -> l.name().equals(PostgresWakeListener.LOOP_NAME)).allMatch(l -> l.state().ended()));
            var events = scripted.events();
            assertFalse(events.contains("LISTEN " + ScriptedPgDataSource.CHANNEL), "no session after the stop: "
                    + events);
            assertFalse(events.contains("poll"), "no poll after the stop: " + events);
            assertEquals("close", events.getLast(), "the borrowed connection was handed back: " + events);
            assertFalse(listener.isConnected());
        } finally {
            scripted.releaseConnections();
            listener.close();
        }
    }

    /**
     * PR #76 review 4629668610: the loop's last snapshot says it is not
     * connected — {@code onExit} released the session, so a terminal snapshot
     * still reading {@code connected=true} would be a lie.
     */
    @Test
    @DisplayName("once the listener has stopped, its snapshot reads not connected")
    void aStoppedListenerReadsNotConnected() {
        var scripted = new ScriptedPgDataSource();
        var listener = scripted.newListener();
        listener.start(executor);
        await("connected").atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(wakeNotes().get("connected")));

        listener.close();

        assertEquals(false, wakeNotes().get("connected"), "the terminal snapshot: " + wakeNotes());
    }

    private java.util.Map<String, Object> wakeNotes() {
        return executor.loops().stream().filter(l -> l.name().equals(PostgresWakeListener.LOOP_NAME))
                .findFirst().orElseThrow().notes();
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
