package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C9 end to end on real PostgreSQL: a stored activity result this build cannot
 * deserialize stands the run down as {@link StandDownReason#UNKNOWN_EVENT_PAYLOAD},
 * and the observed detail names the deserializer's root cause. Restoring the
 * payload (the operator rolled back) lets the instance finish.
 *
 * <p>The incompatible payload change is simulated by editing the recorded row
 * in SQL, which from the replaying node's point of view is indistinguishable
 * from a build that reshaped the type.
 */
@Tag("integration")
@DisplayName("An unreadable stored payload stands down naming its cause, and recovers once restored")
class UnreadablePayloadStandDownIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @Test
    @DisplayName("a reshaped String result stands down with the root cause in the detail, then "
            + "completes once the payload is restored")
    void reshapedPayloadStandsDownNamingTheCauseThenCompletesOnceRestored() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("payload-reshaped");

        var parked = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = signalNode("payload-a", recorder, parked, null)) {
            handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, recorder.count("stepOne"));

        var original = store.getEventBySequence(instanceId, 1).orElseThrow().payload().toString();
        setPayload(instanceId, 1, "{\"reshaped\":true}");

        var observer = new RecordingObserver();
        try (var nodeB = signalNode("payload-b", recorder, new CountDownLatch(1), observer)) {
            assertEquals(1, nodeB.recover());
            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));

            var call = observer.standDowns().getFirst();
            assertAll(
                    () -> assertEquals(1, observer.standDowns().size(), observer.callbackNames().toString()),
                    () -> assertEquals(StandDownReason.UNKNOWN_EVENT_PAYLOAD, call.reason()),
                    () -> assertTrue(call.detail().startsWith("seq=1 cause="), call.detail()),
                    () -> assertTrue(call.detail().contains("Exception"),
                            "the root cause's class name must be present: " + call.detail()),
                    () -> assertTrue(observer.failed().isEmpty()),
                    () -> assertEquals(WorkflowStatus.WAITING_SIGNAL,
                            store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(1, recorder.count("stepOne")),
                    () -> assertFalse(lockRowHeld(workflowId)));
        }

        // ── Rollback: the payload is readable again ─────────────────────────
        setPayload(instanceId, 1, original);

        var completionParked = new CountDownLatch(1);
        try (var nodeC = signalNode("payload-c", recorder, completionParked, null)) {
            assertEquals(1, nodeC.recover());
            assertTrue(completionParked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeC.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        }
        assertEquals(1, recorder.count("stepOne"), "replayed on node C, never re-executed");
    }

    // signalNode / setPayload / lockRowHeld are copied from ReplayDriftStandDownIT: a handful
    // of lines, not worth a shared support class.
    private MaestroEngineHarness signalNode(String serviceName, CountingActivities.Recorder recorder,
                                            CountDownLatch parked, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        node.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));
        return node;
    }

    private void setPayload(UUID instanceId, int seq, String json) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_event SET payload = ?::jsonb "
                             + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setString(1, json);
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private boolean lockRowHeld(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM maestro_distributed_lock "
                             + "WHERE lock_key = ? AND expires_at > now()")) {
            ps.setString(1, "maestro:lock:workflow:" + workflowId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
