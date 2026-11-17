package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With no lock backend, a recovery pass and a fresh start can race on one
 * node (Issue 74): the start writes its {@code RUNNING} row before it
 * registers the run in this node's running set, so a pass that reads the row
 * in between adopts it and launches a second run of the same workflow. The
 * lease fence and the terminal compare-and-set keep the persisted outcome
 * single — the adopted run writes under the new epoch; the fresh run's writes
 * are fenced, or it converges on the twin's outcome — but each run may execute
 * the first activity, which is why activities must be idempotent.
 * With a lock backend the start takes the instance lock before it writes the
 * row, so the pass cannot adopt it.
 */
class NoLockStartRecoveryRaceTest {

    private final CountingStore store = new CountingStore();
    private final BlockingWork work = new BlockingWork();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final WorkflowExecutor a = WorkflowExecutor.builder(store,
                    new PayloadSerializer(JsonMapper.builder().build()), SERVICE)
            .lifecycleEventsEnabled(false)
            .observer(observer)
            .shutdownTimeout(Duration.ofSeconds(2))
            .build(); // no distributedLock: NO_BACKEND

    @AfterEach
    void tearDown() {
        work.releaseAll();
        a.shutdown();
    }

    @Test
    void aPassBetweenAStartsRowWriteAndItsRegistrationAdoptsATwinAndTheFenceKeepsOneOutcome() throws Exception {
        work.completeAtOnceFor(_ -> true);
        var proxy = work.proxy(a, store, new PayloadSerializer(JsonMapper.builder().build()), "A");
        var registrations = Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "orders", () -> new WorkWorkflow(proxy), workMethod()));

        // Hold the fresh start after its row is written, before its run is registered.
        var startHeld = new CountDownLatch(1);
        var releaseStart = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        a.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                startHeld.countDown();
                try {
                    releaseStart.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var starter = Thread.ofVirtual().start(() -> a.startWorkflow("wf-1", "WorkWorkflow", "orders", null,
                () -> new WorkWorkflow(proxy), workMethod()));
        assertTrue(startHeld.await(5, TimeUnit.SECONDS), "the start wrote its row and is held before registering");

        var pass = a.recoveryPass(registrations);
        assertEquals(1, pass.adopted(), "the pass adopted the just-started row: a twin");
        assertEquals(WorkflowStatus.COMPLETED,
                valueWithin(() -> store.getInstance("wf-1").orElseThrow().status(), WorkflowStatus.COMPLETED,
                        Duration.ofSeconds(5)), "the adopted run completes");

        releaseStart.countDown();
        starter.join(Duration.ofSeconds(5));
        var instance = store.getInstance("wf-1").orElseThrow();
        valueWithin(() -> a.runningCount(), 0, Duration.ofSeconds(5));
        // In this order the twin finished first: the fresh run replays the
        // recorded activity and loses the terminal write. Had the fresh run
        // executed the activity before the adoption, its outcome append would
        // be fenced instead and the twin would execute it again.
        assertEquals(List.of(AbandonReason.CONVERGED), observer.abandonReasons(),
                "the fresh run converged on the twin's outcome; " + observer.callbackNames());
        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-1").orElseThrow().status()),
                () -> assertEquals(1, store.getEvents(instance.id()).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_COMPLETED).count(),
                        "one outcome is recorded"),
                () -> assertEquals(1, instance.leaseEpoch(), "the adoption moved the fence"),
                () -> assertEquals(1, work.entries(), "the activity ran once, in the twin"),
                () -> assertEquals(List.of(), observer.failed()));
    }
}
