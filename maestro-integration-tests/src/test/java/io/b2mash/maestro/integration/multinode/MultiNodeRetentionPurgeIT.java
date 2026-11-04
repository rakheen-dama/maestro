package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the retention sweeper's multi-node contract against real Postgres:
 * two nodes of the <em>same service</em> both run a sweeper, exactly one of
 * them is elected on {@code maestro:leader:retention:<service>} and purges,
 * the other never touches the store, and a purged workflow is gone together
 * with its events, signals and timers.
 *
 * <p>Retention is one second and the interval 200 ms, so three workflows that
 * completed on node A are purged within a few sweeps. The leader is whichever
 * node started first (its first sweep runs at once); the assertion is on
 * "exactly one", not on which.
 */
@Tag("integration")
@DisplayName("Two nodes of one service run the retention sweeper; exactly one purges")
class MultiNodeRetentionPurgeIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "retention-it";
    private static final String ELECTION_KEY = "maestro:leader:retention:" + SERVICE;

    private final RetentionObserver observerA = new RetentionObserver();
    private final RetentionObserver observerB = new RetentionObserver();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void close() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("exactly one node purges the finished workflows, history included; the other emits nothing")
    void oneLeaderPurges_theOtherNodeNever() {
        nodeA = node(observerA);
        nodeB = node(observerB);

        var instanceIds = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            var workflowId = MaestroEngineHarness.uniqueWorkflowId("retention");
            var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "seed");
            awaitStatus(workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(30));
            var id = handle.instanceId();
            instanceIds.add(id);
            // A late signal and a leftover timer too, so the purge has every
            // child table to clear.
            store.saveSignal(new WorkflowSignal(UUID.randomUUID(), id, workflowId, "late", null, false,
                    Instant.now()));
            store.saveTimer(new WorkflowTimer(UUID.randomUUID(), id, workflowId, "sleep-99",
                    Instant.now().plusSeconds(3600), TimerStatus.PENDING, Instant.now()));
        }
        assertEquals(3L, instanceIds.stream().filter(id -> childRows("maestro_workflow_event", id) > 0).count());

        var settings = new WorkflowExecutor.RetentionSettings(Duration.ofMillis(200),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 500, 100);
        nodeA.executor().startRetentionSweeper(settings);
        nodeB.executor().startRetentionSweeper(settings);

        await("every finished workflow is purged once older than the 1 s retention")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> instanceIds.stream().allMatch(id -> instanceRows(id) == 0));

        for (var id : instanceIds) {
            assertEquals(0, childRows("maestro_workflow_event", id), "events of " + id);
            assertEquals(0, childRows("maestro_workflow_signal", id), "signals of " + id);
            assertEquals(0, childRows("maestro_workflow_timer", id), "timers of " + id);
        }

        // Exactly one node purged; the other never emitted a retentionPass at
        // all, because a non-leader never reaches the store.
        var purgedByA = observerA.instancesPurged();
        var purgedByB = observerB.instancesPurged();
        assertEquals(3, purgedByA + purgedByB, "every purge is counted exactly once across the fleet");
        assertTrue((purgedByA == 3 && observerB.passes.isEmpty()) || (purgedByB == 3 && observerA.passes.isEmpty()),
                "exactly one node must be the leader: A purged " + purgedByA + " over " + observerA.passes.size()
                        + " pass(es), B purged " + purgedByB + " over " + observerB.passes.size() + " pass(es)");
        assertEquals(1, leaderRows(), "one election row for " + ELECTION_KEY);

        // A workflow finished after the purge, still inside its retention,
        // survives the sweeps that keep running.
        var fresh = MaestroEngineHarness.uniqueWorkflowId("retention-fresh");
        nodeB.start(fresh, TestWorkflows.ChainWorkflow.class, "seed");
        var freshInstance = awaitStatus(fresh, WorkflowStatus.COMPLETED, Duration.ofSeconds(30));
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
                .until(() -> instanceRows(freshInstance.id()) == 1);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(EngineObserver observer) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(new PostgresDistributedLock(newDataSource()))
                .observer(observer)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(new CountingActivities.Recorder()));
        harness.registerWorkflow(TestWorkflows.ChainWorkflow.class);
        return harness;
    }

    private int instanceRows(UUID instanceId) {
        return count("SELECT count(*) FROM maestro_workflow_instance WHERE id = ?", instanceId);
    }

    private int childRows(String table, UUID instanceId) {
        return count("SELECT count(*) FROM " + table + " WHERE workflow_instance_id = ?", instanceId);
    }

    private int leaderRows() {
        return count("SELECT count(*) FROM maestro_leader_election WHERE election_key = ?", ELECTION_KEY);
    }

    private int count(String sql, Object param) {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Collects every {@code retentionPass} one node emits. */
    private static final class RetentionObserver implements EngineObserver {

        final List<PurgeResult> passes = new CopyOnWriteArrayList<>();

        @Override
        public void retentionPass(PurgeResult purged) {
            passes.add(purged);
        }

        int instancesPurged() {
            return passes.stream().mapToInt(PurgeResult::instances).sum();
        }
    }
}
