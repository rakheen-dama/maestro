package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two branches of one {@code parallel()} fork awaiting the <em>same</em>
 * signal name must never both consume one signal row.
 *
 * <p>Before the atomic consume, each branch read the unconsumed rows, took
 * {@code getFirst()}, appended its {@code SIGNAL_RECEIVED} memo and only then
 * flipped the row — proceeding even when the flip lost. Two branches whose
 * reads both landed before either flip therefore both returned the first
 * row's payload: one signal satisfied two awaits, and a second signal sat
 * unconsumed.
 *
 * <p>The store barriers the first two reads <em>after</em> they have read, so
 * both branches are guaranteed to have chosen the same first row before
 * either consumes it — without the barrier the second read is usually
 * serialised after the first consume and the race never shows.
 */
@DisplayName("Parallel branches awaiting one signal name consume distinct rows")
class ParallelBranchSameSignalTest {

    private static final String SIGNAL = "go";

    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private WorkflowExecutor newExecutor(VersionedInMemoryStore store) {
        return new WorkflowExecutor(store, null, null, null, new PayloadSerializer(new ObjectMapper()),
                "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(200));
    }

    @Test
    @DisplayName("two rows, two racing branches — each branch gets a different payload")
    void twoRowsTwoBranchesDistinctPayloads() throws Exception {
        var store = new ReadBarrierStore();
        executor = newExecutor(store);
        executor.deliverSignal("same-signal-2", SIGNAL, "a");
        executor.deliverSignal("same-signal-2", SIGNAL, "b");

        var done = new CountDownLatch(1);
        var wf = new TwoBranchesOneName(done);
        var instanceId = executor.startWorkflow("same-signal-2", "TwoBranchesOneName", "default", "in",
                () -> wf, TwoBranchesOneName.class.getMethod("run", String.class));

        assertTrue(done.await(20, TimeUnit.SECONDS), "the fork must resolve");
        assertEquals(2, store.barrierPassed.get(), "both branches must have read the rows before either consumed");
        assertEquals(Set.of("a", "b"), Set.copyOf(wf.results),
                "one signal must never satisfy two awaits; results were " + wf.results);
        assertEquals(2, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED).count());
        assertTrue(store.allSignals().stream().allMatch(WorkflowSignal::consumed));
        await().atMost(Duration.ofSeconds(10)).until(() ->
                store.getInstance("same-signal-2").orElseThrow().status() == WorkflowStatus.COMPLETED);
    }

    @Test
    @DisplayName("one row, two racing branches — one consumes it, the other parks until a second signal")
    void oneRowTwoBranchesOneParks() throws Exception {
        var store = new ReadBarrierStore();
        executor = newExecutor(store);
        executor.deliverSignal("same-signal-1", SIGNAL, "a");

        var done = new CountDownLatch(1);
        var wf = new TwoBranchesOneName(done);
        var instanceId = executor.startWorkflow("same-signal-1", "TwoBranchesOneName", "default", "in",
                () -> wf, TwoBranchesOneName.class.getMethod("run", String.class));

        await().atMost(Duration.ofSeconds(10)).until(() -> store.getEvents(instanceId).stream()
                .anyMatch(e -> e.eventType() == EventType.TIMER_SCHEDULED));
        assertEquals(2, store.barrierPassed.get(), "both branches must have read the one row");
        assertEquals(1, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED).count(),
                "exactly one branch consumed the only row; the other parked");
        assertEquals(1, done.getCount(), "the fork must still be waiting on the parked branch");

        executor.deliverSignal("same-signal-1", SIGNAL, "b");
        assertTrue(done.await(20, TimeUnit.SECONDS), "the second signal must release the parked branch");
        assertEquals(Set.of("a", "b"), Set.copyOf(wf.results), "results were " + wf.results);
        assertTrue(store.allSignals().stream().allMatch(WorkflowSignal::consumed));
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** Forks two branches that both await the same signal name. */
    public static class TwoBranchesOneName {
        private final CountDownLatch done;
        volatile List<String> results = List.of();

        public TwoBranchesOneName(CountDownLatch done) {
            this.done = done;
        }

        public String run(String input) {
            var ctx = WorkflowContext.current();
            List<Callable<String>> branches = List.of(
                    () -> WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30)),
                    () -> WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30)));
            try {
                results = ctx.parallel(branches);
                return String.join(",", results);
            } finally {
                done.countDown();
            }
        }
    }

    /**
     * Holds the first two reads of {@value #SIGNAL} at a barrier after they
     * have read, so both branches choose the same first row.
     */
    static final class ReadBarrierStore extends VersionedInMemoryStore {
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger barrierPassed = new AtomicInteger();

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            var rows = super.getUnconsumedSignals(workflowId, signalName);
            if (SIGNAL.equals(signalName) && reads.incrementAndGet() <= 2) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    barrierPassed.incrementAndGet();
                } catch (Exception e) {
                    // A broken barrier only weakens the race; the assertions on
                    // barrierPassed report it.
                }
            }
            return rows;
        }
    }
}
