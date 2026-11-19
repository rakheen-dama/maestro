package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two workflows on one node never share a parking slot, whatever their ids
 * and signal names contain.
 *
 * <p>A parking key is {@code workflowId + ":signal:" + signalName} or
 * {@code workflowId + ":timer:" + timerId}. A signal name may contain
 * {@code ':'}, so two different workflows can spell the same string:
 * <ul>
 *   <li>workflow {@code order} awaiting signal {@code 1:signal:go} and
 *       workflow {@code order:signal:1} awaiting signal {@code go} both spell
 *       {@code order:signal:1:signal:go};</li>
 *   <li>workflow {@code w} awaiting signal {@code x:timer:sleep-1} and
 *       workflow {@code w:signal:x} in its first {@code sleep()} (timer
 *       {@code sleep-1}) both spell {@code w:signal:x:timer:sleep-1}.</li>
 * </ul>
 * The lot keyed its waiters by that string alone, so the second park found
 * the slot occupied, threw {@code IllegalStateException}, and its workflow
 * ended {@code FAILED}.
 */
class ParkKeyCollisionTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;
    private VersionedInMemoryStore store;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void newExecutor() {
        store = new VersionedInMemoryStore();
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void twoSignalAwaitsSpellingTheSameKey_bothParkAndEachIsWokenByItsOwnSignal() throws Exception {
        newExecutor();
        var method = AwaitNamed.class.getMethod("run");
        executor.startWorkflow("order", "AwaitNamed", "default", null,
                () -> new AwaitNamed("1:signal:go"), method);
        TestTerminalWait.awaitStatus(store, "order", WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        awaitTrue(() -> executor.parkedCount() == 1, "order parked");

        executor.startWorkflow("order:signal:1", "AwaitNamed", "default", null,
                () -> new AwaitNamed("go"), method);
        // order:signal:1 either parks (WAITING_SIGNAL) or fails at its park (FAILED)
        var second = settledStatus("order:signal:1");

        assertEquals(WorkflowStatus.WAITING_SIGNAL, second, "order:signal:1's park collided with order's");
        awaitTrue(() -> executor.parkedCount() == 2, "both parked");

        executor.deliverSignal("order:signal:1", "go", "b");
        TestTerminalWait.awaitStatus(store, "order:signal:1", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("order").orElseThrow().status(),
                "order:signal:1's signal leaves order parked");

        executor.deliverSignal("order", "1:signal:go", "a");
        TestTerminalWait.awaitStatus(store, "order", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
    }

    @Test
    void aSignalAwaitAndASleepSpellingTheSameKey_bothPark() throws Exception {
        newExecutor();
        executor.startWorkflow("w:signal:x", "SleepLong", "default", null,
                SleepLong::new, SleepLong.class.getMethod("run"));
        TestTerminalWait.awaitStatus(store, "w:signal:x", WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(5));
        awaitTrue(() -> executor.parkedCount() == 1, "the sleeper parked");
        var sleeper = store.getInstance("w:signal:x").orElseThrow();
        assertTrue(store.findTimer(sleeper.id(), "sleep-1").isPresent(),
                "precondition: the sleep's timer is sleep-1, so its key is w:signal:x:timer:sleep-1");

        executor.startWorkflow("w", "AwaitNamed", "default", null,
                () -> new AwaitNamed("x:timer:sleep-1"), AwaitNamed.class.getMethod("run"));
        var awaiter = settledStatus("w");

        assertAll(
                () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, awaiter, "w's park collided with the sleeper's"),
                () -> assertEquals(WorkflowStatus.WAITING_TIMER,
                        store.getInstance("w:signal:x").orElseThrow().status(), "the sleeper is still asleep"));
        awaitTrue(() -> executor.parkedCount() == 2, "both parked");
        executor.deliverSignal("w", "x:timer:sleep-1", "a");
        TestTerminalWait.awaitStatus(store, "w", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance("w:signal:x").orElseThrow().status(),
                "w's signal leaves the sleeper asleep");
    }

    /** Waits until the run has parked or ended, and returns the row's status. */
    private WorkflowStatus settledStatus(String workflowId) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var status = store.getInstance(workflowId).map(i -> i.status()).orElse(null);
            if (status == WorkflowStatus.WAITING_SIGNAL || status == WorkflowStatus.WAITING_TIMER
                    || (status != null && status.isTerminal())) {
                if (status.isTerminal() || executor.parkedCount() == 2) {
                    return status;
                }
            }
            Thread.sleep(10);
        }
        return store.getInstance(workflowId).orElseThrow().status();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("not within 5 s: " + what);
        }
    }

    @DurableWorkflow(name = "AwaitNamed")
    public static class AwaitNamed {
        private final String signalName;

        public AwaitNamed(String signalName) {
            this.signalName = signalName;
        }

        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal(signalName, String.class, Duration.ofHours(1));
        }
    }

    @DurableWorkflow(name = "SleepLong")
    public static class SleepLong {
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofHours(1));
            return "slept";
        }
    }
}
