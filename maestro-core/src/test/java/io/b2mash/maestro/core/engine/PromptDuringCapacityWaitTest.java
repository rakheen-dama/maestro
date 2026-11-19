package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #72 review 4622153255: a deferred start's prompt reaches the recovery
 * poller even while it waits for task-queue capacity on another, full queue,
 * so the start does not wait a whole poll interval.
 */
class PromptDuringCapacityWaitTest {

    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:wf-other";

    private final CountingStore store = new CountingStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private final WorkflowExecutor node = WorkflowExecutor.builder(store, serializer, SERVICE)
            .distributedLock(lock)
            .lifecycleEventsEnabled(false)
            .taskQueueLimits(Map.of("full", new TaskQueueLimits(1, 1)))
            .shutdownTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        node.shutdown();
    }

    @Test
    void aDeferredStartOnAnotherQueueRunsWithinSecondsWhileThePollerWaitsForCapacity() {
        // Three orphans on queue "full" (1 slot): the pass adopts one running
        // and one queued and defers the third, so the poller waits for capacity.
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "full-" + i, "full");
        }
        work.completeAtOnceFor("wf-other"::equals);
        var proxy = work.proxy(node, store, serializer, "A");
        var registrations = Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "full", () -> new WorkWorkflow(proxy), workMethod()));
        node.startRecoveryPoller(registrations, Duration.ofSeconds(60));
        assertEquals(1, valueWithin(node::recoveryDeferredCount, 1, Duration.ofSeconds(5)),
                "the startup pass deferred one orphan: the poller now waits for capacity on 'full'");

        // A start on another (unbounded) queue, deferred because its lock is held elsewhere.
        var elsewhere = lock.tryAcquire(KEY, Duration.ofSeconds(30)).orElseThrow();
        node.startWorkflow("wf-other", "WorkWorkflow", "other", null, () -> new WorkWorkflow(proxy), workMethod());
        lock.release(elsewhere);

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-other").orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)),
                "the prompt ended the capacity wait; the 60 s poll interval was not waited for");
        assertTrue(work.order().contains("wf-other"));
    }
}
