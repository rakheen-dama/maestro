package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import io.b2mash.maestro.messaging.postgres.PostgresNotificationListener;
import io.b2mash.maestro.messaging.postgres.PostgresSignalNotifier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a signal ingested on one node reaches a workflow parked on another —
 * the cross-service path every Maestro deployment depends on, since the node
 * that consumes a Kafka message is rarely the node running the workflow.
 *
 * <h2>Topology</h2>
 * <p>Two harnesses over one store. Each has its <em>own</em>
 * {@link PostgresSignalNotifier} on its own {@link PostgresNotificationListener}
 * and its own connection, so a notification genuinely crosses a process
 * boundary (Postgres {@code LISTEN}/{@code NOTIFY}) rather than being handed
 * over in-JVM.
 *
 * <h2>Timing contract</h2>
 * <p>Every wait here is bounded at <b>15 seconds</b>, deliberately below
 * {@code SignalManager}'s hardcoded 30-second parked re-check. A test that only
 * passed because the re-check eventually fired would be proving nothing about
 * routing; with a 15s bound, passing means the notifier actually woke the node.
 * The no-notifier profile is therefore <em>not</em> covered here — see
 * {@code SPEC.md} "Open items" item 1.
 */
@Tag("integration")
@DisplayName("A signal ingested on one node reaches a workflow parked on another")
class MultiNodeSignalRoutingIT extends PostgresIntegrationSupport {

    /** Below SignalManager's 30s re-check, so only the notifier can satisfy it. */
    private static final Duration NOTIFIER_BOUND = Duration.ofSeconds(15);

    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();
    private final CountDownLatch parkedOnNodeA = new CountDownLatch(1);

    private final List<PostgresNotificationListener> listeners = new ArrayList<>();
    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void closeNodes() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
        listeners.forEach(PostgresNotificationListener::close);
    }

    @Test
    @DisplayName("a signal delivered on node B wakes the workflow parked on node A, which resumes it")
    void signalDeliveredOnNodeB_wakesTheWorkflowParkedOnNodeA() throws Exception {
        var workflowA = new TestWorkflows.SignalWorkflow(parkedOnNodeA);
        nodeA = node("node-a", recorderA, workflowA);
        nodeB = node("node-b", recorderB, new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-routing");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");

        assertTrue(parkedOnNodeA.await(15, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(15));

        nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "APPROVED");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(NOTIFIER_BOUND),
                "node A must be woken by node B's notification, not by the 30s re-check");
        assertEquals("seed-one:APPROVED-two", handle.result(String.class));

        // The resume happened on node A — node B never touched an activity.
        assertEquals(1, recorderA.count("stepOne"));
        assertEquals(1, recorderA.count("stepTwo"));
        assertEquals(List.of(), recorderB.invocations(),
                "node B ingests the signal; it must not execute the workflow");

        assertEquals(
                List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:SIGNAL_RECEIVED:$maestro:awaitSignal:approval",
                        "3:ACTIVITY_COMPLETED:chain.stepTwo",
                        "4:WORKFLOW_COMPLETED:null"),
                handle.events().stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList());
        assertEquals(List.of(),
                store.getUnconsumedSignals(workflowId, TestWorkflows.SignalWorkflow.SIGNAL),
                "the signal must be consumed exactly once, on node A");
    }

    @Test
    @DisplayName("a signal delivered on node B before the workflow exists is adopted when node A starts it")
    void signalDeliveredOnNodeB_beforeNodeAStartsTheWorkflow_isAdoptedAndConsumed() throws Exception {
        var workflowA = new TestWorkflows.SignalWorkflow(parkedOnNodeA);
        nodeA = node("node-a", recorderA, workflowA);
        nodeB = node("node-b", recorderB, new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("pre-delivery");
        nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "EARLY");

        var orphan = store.getUnconsumedSignals(workflowId, TestWorkflows.SignalWorkflow.SIGNAL);
        assertEquals(1, orphan.size());
        assertEquals(null, orphan.getFirst().workflowInstanceId(),
                "a signal for a workflow that does not exist yet is stored unattached");

        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(NOTIFIER_BOUND),
                "the pre-delivered signal must be found without ever parking");
        assertEquals("seed-one:EARLY-two", handle.result(String.class));
        assertEquals(List.of(),
                store.getUnconsumedSignals(workflowId, TestWorkflows.SignalWorkflow.SIGNAL));
        assertEquals(handle.instanceId(), adoptedInstanceId(workflowId),
                "adoption must attach the orphan to the instance node A created");
        assertEquals(List.of(), recorderB.invocations());
    }

    @Test
    @DisplayName("a run of signals delivered on node B is collected on node A in receipt order")
    void signalsDeliveredOnNodeB_areCollectedOnNodeAInOrder() throws Exception {
        var workflowA = new TestWorkflows.CollectingWorkflow(3, parkedOnNodeA);
        nodeA = node("node-a", recorderA, workflowA);
        nodeB = node("node-b", recorderB, new TestWorkflows.CollectingWorkflow(3, new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("collect-routing");
        var handle = nodeA.start(workflowId, TestWorkflows.CollectingWorkflow.class, "seed");

        assertTrue(parkedOnNodeA.await(15, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(15));

        for (var vote : List.of("alpha", "beta", "gamma")) {
            nodeB.deliverSignal(workflowId, TestWorkflows.CollectingWorkflow.SIGNAL, vote);
        }

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(NOTIFIER_BOUND));
        assertEquals("alpha,beta,gamma", handle.result(String.class),
                "receipt order must survive the cross-node hop");
        assertEquals(List.of(),
                store.getUnconsumedSignals(workflowId, TestWorkflows.CollectingWorkflow.SIGNAL));
        assertEquals(3, handle.events().stream()
                        .filter(e -> e.stepName() != null
                                && e.stepName().startsWith("$maestro:awaitSignal:"))
                        .count(),
                "each vote must be memoized exactly once");
    }

    /**
     * Reads back the instance a stored signal is attached to.
     *
     * <p>The store SPI exposes no read for a consumed signal, so this asserts
     * on the row directly — adoption is a column write, and that column is the
     * whole point of the pre-delivery contract.
     *
     * @param workflowId the workflow the signal was addressed to
     * @return the attached instance ID, or {@code null} if still unattached
     * @throws SQLException if the query fails
     */
    private @Nullable UUID adoptedInstanceId(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT workflow_instance_id FROM maestro_workflow_signal "
                             + "WHERE workflow_id = ?")) {
            stmt.setString(1, workflowId);
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "the signal row must still exist after consumption");
                return rs.getObject(1, UUID.class);
            }
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Builds one node with its own lock client and its own LISTEN/NOTIFY
     * notifier over an independent connection.
     *
     * @param serviceName  the node's service name
     * @param recorder     this node's activity recorder
     * @param workflowImpl this node's workflow instance
     * @return the started harness
     */
    private MaestroEngineHarness node(String serviceName, CountingActivities.Recorder recorder,
                                      Object workflowImpl) {
        var listener = new PostgresNotificationListener(newDataSource());
        listener.start();
        listeners.add(listener);

        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(newDataSource()))
                .signalNotifier(new PostgresSignalNotifier(newDataSource(), listener))
                .instanceLockTtl(Duration.ofSeconds(10))
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(workflowImpl);
        return harness;
    }
}
