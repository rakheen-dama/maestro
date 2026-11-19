package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 81: an {@code Error} an observer throws — one that is neither a
 * {@code VirtualMachineError} nor a {@code MaestroControlFlowError} — is the
 * observer's fault and must not change what the engine does. The executor
 * contains every observer it is given, whether or not the host wrapped it.
 */
class ObserverErrorIsolationTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        work.releaseAll();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** Issue 81's done-when: the pass adopts every instance and reports it. */
    @Test
    void anErrorFromWorkflowResumedNeitherEndsTheRecoveryPassNorChangesItsAdoptedCount() {
        var store = new CountingStore();
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        executor = node(store, new EngineObserver() {
            @Override
            public void workflowResumed(WorkflowInfo w) {
                throw new AssertionError("observer bug");
            }
        });
        work.releaseAll();

        var pass = assertDoesNotThrow(() -> executor.recoveryPass(registrations(store)));

        assertAll(
                () -> assertEquals(3, pass.adopted(), "every instance adopted and counted"),
                () -> assertEquals(3, valueWithin(() -> store.completed("wf-"), 3, Duration.ofSeconds(5)),
                        "every adopted run completed"),
                () -> assertTrue(executor.observerErrorCount() >= 3, "each containment counted"));
    }

    @Test
    void anErrorFromWorkflowStartedDoesNotFailTheStart() throws Exception {
        var store = new CountingStore();
        executor = node(store, new EngineObserver() {
            @Override
            public void workflowStarted(WorkflowInfo w) {
                throw new LinkageError("observer built against another library version");
            }
        });
        work.releaseAll();
        var proxy = work.proxy(executor, store, serializer, "A");

        assertDoesNotThrow(() -> executor.startWorkflow("wf-start", "WorkWorkflow", "orders", null,
                () -> new WorkWorkflow(proxy), workMethod()));

        assertEquals(WorkflowStatus.COMPLETED, valueWithin(
                () -> store.getInstance("wf-start").map(i -> i.status()).orElse(null),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)), "the run went on to complete");
        assertEquals(1, executor.observerErrorCount());
    }

    /**
     * PR #74 review 4624922225: the components the executor builds share its
     * one contained observer, so their contained failures are counted on
     * {@code observerErrorCount()} too — here the instance-lock manager's.
     */
    @Test
    void anErrorFromAComponentsCallbackIsCountedOnTheExecutor() throws Exception {
        var store = new CountingStore();
        executor = node(store, new EngineObserver() {
            @Override
            public void instanceLockAcquired(String workflowId) {
                throw new IllegalStateException("observer bug");
            }
        });
        work.releaseAll();
        var proxy = work.proxy(executor, store, serializer, "A");

        executor.startWorkflow("wf-lock", "WorkWorkflow", "orders", null, () -> new WorkWorkflow(proxy), workMethod());

        assertEquals(1, executor.observerErrorCount(), "the lock manager's containment counts on the executor");
    }

    private WorkflowExecutor node(CountingStore store, EngineObserver observer) {
        return WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .observer(observer)
                .build();
    }

    private Map<String, WorkflowRegistration> registrations(CountingStore store) {
        var proxy = work.proxy(executor, store, serializer, "A");
        return Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "orders", () -> new WorkWorkflow(proxy), workMethod()));
    }
}
