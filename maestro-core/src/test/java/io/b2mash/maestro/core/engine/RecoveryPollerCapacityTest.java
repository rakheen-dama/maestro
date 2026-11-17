package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.Work;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueMatching;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6 / Issue 30 (3.1): the recovery poller passes first, and after a pass
 * that deferred for lack of task-queue capacity it passes again as soon as
 * capacity frees on this node — at most once per spacing, and never with
 * nothing free — instead of a full poll interval later.
 */
class RecoveryPollerCapacityTest {

    private static final Method AWAIT_RUN;

    static {
        try {
            AWAIT_RUN = AwaitThenWork.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private final List<WorkflowExecutor> nodes = new ArrayList<>();
    private final Map<WorkflowExecutor, String> names = new IdentityHashMap<>();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        nodes.forEach(WorkflowExecutor::shutdown);
    }

    @Test
    void aBacklogLargerThanTheBoundDrainsWithoutWaitingForThePollInterval() {
        work.releaseAll(); // activities return at once
        for (var i = 0; i < 6; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(1);
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
        assertEquals(6, valueWithin(() -> store.completed("wf-"), 6, Duration.ofSeconds(10)));
    }

    @Test
    void aParkedRunWokenDuringADrainWaitsBehindAtMostOneQueuesWorthOfRecoveredRuns() {
        var a = node(1);
        var proxy = work.proxy(a, store, serializer, "A");
        a.startWorkflow("parked", "AwaitThenWork", "orders", null, () -> new AwaitThenWork(proxy), AWAIT_RUN);
        assertEquals(1, valueWithin(a::parkedCount, 1, Duration.ofSeconds(5)));
        for (var i = 0; i < 5; i++) {
            seedRecoverable(store, "rec-" + i, "orders");
        }
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
        valueWithin(work::entries, 1, Duration.ofSeconds(5)); // rec-0 running, rec-1 queued
        a.deliverSignal("parked", "go", "now");
        valueWithin(a::parkedCount, 0, Duration.ofSeconds(5));
        // parked is in the slot line behind rec-1
        valueWithin(() -> a.taskQueueGovernor().workflowSlotWaiters("orders"), 2, Duration.ofSeconds(5));
        for (var i = 0; i < 6; i++) {
            work.release(1); // one activity at a time
        }
        var order = valueMatching(work::order, o -> o.contains("parked"), Duration.ofSeconds(10));
        assertEquals(2, order.indexOf("parked"), "order=" + order);
    }

    /**
     * D-3, no lost wake-up: capacity that frees while a pass is still running
     * (here: inside the pass's own {@code recoveryPass} callback, on the
     * poller's thread, after the pass deferred) is seen by the wait after it,
     * so the deferred instances are adopted at once rather than a poll
     * interval (1 h) later.
     */
    @Test
    void aReleaseDuringAPassIsSeenByTheWaitAfterIt() {
        var node = new AtomicReference<WorkflowExecutor>();
        var fired = new AtomicBoolean();
        var a = node(1, Duration.ofSeconds(2), new EngineObserver() {
            @Override
            public void recoveryPass(int scanned, int adopted) {
                if (scanned > 0 && fired.compareAndSet(false, true)) {
                    work.releaseAll(); // busy, then the queued rec-0, finish while the pass is returning
                    valueMatching(() -> node.get().recoveryHeadroom(Set.of("orders")), h -> h > 0,
                            Duration.ofSeconds(5));
                }
            }
        });
        node.set(a);
        var reg = registrations(a).get("WorkWorkflow");
        a.startWorkflow("busy", "WorkWorkflow", "orders", null, reg.workflowFactory(), workMethod());
        assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5))); // busy holds the only slot
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "rec-" + i, "orders"); // rec-0 queued on the ticket, rec-1 and rec-2 deferred
        }
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
        assertEquals(3, valueWithin(() -> store.completed("rec-"), 3, Duration.ofSeconds(10)));
    }

    /**
     * D-8: shutdown stops a poller that is waiting for capacity at once. The
     * activities are released as shutdown begins, so the drain is short; a
     * poller that did not leave its wait would hold shutdown to its 10 s
     * deadline.
     */
    @Test
    void stopWhileWaitingForCapacityEndsTheLoopPromptly() throws Exception {
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(1, Duration.ofSeconds(10));
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofHours(1));
        assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
        assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5))); // one running, one queued, one deferred
        var stopped = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            a.shutdown();
            stopped.countDown();
        });
        work.releaseAll(); // the running run finishes; the queued one stands down
        assertTrue(stopped.await(3, SECONDS), "shutdown's poller stop returned");
        assertFalse(a.isRecoveryPollerRunning());
    }

    /**
     * Coordinator ruling: a stop ends the poller's waits without the
     * interrupt — while it waits for capacity (3 seeded, one deferred) and
     * while it waits for its next full pass (nothing seeded).
     */
    @ParameterizedTest(name = "seeded={0}")
    @ValueSource(ints = {3, 0})
    void aStopEndsEveryWaitWithoutAnInterrupt(int seeded) throws Exception {
        for (var i = 0; i < seeded; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(1);
        var poller = new RecoveryPoller(a, registrations(a), SERVICE, Duration.ofHours(1),
                EngineObserver.NOOP, a.taskQueueGovernor().capacitySignal(), Duration.ofHours(1));
        poller.interruptOnStop = false;
        poller.start();
        try {
            assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
            poller.requestStop();
            assertTrue(poller.awaitStopped(Duration.ofSeconds(5)), "the loop left its wait on the stop alone");
        } finally {
            poller.interruptOnStop = true;
            poller.stop();
        }
    }

    /**
     * D-3. Phase 1: with nothing free (one run holds the slot, one recovered
     * run holds the ticket and waits), 500 capacity signals over about a
     * second make no statement. Phase 2: while a backlog drains through runs
     * that finish at once, capacity passes start at most once per spacing.
     * Phase 1 drives the signal directly (the brief's fallback form), which
     * is deterministic where a park churn is not.
     */
    @Test
    void capacityPassesAreSpacedAndFreeWhenNothingIsFree() throws Exception {
        var spacing = Duration.ofMillis(100);
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "rec-" + i, "orders");
        }
        var observer = new RecordingEngineObserver();
        var a = node(1, Duration.ofSeconds(2), observer);
        store.headroom = () -> (int) Math.min(Integer.MAX_VALUE, a.recoveryHeadroom(Set.of("orders")));
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), spacing);
        assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
        assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5)));
        assertEquals(0, valueWithin(() -> a.recoveryHeadroom(Set.of("orders")), 0L, Duration.ofSeconds(5)));

        // Phase 1: nothing free.
        var signal = a.taskQueueGovernor().capacitySignal();
        var churnEnd = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (var i = 0; i < 500 || System.nanoTime() < churnEnd; i++) {
            signal.signal();
            LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        }
        assertAll(
                () -> assertEquals(1, store.pageReads(), "a wake with no headroom read nothing"),
                () -> assertEquals(0, store.pageReadsWithZeroHeadroom()));

        // Phase 2: a drain.
        for (var i = 0; i < 40; i++) {
            seedRecoverable(store, "flow-%02d".formatted(i), "orders");
        }
        var passesBefore = observer.recoveryPasses().size();
        var start = System.nanoTime();
        work.releaseAll();
        var drained = valueMatching(() -> store.completed("flow-"), n -> n >= 20, Duration.ofSeconds(20));
        var elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        var passes = observer.recoveryPasses().size() - passesBefore;
        assertTrue(drained >= 20, "the backlog drains, drained=" + drained);
        assertTrue(passes <= 1 + elapsedMs / spacing.toMillis() + 1,
                "at most one capacity pass per spacing: passes=" + passes + " in " + elapsedMs + " ms");
    }

    /**
     * Carry c1: capacity passes resume per queue. Queue {@code a}'s 60 rows
     * come first and stay deferred (its runs never finish); queue {@code b}'s
     * 20 rows, after them, finish at once. Each capacity pass for {@code b}
     * starts at {@code b}'s own first deferred row, so no capacity pass
     * re-reads {@code a}'s backlog — the reads for draining {@code b} stay
     * linear in {@code b}'s rows, not in {@code a}'s.
     */
    @Test
    void aQueueThatDrainsIsNotReReadBehindAQueueThatIsFull() {
        for (var i = 0; i < 60; i++) {
            seedRecoverable(store, "a-%02d".formatted(i), "a");
        }
        for (var i = 0; i < 20; i++) {
            seedRecoverable(store, "b-%02d".formatted(i), "b");
        }
        work.completeAtOnceFor(id -> id.startsWith("b-"));
        var lastA = RecoveryCursor.of(store.getInstance("a-59").orElseThrow());
        var a = node(1);
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
        assertEquals(20, valueWithin(() -> store.completed("b-"), 20, Duration.ofSeconds(20)), "b drained");
        var capacityReads = store.pageReads.subList(1, store.pageReads.size());
        var reReadA = capacityReads.stream()
                .filter(r -> r.after() == null || r.after().startedAt().isBefore(lastA.startedAt()))
                .count();
        assertEquals(0, reReadA, "capacity page reads that re-read queue a's deferred rows, of "
                + capacityReads.size());
    }

    /**
     * Carry c3: a capacity wake that finds no headroom waits out one spacing
     * before it waits on the signal again, so a churn of signals with nothing
     * free costs at most one headroom check per spacing.
     */
    @Test
    void wakesWithNoHeadroomAreCheckedAtMostOncePerSpacing() throws Exception {
        var spacing = Duration.ofMillis(100);
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "rec-" + i, "orders");
        }
        var a = node(1);
        var poller = new RecoveryPoller(a, registrations(a), SERVICE, Duration.ofHours(1),
                EngineObserver.NOOP, a.taskQueueGovernor().capacitySignal(), spacing);
        poller.start();
        try {
            assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
            assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5)));
            assertEquals(0, valueWithin(() -> a.recoveryHeadroom(Set.of("orders")), 0L, Duration.ofSeconds(5)));
            var signal = a.taskQueueGovernor().capacitySignal();
            var start = System.nanoTime();
            var churnEnd = start + Duration.ofSeconds(1).toNanos();
            for (var i = 0; i < 500 || System.nanoTime() < churnEnd; i++) {
                signal.signal();
                LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
            }
            var elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            var checks = poller.headroomChecks.get();
            assertTrue(checks <= elapsedMs / spacing.toMillis() + 2,
                    "headroom checks=" + checks + " in " + elapsedMs + " ms at one per " + spacing.toMillis() + " ms");
        } finally {
            poller.stop();
        }
    }

    @Test
    void thePollersFirstPassRunsAtOnce() {
        var a = node(1);
        a.startRecoveryPoller(Map.of(), Duration.ofHours(1), Duration.ofMillis(20));
        assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
    }

    // ── fixtures ──────────────────────────────────────────────────────

    private WorkflowExecutor node(int c) {
        return node(c, Duration.ofSeconds(2));
    }

    private WorkflowExecutor node(int c, Duration shutdownTimeout) {
        return node(c, shutdownTimeout, EngineObserver.NOOP);
    }

    private WorkflowExecutor node(int c, Duration shutdownTimeout, EngineObserver observer) {
        var executor = WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .observer(observer)
                .shutdownTimeout(shutdownTimeout)
                .defaultTaskQueueLimits(new TaskQueueLimits(c, c))
                .build();
        names.put(executor, String.valueOf((char) ('A' + nodes.size())));
        nodes.add(executor);
        return executor;
    }

    private Map<String, WorkflowRegistration> registrations(WorkflowExecutor executor) {
        var proxy = work.proxy(executor, store, serializer, names.get(executor));
        return Map.of("WorkWorkflow", new WorkflowRegistration("WorkWorkflow", "orders",
                () -> new WorkWorkflow(proxy), workMethod()));
    }

    /** Parks on a signal, then calls the blocking activity. */
    @DurableWorkflow(name = "AwaitThenWork")
    public static final class AwaitThenWork {
        private final Work work;

        AwaitThenWork(Work work) {
            this.work = work;
        }

        /** @return the signal payload plus the activity's result */
        @WorkflowMethod
        public String run() {
            var go = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
            return go + work.work(WorkflowContext.current().workflowId());
        }
    }
}
