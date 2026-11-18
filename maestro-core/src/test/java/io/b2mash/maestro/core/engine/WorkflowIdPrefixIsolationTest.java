package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workflow ids are matched exactly in {@link ParkingLot}, never by prefix:
 * for workflows {@code order} and {@code order:1} on one node, ending or
 * terminating {@code order} must not reach {@code order:1}'s parks or permits.
 *
 * <p>Every parking key is {@code workflowId + ":" + …}, and the lot used to
 * match a workflow's keys by {@code startsWith(workflowId + ":")} — which
 * {@code order:1:signal:go} also does.
 */
class WorkflowIdPrefixIsolationTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void terminatingOrder_leavesOrderColon1ParkedAndWakeable() throws Exception {
        var store = new VersionedInMemoryStore();
        var observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .observer(observer)
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        var method = AwaitLong.class.getMethod("run");
        executor.startWorkflow("order", "AwaitLong", "default", null, AwaitLong::new, method);
        executor.startWorkflow("order:1", "AwaitLong", "default", null, AwaitLong::new, method);
        TestTerminalWait.awaitStatus(store, "order", WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        TestTerminalWait.awaitStatus(store, "order:1", WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        awaitTrue(() -> executor.parkedCount() == 2, "both runs parked");

        executor.terminateWorkflow("order", "operator");
        awaitTrue(() -> !executor.isRunning("order"), "order's run unwound");
        // Give order:1's run the same time to unwind, if the terminate reached it.
        awaitTrue(() -> !executor.isRunning("order:1"), Duration.ofSeconds(1));
        var order1RunningAfterTerminate = executor.isRunning("order:1");

        executor.deliverSignal("order:1", "go", "x");
        var order1Status = statusWithin(store, "order:1", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));

        assertAll(
                () -> assertEquals(List.of(), observer.abandoned().stream()
                                .filter(a -> a.info().workflowId().equals("order:1"))
                                .map(a -> a.info().workflowId() + "=" + a.reason())
                                .toList(),
                        "terminating order must not abandon order:1's run"),
                () -> assertEquals(true, order1RunningAfterTerminate, "order:1 still running after order's terminate"),
                () -> assertEquals(WorkflowStatus.COMPLETED, order1Status, "order:1 woken by its own signal"),
                () -> assertEquals(WorkflowStatus.TERMINATED, store.getInstance("order").orElseThrow().status()));
    }

    @Test
    void theRunEndOfOrder_leavesOrderColon1sPermit() {
        var lot = new ParkingLot(id -> true);
        lot.wake("order:1", "order:1:signal:go");
        lot.clearPending("order");
        assertEquals(1, lot.pendingWakeCount(), "order's run end removed order:1's permit");
    }

    @Test
    void terminatingOrder_leavesOrderColon1sPermit() {
        var lot = new ParkingLot(id -> true);
        lot.wake("order:1", "order:1:signal:go");
        lot.abandonWorkflow("order", null);
        assertEquals(1, lot.pendingWakeCount(), "order's terminate removed order:1's permit");
    }

    // ── Ids containing ':' at various positions ────────────────────────

    static Stream<Arguments> idPairs() {
        return Stream.of(
                Arguments.of("order", "order:1"),
                Arguments.of("order:1", "order"),
                Arguments.of("a", "a:b:c"),
                Arguments.of("a:b", "a:b:c"),
                Arguments.of("a:b:c", "a:b"),
                Arguments.of(":x", ":x:y"),
                Arguments.of("x:", "x::"),
                Arguments.of("x:signal", "x"));
    }

    /**
     * Terminating {@code victim} abandons only its own waiter and permit;
     * {@code bystander} stays parked, unpoisoned (it can park on another key)
     * and wakeable, and keeps its permit.
     */
    @ParameterizedTest(name = "terminate \"{0}\" leaves \"{1}\"")
    @MethodSource("idPairs")
    void aTerminateReachesOnlyTheWorkflowItNames(String victim, String bystander) throws Exception {
        var lot = new ParkingLot(id -> true);
        var victimOutcome = parkInBackground(lot, victim, victim + ":signal:go");
        var bystanderOutcome = parkInBackground(lot, bystander, bystander + ":signal:go");
        awaitTrue(() -> lot.isParked(victim + ":signal:go") && lot.isParked(bystander + ":signal:go"),
                "both parked");
        lot.wake(bystander, bystander + ":timer:sleep-1");
        assertEquals(1, lot.pendingWakeCount(), "precondition: the bystander holds one permit");

        lot.abandonWorkflow(victim, "operator");

        assertEquals(WorkflowTerminatedException.class,
                victimOutcome.get(5, TimeUnit.SECONDS).getClass(), "the victim's park is abandoned");
        assertTrue(lot.isParked(bystander + ":signal:go"), "the bystander is still parked");
        assertFalse(bystanderOutcome.isDone(), "the bystander's park is not abandoned");
        assertEquals(1, lot.pendingWakeCount(), "the bystander keeps its permit");
        assertNull(lot.parkWithTimeout(bystander, bystander + ":timer:sleep-1", Duration.ofSeconds(5)),
                "the bystander is not poisoned: it parks again and consumes its permit");

        lot.wake(bystander, bystander + ":signal:go");
        assertEquals("woken", bystanderOutcome.get(5, TimeUnit.SECONDS), "the bystander is wakeable");
        assertThrows(WorkflowTerminatedException.class,
                () -> lot.parkWithTimeout(victim, victim + ":signal:later", Duration.ofSeconds(5)),
                "the victim stays poisoned until its run clears it");
    }

    @ParameterizedTest(name = "run end of \"{0}\" leaves \"{1}\"")
    @MethodSource("idPairs")
    void aRunEndClearsOnlyTheWorkflowItNames(String ended, String bystander) {
        var lot = new ParkingLot(id -> true);
        lot.wake(ended, ended + ":signal:go");
        lot.wake(bystander, bystander + ":signal:go");
        lot.wake(bystander, bystander + ":timer:sleep-1");
        lot.clearPending(ended);
        assertEquals(2, lot.pendingWakeCount(), "only the ended workflow's permit is removed");
    }

    private static CompletableFuture<Object> parkInBackground(ParkingLot lot, String workflowId, String key) {
        var outcome = new CompletableFuture<Object>();
        Thread.ofVirtual().start(() -> {
            try {
                lot.parkWithTimeout(workflowId, key, Duration.ofSeconds(30));
                outcome.complete("woken");
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        return outcome;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        awaitTrue(condition, Duration.ofSeconds(5));
        if (!condition.getAsBoolean()) {
            throw new AssertionError("not within 5 s: " + what);
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, Duration within)
            throws InterruptedException {
        var deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static @Nullable WorkflowStatus statusWithin(VersionedInMemoryStore store, String id,
                                                         WorkflowStatus wanted, Duration within)
            throws InterruptedException {
        var deadline = System.nanoTime() + within.toNanos();
        while (true) {
            var last = store.getInstance(id).map(i -> i.status()).orElse(null);
            if (last == wanted || System.nanoTime() >= deadline) {
                return last;
            }
            Thread.sleep(20);
        }
    }

    @DurableWorkflow(name = "AwaitLong")
    public static class AwaitLong {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }
}
