package io.b2mash.maestro.integration.observability;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.OtelTracingFixture;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.spring.observe.TracingEngineObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-phantom-spans pin against a real crash and a real Postgres store: a
 * recovered workflow replays its memoized steps to reach the park, and those
 * replayed steps must produce <b>zero</b> spans on the recovering node.
 *
 * <p>Structured exactly like {@link ObserverReplayNoDoubleCountIT} — node A runs
 * {@link TestWorkflows.SignalWorkflow} to its park and is then killed; node B
 * recovers from Postgres alone and completes it — but each node gets its
 * <em>own</em> {@link OtelTracingFixture}, because "how many spans did the
 * recovering node create" is precisely the question, and a shared exporter
 * would blur node A's live spans into node B's ledger.
 */
@Tag("integration")
@DisplayName("A recovered workflow's replayed activity produces no spans on the recovering node")
class TracingReplayNoSpansIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private static final String SEGMENT_SPAN = "maestro.workflow.run";
    private static final String ACTIVITY_SPAN = "maestro.activity";

    @Test
    @DisplayName("node B replays stepOne to reach the park and emits exactly one activity span — "
            + "for the genuinely live stepTwo")
    void replayedActivityProducesNoSpanOnTheRecoveringNode() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var parked = new CountDownLatch(1);

        try (var otelA = new OtelTracingFixture("node-a");
             var otelB = new OtelTracingFixture("node-b")) {

            var nodeA = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName("node-a")
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otelA.tracer(), otelA.propagator()))
                    .build();
            nodeA.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("tracing-replay");
            var handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

            assertEquals(1, otelA.spansNamed(ACTIVITY_SPAN).size(),
                    "node A ran exactly one live activity before parking");

            // "Crash": node A stops while the workflow is parked.
            nodeA.close();

            var nodeB = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName("node-b")
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otelB.tracer(), otelB.propagator()))
                    .build();
            nodeB.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

            assertEquals(1, nodeB.recover());
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeB.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
            nodeB.close();

            var activitySpansOnB = otelB.spansNamed(ACTIVITY_SPAN);
            assertEquals(1, activitySpansOnB.size(),
                    () -> "node B replayed stepOne and ran stepTwo live, so exactly one activity "
                            + "span may exist on B; a phantom span for the replayed step would make "
                            + "this 2. Spans were: " + activitySpansOnB.stream()
                            .map(s -> OtelTracingFixture.attribute(s, "maestro.activity.name")).toList());
            assertEquals("chain.stepTwo",
                    OtelTracingFixture.attribute(activitySpansOnB.getFirst(), "maestro.activity.name"),
                    "the one activity span on B must be the live post-signal step");

            assertTrue(otelB.spansNamed(SEGMENT_SPAN).size() >= 1,
                    "the resumed run still produces a run-segment span — replay is silent, "
                            + "not invisible");
            assertEquals(1, recorder.count("stepOne"),
                    "sanity check on the engine itself: the memoized step is never re-executed");
        }
    }
}
