package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature 4 end to end on real PostgreSQL: the assessment's <em>silent wrong
 * replay</em>. A workflow builds an activity's argument from a map's iteration
 * order — the same map, as far as the author can tell, on every node — runs
 * the activity once, and parks. The node that recovers it iterates the map
 * in a different order, so it asks the activity a <em>different question</em>
 * at the same sequence. Before this feature the recorded answer replayed
 * silently and the workflow carried on with a result for an input it never
 * passed. Now the run stands down as
 * {@link StandDownReason#NONDETERMINISTIC_HISTORY} with the
 * {@code argsHash} detail, the activity is not re-executed, nothing is written,
 * and the instance completes normally once a node with the recorded input
 * order adopts it.
 *
 * <p>The second test pins the rolling-deploy contract: a stored row without a
 * fingerprint (a pre-V7 row, or one an older node wrote mid-rollout) is
 * <em>unverified</em>, so the drifted recovery replays as it always did.
 */
@Tag("integration")
@DisplayName("An activity argument that drifts between runs stands down instead of replaying a stale result")
class ArgumentDriftStandDownIT extends PostgresIntegrationSupport {

    /**
     * The one service every node in this suite belongs to. Nodes of a service
     * share its name — recovery adopts only its own service's instances (D1),
     * and timer/retention leadership, the signal-wake channel and the Kafka
     * subscription are all keyed by it. Node identity is the harness itself
     * (its own lock client, lock tokens and timer-leader candidate id).
     */
    private static final String SERVICE = "argument-drift";

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /** The two "identical" configs: equal as maps, different in iteration order. */
    private static final Map<String, String> CONFIG_A = orderedConfig("region", "eu", "tier", "gold");
    private static final Map<String, String> CONFIG_B = orderedConfig("tier", "gold", "region", "eu");

    @Test
    @DisplayName("map-order drift stands down with the argsHash detail, re-executes nothing, and the "
            + "instance completes once a node with the recorded order adopts it")
    void mapOrderDrift_standsDownThenCompletesOnceInputMatchesAgain() throws Exception {
        assertEquals(CONFIG_A, CONFIG_B, "the author sees one config; only the iteration order differs");
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("args-drift");

        // ── Node A: runs stepOne("region,tier:seed") live, parks on the signal.
        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(recorder, CONFIG_A, parkedA, null)) {
            handle = nodeA.start(workflowId, ConfigDrivenWorkflow.class, "seed");
            assertTrue(parkedA.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, recorder.count("stepOne"));

        var recorded = store.getEventBySequence(instanceId, 1).orElseThrow();
        var storedHash = recorded.argsHash();
        assertNotNull(storedHash, "the live outcome must carry its argument fingerprint on Postgres");
        assertTrue(storedHash.matches("[0-9a-f]{64}"), storedHash);
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{ConfigDrivenWorkflow.argument(CONFIG_A, "seed")}),
                storedHash, "the stored fingerprint is the engine's fingerprint of the argument node A passed");
        var driftedHash = ArgumentFingerprint.of(serializer, new Object[]{ConfigDrivenWorkflow.argument(CONFIG_B, "seed")});
        assertNotNull(driftedHash);
        assertNotEquals(storedHash, driftedHash);
        var beforeStatus = handle.status();
        var logBefore = fingerprint(instanceId);

        // ── Node B: iterates the "same" config in the other order → asks a different question at seq 1.
        var observer = new RecordingObserver();
        try (var nodeB = node(recorder, CONFIG_B, new CountDownLatch(1), observer)) {
            assertEquals(1, nodeB.recover());
            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));

            var call = observer.standDowns().getFirst();
            assertAll(
                    () -> assertEquals(1, observer.standDowns().size(), observer.callbackNames().toString()),
                    () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                    () -> assertEquals("seq=1 step=" + recorded.stepName() + " argsHash expected="
                            + driftedHash.substring(0, 8) + " stored=" + storedHash.substring(0, 8), call.detail()),
                    () -> assertTrue(observer.failed().isEmpty(), "a stand-down is not a failure"),
                    () -> assertTrue(observer.compensating().isEmpty(), "nothing is compensated"),
                    () -> assertEquals(beforeStatus, store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(logBefore, fingerprint(instanceId), "a stand-down writes nothing"),
                    () -> assertEquals(1, recorder.count("stepOne"),
                            "the activity was neither re-executed nor silently replayed into a continuing run"));
        }

        // ── Node C: the recorded input order again → replays seq 1, parks, completes on the signal.
        var parkedC = new CountDownLatch(1);
        try (var nodeC = node(recorder, CONFIG_A, parkedC, null)) {
            assertEquals(1, nodeC.recover());
            assertTrue(parkedC.await(30, TimeUnit.SECONDS), "the recovered run must reach its await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeC.deliverSignal(workflowId, ConfigDrivenWorkflow.SIGNAL, "approved");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        }
        assertEquals(1, recorder.count("stepOne"), "replayed on node C, never re-executed");
    }

    @Test
    @DisplayName("a stored row without a fingerprint (pre-V7 / older node) is unverified: the drifted recovery "
            + "replays as before — the rolling-deploy contract")
    void nullStoredHash_isUnverified_driftedRecoveryReplays() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("args-null");

        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(recorder, CONFIG_A, parkedA, null)) {
            handle = nodeA.start(workflowId, ConfigDrivenWorkflow.class, "seed");
            assertTrue(parkedA.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        clearArgsHash(instanceId, 1);          // "this row was written by a node that predates V7"

        var observer = new RecordingObserver();
        var parkedB = new CountDownLatch(1);
        try (var nodeB = node(recorder, CONFIG_B, parkedB, observer)) {
            assertEquals(1, nodeB.recover());
            assertTrue(parkedB.await(30, TimeUnit.SECONDS), "an unverified slot replays and the run parks again");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeB.deliverSignal(workflowId, ConfigDrivenWorkflow.SIGNAL, "approved");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
            assertAll(
                    () -> assertEquals(List.of(), observer.standDowns()),
                    () -> assertEquals(1, recorder.count("stepOne"), "replayed, not re-executed"));
        }
    }

    // ── fixture ───────────────────────────────────────────────────────────

    /**
     * Builds its activity's argument from the iteration order of a map the
     * node supplies — the author's "config", equal on every node, iterated in
     * whatever order the node's map happens to have.
     */
    @DurableWorkflow(name = "ArgsDriftConfigWorkflow")
    public static class ConfigDrivenWorkflow {

        /** Signal name this workflow awaits after its first activity. */
        public static final String SIGNAL = "approval";

        @ActivityStub
        ChainActivities activities;

        private final Map<String, String> config;
        private final CountDownLatch parked;

        /**
         * @param config the node's configuration map, iterated to build the argument
         * @param parked counted down once the workflow reaches its await point
         */
        public ConfigDrivenWorkflow(Map<String, String> config, CountDownLatch parked) {
            this.config = config;
            this.parked = parked;
        }

        /** @return the argument the workflow passes: the config's keys in iteration order, then the input */
        static String argument(Map<String, String> config, String input) {
            return String.join(",", config.keySet()) + ":" + input;
        }

        /**
         * @param input the seed value
         * @return the signal payload combined with the activity result
         */
        @WorkflowMethod
        public String run(String input) {
            var one = activities.stepOne(argument(config, input));
            parked.countDown();
            var decision = WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30));
            return one + ":" + decision;
        }
    }

    private MaestroEngineHarness node(CountingActivities.Recorder recorder,
                                      Map<String, String> config, CountDownLatch parked,
                                      @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        node.registerWorkflow(ConfigDrivenWorkflow.class, () -> new ConfigDrivenWorkflow(config, parked));
        return node;
    }

    private static Map<String, String> orderedConfig(String k1, String v1, String k2, String v2) {
        var map = new LinkedHashMap<String, String>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    // ── SQL helpers ───────────────────────────────────────────────────────

    private void clearArgsHash(UUID instanceId, int seq) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_event SET args_hash = NULL "
                             + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setObject(1, instanceId);
            ps.setInt(2, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** A comparable fingerprint of the durable log: sequence, type, step, argument hash. */
    private List<String> fingerprint(UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .map(e -> "%d:%s:%s:%s".formatted(e.sequenceNumber(), e.eventType(), e.stepName(), e.argsHash()))
                .toList();
    }
}
