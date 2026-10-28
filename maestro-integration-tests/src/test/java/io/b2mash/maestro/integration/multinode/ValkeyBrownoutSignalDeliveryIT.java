package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import io.b2mash.maestro.lock.valkey.ValkeySignalNotifier;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins Issue 32: a Valkey brownout must not stall a signal sender, and must not
 * cost the parked workflow its wake.
 *
 * <p>The triaged soak failure: {@code SignalManager.deliverSignal} persisted the
 * signal and then published the cross-node wake synchronously; Lettuce's
 * {@code sync().publish} against a paused Valkey sat for its 60 s command
 * timeout, so every {@code signal()} caller stalled for the outage, and a
 * caller that gave up and retried persisted a byte-identical duplicate.
 *
 * <h2>Topology</h2>
 * <p>Two nodes over one real Postgres store and the Postgres lock (so the pause
 * touches nothing but the notifier). Each node has its own
 * {@link ValkeySignalNotifier} on its own connections to one real Valkey
 * container. The workflow parks on node A; node B delivers the signal while the
 * container is {@code docker pause}d — the TCP connections stay open and simply
 * stop answering, which is exactly a brownout.
 *
 * <h2>What wakes the workflow</h2>
 * <p>The workflow is asserted to complete <em>while Valkey is still paused</em>,
 * so the wake can only have come from the parked await's store re-read
 * ({@code wakeRecheckInterval}, shortened here to {@link #WAKE_RECHECK}) — the
 * notification is best-effort and the re-check covers a missed one. No timeout
 * anywhere is lengthened to make this pass.
 */
@Tag("integration")
@Testcontainers
@DisplayName("A Valkey brownout neither stalls signal() nor loses the parked workflow's wake (Issue 32)")
class ValkeyBrownoutSignalDeliveryIT extends PostgresIntegrationSupport {

    /** The sender's bound: "well inside 3 s" — the old code waited 60 s. */
    private static final Duration SEND_BOUND = Duration.ofSeconds(3);

    /** Short store re-read so the missed notification is covered quickly. */
    private static final Duration WAKE_RECHECK = Duration.ofMillis(300);

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> valkey =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();
    private final CountDownLatch parkedOnNodeA = new CountDownLatch(1);

    private final List<RedisClient> clients = new ArrayList<>();
    private final List<ValkeySignalNotifier> notifiers = new ArrayList<>();
    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;
    private boolean paused;

    @AfterEach
    void tearDown() {
        // Unpause first: closing Lettuce connections to a paused server can wait.
        try {
            unpause();
        } finally {
            try {
                if (nodeB != null) {
                    nodeB.close();
                }
            } finally {
                try {
                    if (nodeA != null) {
                        nodeA.close();
                    }
                } finally {
                    notifiers.forEach(ValkeySignalNotifier::close);
                    clients.forEach(RedisClient::shutdown);
                }
            }
        }
    }

    @Test
    @DisplayName("signal() on node B returns promptly during the pause, persists one row, and node A still wakes")
    void signalDuringValkeyPause_returnsPromptly_persistsOneRow_andTheParkedWorkflowWakes() throws Exception {
        nodeA = node("node-a", recorderA, new TestWorkflows.SignalWorkflow(parkedOnNodeA));
        nodeB = node("node-b", recorderB, new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("valkey-brownout");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parkedOnNodeA.await(15, TimeUnit.SECONDS));
        // WAITING_SIGNAL is written after the wake subscribe settled — the
        // subscription is in place before the brownout starts.
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(15));
        // A bystander subscriber on the workflow's wake channel: it sees node B's
        // late PUBLISH arrive, so the post-unpause assertions run after it.
        var latePublishSeen = probeWakeChannel(workflowId);

        pause();

        var started = System.nanoTime();
        assertTimeoutPreemptively(SEND_BOUND,
                () -> nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "APPROVED"),
                "signal() must not wait for the paused Valkey (it used to wait 60 s)");
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0,
                "signal() must return well inside " + SEND_BOUND + "; took " + elapsed);
        assertEquals(1, signalRowCount(workflowId), "exactly one maestro_workflow_signal row");

        // Still paused: only the wake re-check's store re-read can wake node A.
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(15)),
                "the parked workflow must wake from the store re-read while Valkey is down");
        assertEquals("seed-one:APPROVED-two", handle.result(String.class));

        // Back up: the publish Lettuce queued during the pause is now delivered
        // late, for a signal already consumed — harmless. Wait until it has
        // actually been delivered before asserting it changed nothing.
        assertEquals(1, latePublishSeen.getCount(), "nothing is delivered while Valkey is paused");
        unpause();
        assertTrue(latePublishSeen.await(10, TimeUnit.SECONDS),
                "node B's PUBLISH, queued during the pause, must be delivered once Valkey answers");
        assertEquals(1, handle.events().stream()
                        .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                        .count(),
                "the signal is consumed exactly once");
        assertEquals(1, signalRowCount(workflowId), "a late notification persists nothing");
        assertEquals(1, recorderA.count("stepTwo"));
        assertEquals(List.of(), recorderB.invocations(),
                "node B ingests the signal; it must not execute the workflow");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(String serviceName, CountingActivities.Recorder recorder,
                                      Object workflowImpl) {
        var client = newClient();
        var notifier = new ValkeySignalNotifier(client.connectPubSub(), client.connect());
        notifiers.add(notifier);

        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(newDataSource()))
                .signalNotifier(notifier)
                .instanceLockTtl(Duration.ofSeconds(10))
                .wakeRecheckInterval(WAKE_RECHECK)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(workflowImpl.getClass(), () -> workflowImpl);
        return harness;
    }

    /**
     * Subscribes a bystander pub/sub connection to the workflow's wake channel
     * (acknowledged before returning) and counts down on the first message.
     */
    private CountDownLatch probeWakeChannel(String workflowId) throws Exception {
        var client = newClient();
        var probe = client.connectPubSub();
        var seen = new CountDownLatch(1);
        probe.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                seen.countDown();
            }
        });
        probe.async().subscribe("maestro:signal:" + workflowId).get(5, TimeUnit.SECONDS);
        return seen;
    }

    private RedisClient newClient() {
        var client = RedisClient.create(RedisURI.builder()
                .withHost(valkey.getHost())
                .withPort(valkey.getMappedPort(6379))
                .build());
        clients.add(client);
        return client;
    }

    private void pause() {
        valkey.getDockerClient().pauseContainerCmd(valkey.getContainerId()).exec();
        paused = true;
    }

    private void unpause() {
        if (paused) {
            valkey.getDockerClient().unpauseContainerCmd(valkey.getContainerId()).exec();
            paused = false;
        }
    }

    private int signalRowCount(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM maestro_workflow_signal WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
