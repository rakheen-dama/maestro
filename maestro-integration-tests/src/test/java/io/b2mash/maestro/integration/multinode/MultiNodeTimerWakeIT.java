package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a timer fired (or cancelled) on one node wakes a workflow sleeping on
 * another — the routine multi-instance topology behind Issue 17. The
 * {@code TimerPoller} runs on the elected leader only, and
 * {@code fireTimer}'s unpark is per-JVM, so whenever the leader is not the
 * node owning the parked virtual thread the wake must come from the sleeping
 * node's own durable-store recheck.
 *
 * <h2>Topology</h2>
 * <p>Two harnesses over one store and one Postgres lock backend. Node A owns
 * the workflow (its virtual thread parks in {@code sleep()}); only node B runs
 * a timer poller, which makes B the timer leader deterministically — the exact
 * shape of "leader ≠ owner" without an election race in the fixture.
 *
 * <h2>Timing contract</h2>
 * <p>Node A's wake-recheck interval is 500ms, so a recheck-driven wake lands
 * comfortably inside the 15s bounds. Before the Issue 17 fix, node B durably
 * marks the timer {@code FIRED} (making it invisible to {@code getDueTimers}
 * forever), nothing on node A wakes, and these tests time out.
 */
@Tag("integration")
@DisplayName("A timer fired or cancelled on one node wakes a workflow sleeping on another")
class MultiNodeTimerWakeIT extends PostgresIntegrationSupport {

    /** Node A's recheck interval — the wake path under test. */
    private static final Duration RECHECK_INTERVAL = Duration.ofMillis(500);

    /** Generous CI bound; a recheck wake needs ≈ nap + one interval. */
    private static final Duration WAKE_BOUND = Duration.ofSeconds(15);

    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();

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
    }

    @Test
    @DisplayName("a timer fired by node B's poller wakes the workflow sleeping on node A")
    void timerFiredByNodeBPoller_wakesWorkflowSleepingOnNodeA() {
        nodeA = node("node-a", recorderA, new TestWorkflows.SleepingWorkflow(Duration.ofSeconds(1)));
        nodeB = node("node-b", recorderB, new TestWorkflows.SleepingWorkflow(Duration.ofSeconds(1)));
        // Only node B polls timers — B is the leader, A owns the parked thread.
        nodeB.startTimerPoller(Duration.ofMillis(200), 10);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("xnode-timer-fire");
        var handle = nodeA.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");

        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(15));

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(WAKE_BOUND),
                "node B's poller fires the timer in the store; node A must notice via its "
                        + "recheck — before the Issue 17 fix this wedged forever");
        assertEquals("seed-one-two", handle.result(String.class));

        // The workflow ran on node A only; node B just fired the timer row.
        assertEquals(1, recorderA.count("stepOne"));
        assertEquals(1, recorderA.count("stepTwo"));
        assertEquals(List.of(), recorderB.invocations(),
                "node B fires the timer; it must not execute the workflow");

        assertEquals(1, handle.events().stream()
                        .filter(e -> e.eventType() == EventType.TIMER_FIRED).count(),
                "the cross-node wake must memoize TIMER_FIRED exactly once");
        assertEquals(TimerStatus.FIRED,
                store.findTimer(handle.instanceId(), "sleep-2").orElseThrow().status());
    }

    @Test
    @DisplayName("a timer cancelled on node B wakes the workflow sleeping on node A with the cancelled outcome")
    void timerCancelledOnNodeB_wakesWorkflowSleepingOnNodeA_withCancelledOutcome() {
        nodeA = node("node-a", recorderA,
                new TestWorkflows.CancellableSleepWorkflow(Duration.ofMinutes(10)));
        nodeB = node("node-b", recorderB,
                new TestWorkflows.CancellableSleepWorkflow(Duration.ofMinutes(10)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("xnode-timer-cancel");
        var handle = nodeA.start(workflowId, TestWorkflows.CancellableSleepWorkflow.class, "seed");

        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(15));
        // The sleep is the second operation (after stepOne), so it owns seq 2.
        await().atMost(Duration.ofSeconds(15)).until(() ->
                store.findTimer(handle.instanceId(), "sleep-2").isPresent());

        // The cancel arrives on node B — a node that does not own the parked
        // thread. Its unpark is a local no-op; only the row transition crosses.
        var timer = store.findTimer(handle.instanceId(), "sleep-2").orElseThrow();
        assertTrue(nodeB.executor().cancelTimer(workflowId, "sleep-2", timer.id()),
                "node B's cancel must win the CAS against the PENDING timer");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(WAKE_BOUND),
                "node A must observe the durable CANCELLED row via its recheck and take "
                        + "the fallback branch — before the Issue 17 fix this wedged forever");
        assertEquals("seed-one-cancelled-two", handle.result(String.class));

        assertEquals(1, recorderA.count("stepOne"));
        assertEquals(1, recorderA.count("stepTwo"));
        assertEquals(List.of(), recorderB.invocations(),
                "node B cancels the timer; it must not execute the workflow");

        assertEquals(1, handle.events().stream()
                        .filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count(),
                "the cross-node cancel must memoize TIMER_CANCELLED exactly once");
        assertEquals(0, handle.events().stream()
                .filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Builds one node over the shared store with its own Postgres lock client.
     *
     * @param serviceName  the node's service name
     * @param recorder     this node's activity recorder
     * @param workflowImpl this node's workflow instance
     * @return the built harness
     */
    private MaestroEngineHarness node(String serviceName, CountingActivities.Recorder recorder,
                                      Object workflowImpl) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(new PostgresDistributedLock(newDataSource()))
                .instanceLockTtl(Duration.ofSeconds(10))
                .wakeRecheckInterval(RECHECK_INTERVAL)
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(workflowImpl);
        return harness;
    }
}
