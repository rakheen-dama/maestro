package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities.DomainFailure;
import io.b2mash.maestro.integration.workflows.CountingActivities.FailingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.NoCtorFailure;
import io.b2mash.maestro.integration.workflows.CountingActivities.ThrowingFailingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C15 end to end on real PostgreSQL: the cause a workflow observes after a
 * recovery replay matches the live one wherever the type permits, and is a
 * {@link ReconstructedActivityFailure} naming the original class where it does not.
 */
@Tag("integration")
@DisplayName("A replayed activity failure is deterministic across nodes")
class ActivityFailureReplayIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @Test
    @DisplayName("an exception with a public (String) constructor replays as its own type")
    void domainExceptionWithStringCtor_replaysIdentically() throws Exception {
        var run = runLiveThenReplay("failure-domain", () -> new DomainFailure("boom"));

        assertEquals(DomainFailure.class.getName() + "|boom", run.live().getFirst());
        assertEquals(run.live().getFirst(), run.replay().getFirst());
        assertEquals(run.live().getFirst(), run.result());
        assertEquals(1, run.failedEvents(), "replay re-executed nothing");
    }

    @Test
    @DisplayName("an exception without a (String) constructor replays as ReconstructedActivityFailure "
            + "carrying the original class name")
    void exceptionWithoutStringCtor_replaysAsReconstructedWithOriginalName() throws Exception {
        var run = runLiveThenReplay("failure-noctor", () -> new NoCtorFailure("boom", 7));

        assertEquals(NoCtorFailure.class.getName() + "|boom", run.live().getFirst());
        assertEquals(ReconstructedActivityFailure.class.getName() + "|boom|" + NoCtorFailure.class.getName(),
                run.replay().getFirst());
        assertEquals(1, run.failedEvents(), "replay re-executed nothing");
    }

    private record Run(List<String> live, List<String> replay, String result, long failedEvents) {
    }

    private Run runLiveThenReplay(String prefix, Supplier<RuntimeException> thrown) throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId(prefix);
        var liveObservations = new CopyOnWriteArrayList<String>();
        var replayObservations = new CopyOnWriteArrayList<String>();

        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(prefix + "-a", thrown, liveObservations, parkedA)) {
            handle = nodeA.start(workflowId, TestWorkflows.FailureReplayWorkflow.class, "seed");
            assertTrue(parkedA.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }

        var parkedB = new CountDownLatch(1);
        try (var nodeB = node(prefix + "-b", thrown, replayObservations, parkedB)) {
            assertEquals(1, nodeB.recover());
            assertTrue(parkedB.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeB.deliverSignal(workflowId, "go", "now");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        }
        var failed = store.getEvents(handle.instanceId()).stream()
                .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED).count();
        return new Run(liveObservations, replayObservations, handle.result(String.class), failed);
    }

    private MaestroEngineHarness node(String serviceName, Supplier<RuntimeException> thrown,
                                      List<String> observations, CountDownLatch parked) {
        var node = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .build();
        node.registerActivities(FailingActivities.class, new ThrowingFailingActivities(thrown));
        node.registerWorkflow(TestWorkflows.FailureReplayWorkflow.class, () -> new TestWorkflows.FailureReplayWorkflow(observations, parked));
        return node;
    }
}
