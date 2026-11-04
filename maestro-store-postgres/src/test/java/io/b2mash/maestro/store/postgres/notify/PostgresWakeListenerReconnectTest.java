package io.b2mash.maestro.store.postgres.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LISTEN session is long-lived, so it will be killed at some point — a
 * failover, an idle-session timeout, a {@code pg_terminate_backend} by an
 * operator. The listener must come back on its own and keep delivering.
 */
@DisplayName("PostgresWakeListener reconnects after its backend is terminated")
class PostgresWakeListenerReconnectTest extends PostgresNotifyTestSupport {

    @Test
    @DisplayName("after pg_terminate_backend the session is re-established and a later publish is delivered")
    void terminatedBackendIsReplacedAndWakesStillArrive() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(listener::isConnected);
        var pid = listenBackendPid();
        assertTrue(pid > 0, "the LISTEN session must be visible in pg_stat_activity");

        var workflowId = "wf-reconnect-" + unique();
        var woken = new ConcurrentLinkedQueue<String>();
        try (var notifier = newNotifier()) {
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            terminateBackend(pid);

            await().atMost(Duration.ofSeconds(10)).until(() -> listener.reconnects() >= 1);
            await().atMost(Duration.ofSeconds(10)).until(listener::isConnected);

            notifier.publish(workflowId, "approval");

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> !woken.isEmpty());
            assertEquals("approval", woken.peek());
            assertTrue(listener.reconnects() >= 1);
            assertTrue(listener.dispatched() >= 1);
        }
    }

    @Test
    @DisplayName("a listener whose first connection is refused still starts, then connects once the pool recovers")
    void startSurvivesInitialConnectionFailure() {
        var flaky = new FaultInjectingDataSource(newDataSource());
        flaky.arm();
        try (var late = new PostgresWakeListener(flaky, WakeChannel.channelFor("late-" + unique()))) {
            late.start();   // must not throw
            assertFalse(late.isConnected());
            assertTrue(flaky.failedConnections() >= 1, "start() must have tried");

            flaky.disarm();
            await().atMost(Duration.ofSeconds(10)).until(late::isConnected);

            var woken = new ConcurrentLinkedQueue<String>();
            try (var notifier = new PostgresSignalNotifier(newDataSource(), late, late.channel())) {
                notifier.subscribe("wf-late", (wfId, signalName) -> woken.add(signalName));
                notifier.publish("wf-late", "approval");
                await().atMost(Duration.ofSeconds(10)).until(() -> !woken.isEmpty());
            }
        }
    }

    private int listenBackendPid() throws SQLException {
        try (var conn = newDataSource().getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT pid FROM pg_stat_activity WHERE query = ? AND pid <> pg_backend_pid()")) {
            stmt.setString(1, "LISTEN " + channel);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private void terminateBackend(int pid) throws SQLException {
        try (var conn = newDataSource().getConnection();
             var stmt = conn.prepareStatement("SELECT pg_terminate_backend(?)")) {
            stmt.setInt(1, pid);
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1), "pg_terminate_backend must report success");
            }
        }
    }
}
