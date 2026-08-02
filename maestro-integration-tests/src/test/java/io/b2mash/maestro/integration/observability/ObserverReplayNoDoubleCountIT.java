package io.b2mash.maestro.integration.observability;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.spring.observe.MicrometerEngineObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay-no-double-count pin (observability design doc §8.2, spec B1
 * evidence) against a real Postgres store and a real Postgres lock backend,
 * mirroring {@link io.b2mash.maestro.integration.shutdown.ShutdownContractIT}'s
 * restart pattern: node A runs a workflow to a park, is shut down (a crash —
 * durable state is all that survives), and node B recovers it from Postgres
 * alone and completes it.
 *
 * <p>A single {@link SimpleMeterRegistry}, bound through one
 * {@link MicrometerEngineObserver}, is wired into <em>both</em> harnesses via
 * {@link MaestroEngineHarness.Builder#observer}. This is the one place this
 * suite deliberately simplifies "one node, one registry": what the pin needs
 * to prove is that the total count of a logical event, observed across the
 * whole crash-and-recover lifecycle, is exactly one per real occurrence —
 * sharing a registry makes that a single, direct assertion instead of a
 * cross-registry sum, and does not change what is being proven (each
 * harness still runs its own real {@link
 * io.b2mash.maestro.core.engine.WorkflowExecutor}, and the memoization engine
 * has no idea the two nodes happen to report to the same registry).
 *
 * <p>{@link TestWorkflows.SignalWorkflow} is the fixture: it calls one
 * activity before parking on a signal and one after, so N=2 activity
 * completions are the exactly-once ledger this test pins — one live on node
 * A (before the crash), one live on node B (after recovery). The replayed
 * copy of the first activity, which the recovering node's replay pass must
 * walk to reach the park, must not touch the timer at all — the assertion
 * that would fail if the {@code replayed} flag were ignored, and is exactly
 * the case {@link MicrometerEngineObserverTest} in the starter module pins
 * at the unit level; this is the same contract proven through a real
 * crash/recovery over Postgres.
 */
@Tag("integration")
@DisplayName("A recovered workflow's replayed activity does not double-count maestro.activity.duration")
class ObserverReplayNoDoubleCountIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    @Test
    @DisplayName("crash after the pre-park activity, recover, complete: activity.duration count == 2, "
            + "workflow.started == 1, workflow.completed == 1 — the replayed step is never re-counted")
    void replayedActivityIsNotDoubleCounted() throws Exception {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerEngineObserver(registry);
        var recorder = new CountingActivities.Recorder();

        var parked = new CountDownLatch(1);
        MaestroEngineHarness nodeA = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("node-a")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .observer(observer)
                .build();
        nodeA.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("observer-replay");
        var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        assertEquals(1.0, registry.get("maestro.activity.duration")
                .tag("activity", "chain.stepOne").tag("outcome", "completed").timer().count(),
                "the live pre-park activity must be counted exactly once before the crash");
        assertEquals(1.0, registry.get("maestro.workflow.started")
                .tag("workflow", "SignalWorkflow").counter().count());

        // "Crash": node A stops while the workflow is parked — durable state
        // stays WAITING_SIGNAL, exactly what recovery replays from.
        nodeA.close();

        // ── Recovery on a second node over the same store ──
        MaestroEngineHarness nodeB = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("node-b")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .observer(observer)
                .build();
        nodeB.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        assertEquals(1, nodeB.recover(),
                "the workflow parked by the crash must be recoverable from Postgres alone");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        nodeB.close();

        // ── The exactly-once ledger across the whole crash/recovery lifecycle ──
        assertEquals(1.0, registry.get("maestro.activity.duration")
                .tag("activity", "chain.stepOne").tag("outcome", "completed").timer().count(),
                "the pre-park activity, replayed to reach the park during recovery, must still be "
                        + "counted exactly once total — a double-count would make this 2");
        assertEquals(1.0, registry.get("maestro.activity.duration")
                .tag("activity", "chain.stepTwo").tag("outcome", "completed").timer().count(),
                "the post-signal activity is live exactly once, on the recovering node");
        assertEquals(1.0, registry.get("maestro.workflow.started")
                .tag("workflow", "SignalWorkflow").counter().count(),
                "recovery is a resume, never a second workflowStarted");
        assertEquals(1.0, registry.get("maestro.workflow.completed")
                .tag("workflow", "SignalWorkflow").counter().count());
        assertEquals(1, recorder.count("stepOne"),
                "sanity check on the activity fixture itself: stepOne must have executed exactly once "
                        + "(recorder counts live executions only, memoized calls never reach the impl) "
                        + "— 2 here would mean the engine re-executed a memoized step, a correctness bug "
                        + "entirely separate from the metrics adapter");
        assertEquals(1, recorder.count("stepTwo"));
    }
}
