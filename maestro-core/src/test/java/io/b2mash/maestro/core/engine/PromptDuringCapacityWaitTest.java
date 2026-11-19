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
    private final io.b2mash.maestro.core.observe.RecordingEngineObserver observer =
            new io.b2mash.maestro.core.observe.RecordingEngineObserver();
    private final WorkflowExecutor node = WorkflowExecutor.builder(store, serializer, SERVICE)
            .distributedLock(lock)
            .observer(observer)
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
        assertEquals(1, observer.startDeferrals().size(), "the start was deferred while the lock was held");
        lock.release(elsewhere);

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-other").orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)),
                "the prompt ended the capacity wait; the 60 s poll interval was not waited for");
        assertTrue(work.order().contains("wf-other"));
    }

    /**
     * PR #72 review, follow-up: a prompt requested in the capacity wait after
     * its last pending-prompt check but before it reads the capacity epoch for
     * a headroom check must not be absorbed by that read; with no headroom the
     * next wait would otherwise sleep to the full pass.
     */
    @Test
    void aPromptRequestedJustBeforeAHeadroomCheckIsNotLost() throws Exception {
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "full-" + i, "full");
        }
        work.completeAtOnceFor("wf-other"::equals);
        var proxy = work.proxy(node, store, serializer, "A");
        var registrations = Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "full", () -> new WorkWorkflow(proxy), workMethod()));
        node.startRecoveryPoller(registrations, Duration.ofSeconds(60));
        assertEquals(1, valueWithin(node::recoveryDeferredCount, 1, Duration.ofSeconds(5)));

        // In the gap, another thread defers a start: its prompt is requested there.
        var elsewhere = lock.tryAcquire(KEY, Duration.ofSeconds(30)).orElseThrow();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var hookRan = new java.util.concurrent.CountDownLatch(1);
        var starterFinished = new java.util.concurrent.atomic.AtomicBoolean();
        var deferredWhileHeld = new java.util.concurrent.atomic.AtomicBoolean();
        var starterFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        node.beforeRecoveryHeadroomCheck = () -> {
            if (first.compareAndSet(true, false)) {
                var starter = Thread.ofVirtual().start(() -> {
                    try {
                        node.startWorkflow("wf-other", "WorkWorkflow", "other",
                                null, () -> new WorkWorkflow(proxy), workMethod());
                    } catch (Throwable t) {
                        starterFailure.set(t);
                    }
                });
                try {
                    starterFinished.set(starter.join(Duration.ofSeconds(5)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // The start must have been deferred while the competing lock was
                // still held; only then is the lock let go.
                deferredWhileHeld.set(observer.startDeferrals().size() == 1);
                lock.release(elsewhere);
                hookRan.countDown();
            }
        };
        // A capacity signal with no headroom brings the wait to its headroom check.
        node.taskQueueGovernor().capacitySignal().signal();
        assertTrue(hookRan.await(5, java.util.concurrent.TimeUnit.SECONDS), "the wait reached its headroom check");
        assertTrue(starterFinished.get(), "the deferring start returned inside the gap");
        assertEquals(null, starterFailure.get(), "the deferring start did not fail");
        assertTrue(deferredWhileHeld.get(), "the start was deferred while the competing lock was held");

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-other")
                        .map(i -> i.status()).orElse(WorkflowStatus.RUNNING), WorkflowStatus.COMPLETED,
                Duration.ofSeconds(5)), "the prompt requested in the gap was not lost");
    }
}
