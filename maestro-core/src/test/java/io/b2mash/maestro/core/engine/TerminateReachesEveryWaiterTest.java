package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminating a workflow abandons every waiter it has parked on this node —
 * several {@code parallel()} branches at once, signal waits and a timer — and
 * no waiter of any other workflow, including one whose id extends the
 * terminated one's.
 */
class TerminateReachesEveryWaiterTest {

    /** What each branch of the terminated run saw: branch name → outcome. */
    private static final Map<String, String> BRANCH_OUTCOMES = new ConcurrentHashMap<>();

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        BRANCH_OUTCOMES.clear();
    }

    @Test
    void aTerminateAbandonsEveryParkedBranchOfItsWorkflow_andNoWaiterOfAnother() throws Exception {
        var store = new VersionedInMemoryStore();
        var observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(store, serializer, "svc")
                .observer(observer)
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        executor.startWorkflow("fan", "FanOut", "default", null, FanOut::new, FanOut.class.getMethod("run"));
        // Bystanders: one awaits the same signal name under an id that extends
        // "fan", one sleeps under another id.
        executor.startWorkflow("fan:1", "AwaitA", "default", null, AwaitA::new, AwaitA.class.getMethod("run"));
        executor.startWorkflow("other", "SleepLong", "default", null,
                SleepLong::new, SleepLong.class.getMethod("run"));
        awaitTrue(() -> executor.parkedCount() == 5, "three branches and two bystanders parked");

        executor.terminateWorkflow("fan", "operator");
        // Soft wait: a branch the terminate missed keeps the run alive, and the
        // assertions below then name it.
        waitUpTo5s(() -> !executor.isRunning("fan"));

        assertAll(
                () -> assertEquals(false, executor.isRunning("fan"), "the terminated run unwound"),
                () -> assertEquals(new TreeMap<>(Map.of(
                                "signal-a", "terminated", "signal-b", "terminated", "sleep", "terminated")),
                        new TreeMap<>(BRANCH_OUTCOMES), "every branch's park was abandoned by the terminate"),
                () -> assertEquals(2, executor.parkedCount(), "only the two bystanders are still parked"),
                () -> assertEquals(List.of("fan=TERMINATED"), observer.abandoned().stream()
                                .map(a -> a.info().workflowId() + "=" + a.reason())
                                .toList(),
                        "only the terminated workflow's run was abandoned"),
                () -> assertTrue(executor.isRunning("fan:1"), "fan:1 still runs"),
                () -> assertTrue(executor.isRunning("other"), "other still runs"),
                () -> assertEquals(WorkflowStatus.TERMINATED, store.getInstance("fan").orElseThrow().status()),
                () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("fan:1").orElseThrow().status()),
                () -> assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance("other").orElseThrow().status()));

        // The bystander is not poisoned: its own signal wakes it.
        executor.deliverSignal("fan:1", "a", "x");
        TestTerminalWait.awaitStatus(store, "fan:1", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        waitUpTo5s(condition);
        if (!condition.getAsBoolean()) {
            throw new AssertionError("not within 5 s: " + what);
        }
    }

    private static void waitUpTo5s(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Records the branch's outcome and rethrows: a control-flow Error must propagate. */
    private static <T> T recorded(String branch, Callable<T> body) throws Exception {
        try {
            var value = body.call();
            BRANCH_OUTCOMES.put(branch, "returned");
            return value;
        } catch (WorkflowTerminatedException e) {
            BRANCH_OUTCOMES.put(branch, "terminated");
            throw e;
        }
    }

    @DurableWorkflow(name = "FanOut")
    public static class FanOut {
        @WorkflowMethod
        public List<String> run() {
            var wf = WorkflowContext.current();
            List<Callable<String>> branches = List.of(
                    () -> recorded("signal-a", () -> wf.awaitSignal("a", String.class, Duration.ofHours(1))),
                    () -> recorded("signal-b", () -> wf.awaitSignal("b", String.class, Duration.ofHours(1))),
                    () -> recorded("sleep", () -> {
                        wf.sleep(Duration.ofHours(1));
                        return "slept";
                    }));
            return wf.parallel(branches);
        }
    }

    @DurableWorkflow(name = "AwaitA")
    public static class AwaitA {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("a", String.class, Duration.ofHours(1));
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
