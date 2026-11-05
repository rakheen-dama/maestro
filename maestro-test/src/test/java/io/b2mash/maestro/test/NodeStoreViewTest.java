package io.b2mash.maestro.test;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.RunLease;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-node store view: a dead node's workflow threads can no longer write,
 * everything else still reaches the shared store, and the view is transparent
 * to the store's own contracts (the lease fence included).
 */
class NodeStoreViewTest {

    /** Every {@link WorkflowStore} method that changes state — kept explicit so a new SPI method must be classified. */
    private static final Set<String> WRITES = Set.of(
            "createInstance", "updateInstance", "completeInstance", "appendEvent", "deleteFailureEvents",
            "saveSignal", "saveSignalIfAbsent", "markSignalConsumed", "appendEventAndConsumeSignal",
            "adoptOrphanedSignals", "deleteUnconsumedSignalsForTerminalInstancesBefore",
            "purgeTerminalInstancesBefore", "saveTimer", "markTimerFired", "markTimerCancelled");

    private static final Set<String> READS = Set.of(
            "getInstance", "getRecoverableInstances", "getEventBySequence", "getEvents",
            "getUnconsumedSignals", "getDueTimers", "findTimer");

    private final InMemoryWorkflowStore delegate = new InMemoryWorkflowStore();

    @Test
    void everyStoreMethodIsClassifiedAsAReadOrAWrite() {
        var names = Arrays.stream(WorkflowStore.class.getMethods())
                .filter(m -> !m.isSynthetic() && !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        var unclassified = new TreeSet<>(names);
        unclassified.removeAll(WRITES);
        unclassified.removeAll(READS);
        assertTrue(unclassified.isEmpty(),
                "classify these WorkflowStore methods in NodeStoreView and this test: " + unclassified);

        for (var method : WorkflowStore.class.getMethods()) {
            assertEquals(WRITES.contains(method.getName()), NodeStoreView.isWrite(method),
                    method.getName() + " is misclassified");
        }
    }

    @Test
    void aDeadNodeBlocksWritesFromWorkflowThreadsOnly() throws Exception {
        var view = NodeStoreView.over(delegate, NodeStoreView.CrashPolicy.NONE);
        var store = view.store();
        var instance = store.createInstance(instance("wf-1"));

        view.kill("test");
        assertTrue(view.isDead());

        // A workflow thread of the dead node: writes stop, reads still work.
        assertThrows(ExecutorShutdownException.class,
                () -> onWorkflowThread(instance, () -> {
                    store.appendEvent(event(instance, 1));
                    return null;
                }));
        assertThrows(ExecutorShutdownException.class,
                () -> onWorkflowThread(instance, () -> {
                    store.appendEvent(event(instance, 1), lease(instance));
                    return null;
                }));
        assertEquals(0, onWorkflowThread(instance, () -> store.getEvents(instance.id()).size()));

        // The test thread ("a signal lands while the service is down"): still reaches the store.
        store.saveSignal(signal("wf-1"));
        assertEquals(1, store.getUnconsumedSignals("wf-1", "go").size());
        assertEquals(1, delegate.getUnconsumedSignals("wf-1", "go").size());
        assertTrue(delegate.getEvents(instance.id()).isEmpty(), "nothing the dead node tried was written");
    }

    @Test
    void aLiveNodePassesEveryCallThrough() throws Exception {
        var view = NodeStoreView.over(delegate, NodeStoreView.CrashPolicy.NONE);
        var store = view.store();
        var instance = store.createInstance(instance("wf-2"));

        onWorkflowThread(instance, () -> {
            store.appendEvent(event(instance, 1), lease(instance));
            return null;
        });

        assertEquals(1, delegate.getEvents(instance.id()).size());
        assertFalse(view.isDead());
    }

    @Test
    void aStaleLeaseIsStillFencedThroughTheView() {
        var store = NodeStoreView.over(delegate, NodeStoreView.CrashPolicy.NONE).store();
        var instance = store.createInstance(instance("wf-3"));
        var adopted = instance.toBuilder().leaseEpoch(1).version(1).updatedAt(Instant.now()).build();
        store.updateInstance(adopted);

        var stale = new RunLease(instance.id(), 0);
        assertThrows(FencedWriteException.class, () -> store.appendEvent(event(instance, 1), stale),
                "the fenced default is dispatched to the in-memory store's real override");
        assertTrue(delegate.getEvents(instance.id()).isEmpty());
    }

    @Test
    void theCrashPolicyDecidesBeforeAndAfterThePersist() throws Exception {
        var policy = new NodeStoreView.CrashPolicy() {
            @Override
            public String before(WorkflowEvent event) {
                return event.sequenceNumber() == 2 ? "before seq 2" : null;
            }

            @Override
            public String after(WorkflowEvent event) {
                return event.sequenceNumber() == 1 ? "after seq 1" : null;
            }
        };
        var view = NodeStoreView.over(delegate, policy);
        var store = view.store();
        var instance = store.createInstance(instance("wf-4"));

        var after = assertThrows(ExecutorShutdownException.class, () -> onWorkflowThread(instance, () -> {
            store.appendEvent(event(instance, 1), lease(instance));
            return null;
        }));
        assertTrue(after.getMessage().startsWith("Simulated crash: after seq 1"), after.getMessage());
        assertEquals(1, delegate.getEvents(instance.id()).size(), "persisted, then the node died");
        assertTrue(view.isDead());

        var fresh = NodeStoreView.over(delegate, policy);
        assertThrows(ExecutorShutdownException.class, () -> onWorkflowThread(instance, () -> {
            fresh.store().appendEvent(event(instance, 2), lease(instance));
            return null;
        }));
        assertEquals(1, delegate.getEvents(instance.id()).size(), "the node died before the persist");

        // The test thread never triggers a crash.
        NodeStoreView.over(delegate, policy).store().appendEvent(event(instance, 2));
        assertEquals(2, delegate.getEvents(instance.id()).size());
    }

    @Test
    void objectMethodsDoNotReachTheDelegate() {
        var view = NodeStoreView.over(delegate, NodeStoreView.CrashPolicy.NONE);
        var store = view.store();
        assertEquals(store, store);
        assertTrue(store.toString().contains("NodeStoreView"));
        assertEquals(System.identityHashCode(store), store.hashCode());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static <T> T onWorkflowThread(WorkflowInstance instance, Callable<T> body) throws Exception {
        var ctx = new WorkflowContext(instance.id(), instance.workflowId(), instance.runId(),
                "t", "default", "svc", 0, false);
        return ScopedValue.where(WorkflowContext.scopedValue(), ctx).call(body::call);
    }

    private static WorkflowInstance instance(String workflowId) {
        var now = Instant.now();
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("t")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }

    private static RunLease lease(WorkflowInstance instance) {
        return new RunLease(instance.id(), instance.leaseEpoch());
    }

    private static WorkflowEvent event(WorkflowInstance instance, int seq) {
        return new WorkflowEvent(UUID.randomUUID(), instance.id(), seq, EventType.ACTIVITY_COMPLETED,
                "Steps.step" + seq, null, Instant.now());
    }

    private static WorkflowSignal signal(String workflowId) {
        return new WorkflowSignal(UUID.randomUUID(), null, workflowId, "go", null, false, Instant.now(), null, null);
    }
}
