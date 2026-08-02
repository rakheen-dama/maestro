package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two branches of one {@code parallel()} fork that both park must both park.
 *
 * <p>Before the fix, {@code InstanceStatusWriter.write} was an unguarded,
 * un-retried read-modify-write of the single instance row, and it is the sole
 * status writer for both park paths ({@code awaitSignal} → {@code
 * WAITING_SIGNAL}, {@code sleep} → {@code WAITING_TIMER}). When two branch
 * threads' reads both landed before either write, the loser's CAS found a
 * bumped version and threw {@code OptimisticLockException} out of
 * {@code awaitSignal()}/{@code sleep()} into workflow code; {@code parallel()}
 * rethrew it to the author, {@code executeWorkflow}'s {@code catch (Exception)}
 * recorded the workflow {@code FAILED} and ran its compensations.
 *
 * <p>That is BUG7's exact harm — "a persistence conflict is not a workflow
 * outcome" — arriving through the one status-writing call site that had neither
 * a retry nor a stand-down. {@code FAILED} is not {@code isActive()}, so the
 * recovery poller never heals it, and for a saga {@code retryWorkflow} refuses
 * (Issue 16): the damage is durable.
 *
 * <p>Two traps these fixtures exist to avoid:
 * <ul>
 *   <li>{@code parallel()} runs a <b>single-task list inline on the calling
 *       thread</b> — a one-branch fixture never forks and passes against broken
 *       code. Every fixture here uses two tasks and <b>both</b> park.</li>
 *   <li>A barrier on the <b>read</b> is not enough: the second read can still be
 *       serialised after the first write. The barrier must sit <b>between</b>
 *       the read and the write, i.e. inside an overridden {@code updateInstance}
 *       before delegating to {@code super}.</li>
 * </ul>
 *
 * <h2>What the workflow-level pins do NOT establish</h2>
 * <p>They pin the <em>outcome</em>: no run may be recorded {@code FAILED} and no
 * compensation may fire because two branches parked at once. They do not pin
 * <em>how</em> the writer achieves it, and they cannot: standing down on every
 * conflict also lets the workflow complete normally, so a writer whose retry
 * re-read is stale — one whose status write therefore never lands under
 * contention — keeps all four of them green. Verified, not assumed: hoisting
 * {@code store.getInstance} out of the retry loop leaves them all passing.
 *
 * <p>So the two properties {@code InstanceStatusWriter}'s shape actually rests
 * on get their own pins, both of which watch the store rather than the workflow:
 * <ul>
 *   <li>{@link #statusWrite_loserOfACollision_landsOnAFreshVersion()} — the
 *       loser's status must <em>land</em>, on the winner's version + 1. Dies if
 *       the read is hoisted.</li>
 *   <li>{@link #statusWrite_terminatedBetweenAttempts_propagatesWithoutOverwriting()}
 *       — a {@code TERMINATED} arriving between attempts must abort the loop.
 *       Dies if the terminal guard's verdict is re-used across attempts.</li>
 * </ul>
 */
@DisplayName("Concurrent branch parking must not collide on the instance row")
class ConcurrentBranchParkingTest {

    /** Short enough that a recheck-driven wake lands well inside the await bounds. */
    private static final Duration SHORT_RECHECK = Duration.ofMillis(200);

    private PayloadSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new PayloadSerializer(new ObjectMapper());
    }

    private WorkflowExecutor newExecutor(VersionedInMemoryStore store) {
        return new WorkflowExecutor(store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, SHORT_RECHECK);
    }

    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    // ── Deterministic: barrier between the read and the write ──────────

    @Test
    @DisplayName("both branches await a signal, both signals arrive, the workflow completes")
    void twoParkingBranches_signalPark_doNotRaceTheInstanceRow() throws Exception {
        var store = new BarrierStore();
        executor = newExecutor(store);

        var compensated = new AtomicBoolean();
        var done = new CountDownLatch(1);
        var wf = new TwoAwaitingBranches(done, compensated);
        var method = TwoAwaitingBranches.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("park-race-signal", "TwoAwaitingBranches",
                "default", "in", wf, method);

        // Both branches must be inside the barrier — i.e. both have read the
        // same version and neither has written — before either signal lands.
        await().atMost(Duration.ofSeconds(5)).until(store::bothBranchesAtBarrier);

        executor.deliverSignal("park-race-signal", "goA", "a");
        executor.deliverSignal("park-race-signal", "goB", "b");

        assertTrue(done.await(20, TimeUnit.SECONDS), "the fork must resolve");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var inst = store.getInstance("park-race-signal").orElseThrow();
            assertEquals(WorkflowStatus.COMPLETED, inst.status(),
                    "a version conflict between two parking branches is not a workflow "
                            + "outcome; output was: " + inst.output());
        });

        // Blast radius, not just the exception:
        assertFalse(compensated.get(),
                "no compensation may run — nothing in this workflow failed");
        assertEquals("a,b", wf.result,
                "both parked branches must resume and return their own signal payloads");
        assertEquals(2, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED).count(),
                "both branches must genuinely park and consume their own signal");
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED
                                || e.eventType() == EventType.COMPENSATION_STARTED).count(),
                "no failure or compensation may be recorded in the durable log");
        assertTrue(store.allSignals().stream().allMatch(s -> s.consumed()),
                "neither branch's signal may be left durably unconsumed");
    }

    @Test
    @DisplayName("both branches sleep, both timers fire, the workflow completes")
    void twoParkingBranches_timerPark_doNotRaceTheInstanceRow() throws Exception {
        var store = new BarrierStore();
        executor = newExecutor(store);

        var compensated = new AtomicBoolean();
        var done = new CountDownLatch(1);
        var wf = new TwoSleepingBranches(done, compensated);
        var method = TwoSleepingBranches.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("park-race-timer", "TwoSleepingBranches",
                "default", "in", wf, method);

        await().atMost(Duration.ofSeconds(5)).until(store::bothBranchesAtBarrier);

        // Both branches are parked on their own timer row; fire both.
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getDueTimers(Instant.now().plus(Duration.ofDays(1)), 10).size() == 2);
        for (var timer : store.getDueTimers(Instant.now().plus(Duration.ofDays(1)), 10)) {
            executor.fireTimer("park-race-timer", timer.timerId(), timer.id());
        }

        assertTrue(done.await(20, TimeUnit.SECONDS), "the fork must resolve");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var inst = store.getInstance("park-race-timer").orElseThrow();
            assertEquals(WorkflowStatus.COMPLETED, inst.status(),
                    "a version conflict between two sleeping branches is not a workflow "
                            + "outcome; output was: " + inst.output());
        });

        assertFalse(compensated.get(),
                "no compensation may run — nothing in this workflow failed");
        assertEquals("a,b", wf.result,
                "both slept branches must resume and return their own value");
        assertEquals(2, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.TIMER_FIRED).count(),
                "both branches must genuinely park on a timer and memoize its fire");
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED
                                || e.eventType() == EventType.COMPENSATION_STARTED).count(),
                "no failure or compensation may be recorded in the durable log");
    }

    // ── The natural race, unassisted ───────────────────────────────────

    @Test
    @DisplayName("the natural race: 15 consecutive forks whose two branches both park must all complete")
    void twoParkingBranches_naturalRace_everyRunCompletes() throws Exception {
        var store = new VersionedInMemoryStore();
        executor = newExecutor(store);

        for (var i = 0; i < 15; i++) {
            var workflowId = "park-race-nat-" + i;
            var compensated = new AtomicBoolean();
            var done = new CountDownLatch(1);
            var wf = new TwoAwaitingBranches(done, compensated);
            var method = TwoAwaitingBranches.class.getMethod("run", String.class);

            executor.startWorkflow(workflowId, "TwoAwaitingBranches", "default", "in", wf, method);

            await().atMost(Duration.ofSeconds(5)).until(() ->
                    store.getInstance(workflowId)
                            .map(inst -> inst.status() == WorkflowStatus.WAITING_SIGNAL
                                    || inst.status().isTerminal())
                            .orElse(false));

            executor.deliverSignal(workflowId, "goA", "a");
            executor.deliverSignal(workflowId, "goB", "b");

            assertTrue(done.await(20, TimeUnit.SECONDS),
                    "iteration " + i + ": the fork must resolve");
            var iteration = i;
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var inst = store.getInstance(workflowId).orElseThrow();
                assertEquals(WorkflowStatus.COMPLETED, inst.status(),
                        "iteration " + iteration + ": a version conflict between two parking "
                                + "branches is not a workflow outcome; output was: " + inst.output());
            });
            assertFalse(compensated.get(),
                    "iteration " + i + ": no compensation may run — nothing failed");
            assertEquals("a,b", wf.result,
                    "iteration " + i + ": both branches must resume with their own payload");
        }
    }

    // ── The retry loop's own boundaries ────────────────────────────────

    @Test
    @DisplayName("a transient conflict is retried against a fresh read and the status still lands")
    void statusWrite_retriesTransientConflict_andSucceeds() {
        var store = new VersionedInMemoryStore();
        store.createInstance(seedInstance("retry-transient"));

        // Two conflicts, then success — inside the bounded budget.
        store.failNextUpdates(2);
        InstanceStatusWriter.write(store, "retry-transient", WorkflowStatus.WAITING_SIGNAL);

        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("retry-transient").orElseThrow().status(),
                "the status must land once a retry wins the CAS");
        assertEquals(3, store.updateAttempts(),
                "two conflicts plus the winning write");
    }

    @Test
    @DisplayName("an unrelenting conflict stands down — it must never become a workflow outcome")
    void statusWrite_exhaustsBudget_standsDownWithoutThrowing() {
        var store = new VersionedInMemoryStore();
        store.createInstance(seedInstance("retry-exhausted"));

        store.failNextUpdates(1_000);
        // No throw: propagating here would escape the park call into workflow
        // code and land in executeWorkflow's catch (Exception) — FAILED plus
        // compensations for a workflow that never failed.
        InstanceStatusWriter.write(store, "retry-exhausted", WorkflowStatus.WAITING_SIGNAL);

        assertEquals(InstanceStatusWriter.STATUS_WRITE_ATTEMPTS, store.updateAttempts(),
                "the retry budget must be bounded, not unbounded");
        var inst = store.getInstance("retry-exhausted").orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, inst.status(),
                "the row keeps its previous status");
        assertTrue(inst.status().isActive(),
                "and that status is active — the workflow stays recoverable, which is why "
                        + "standing down on a lost status write is safe");
    }

    @Test
    @DisplayName("the loser of a collision re-reads and lands its status on the winner's fresh version")
    void statusWrite_loserOfACollision_landsOnAFreshVersion() throws Exception {
        var store = new BarrierStore();
        store.createInstance(seedInstance("collide-fresh"));

        // Two writers, barriered between their read and their CAS — exactly the
        // branch-thread collision, with the workflow machinery stripped away so
        // the ledger below is the only thing that can move.
        var writers = new ArrayList<Thread>();
        for (var i = 0; i < 2; i++) {
            writers.add(Thread.ofVirtual()
                    .name("maestro-workflow-Collide-collide-fresh-branch-" + i)
                    .start(() -> InstanceStatusWriter.write(
                            store, "collide-fresh", WorkflowStatus.WAITING_SIGNAL)));
        }
        for (var writer : writers) {
            writer.join(Duration.ofSeconds(15));
        }

        // THE point of the retry, and the one thing the workflow-level pins
        // cannot see: standing down also lets a workflow complete, so "it
        // completed" is consistent with the loser's status never landing. Only
        // the second committed write proves the retry re-read the winner's
        // version 1 and wrote 2. Hoist the read out of the loop and the loser
        // re-issues a stale version 1 on every attempt, conflicts five times,
        // stands down, and this ledger holds one entry instead of two.
        assertEquals(List.of("WAITING_SIGNAL@1", "WAITING_SIGNAL@2"), store.committedWrites(),
                "both writers' status must land — the loser must re-read the winner's "
                        + "version and write the next one, not re-issue its stale version");
        assertEquals(3, store.updateAttempts(),
                "two commits plus exactly one conflict — the retry must not re-conflict");

        var inst = store.getInstance("collide-fresh").orElseThrow();
        assertEquals(WorkflowStatus.WAITING_SIGNAL, inst.status());
        assertEquals(2, inst.version(),
                "the row must carry the retried write's version, not the winner's");
    }

    @Test
    @DisplayName("a TERMINATED landing between attempts aborts the retry — the guard is re-read each time")
    void statusWrite_terminatedBetweenAttempts_propagatesWithoutOverwriting() {
        var store = new TerminateOnFirstConflictStore();
        store.createInstance(seedInstance("terminated-mid-retry"));

        // The guard passed on attempt 1 (the row was RUNNING). The terminate
        // lands in the retry window. If the loop re-used that first read's
        // verdict, attempt 2 would happily CAS WAITING_SIGNAL over an operator's
        // TERMINATED — resurrecting a workflow someone asked to stop.
        assertThrows(WorkflowTerminatedException.class,
                () -> InstanceStatusWriter.write(
                        store, "terminated-mid-retry", WorkflowStatus.WAITING_SIGNAL),
                "the re-read must see TERMINATED and abandon the run; WorkflowTerminatedException "
                        + "is an Error so a workflow's own catch (Exception) cannot swallow it");

        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("terminated-mid-retry").orElseThrow().status(),
                "the operator's terminate must not be overwritten by the retry");
        assertEquals(1, store.casAttempts(),
                "the re-evaluated guard must abort before a second CAS is ever issued");
    }

    private static WorkflowInstance seedInstance(String workflowId) {
        var now = Instant.now();
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("TwoAwaitingBranches")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /**
     * Forks two branches, both of which park on their own signal. Two is the
     * minimum that actually forks: {@code parallel()} runs a one-task list
     * inline on the calling thread.
     */
    public static class TwoAwaitingBranches {
        private final CountDownLatch done;
        private final AtomicBoolean compensated;
        volatile String result = "unset";

        public TwoAwaitingBranches(CountDownLatch done, AtomicBoolean compensated) {
            this.done = done;
            this.compensated = compensated;
        }

        public String run(String input) {
            var ctx = WorkflowContext.current();
            ctx.addCompensation("reverseCharge", () -> compensated.set(true));
            List<Callable<String>> branches = List.of(
                    () -> WorkflowContext.current()
                            .awaitSignal("goA", String.class, Duration.ofSeconds(30)),
                    () -> WorkflowContext.current()
                            .awaitSignal("goB", String.class, Duration.ofSeconds(30)));
            try {
                result = String.join(",", ctx.parallel(branches));
                return result;
            } finally {
                done.countDown();
            }
        }
    }

    /** Forks two branches, both of which park on their own timer. */
    public static class TwoSleepingBranches {
        private final CountDownLatch done;
        private final AtomicBoolean compensated;
        volatile String result = "unset";

        public TwoSleepingBranches(CountDownLatch done, AtomicBoolean compensated) {
            this.done = done;
            this.compensated = compensated;
        }

        public String run(String input) {
            var ctx = WorkflowContext.current();
            ctx.addCompensation("reverseCharge", () -> compensated.set(true));
            List<Callable<String>> branches = List.of(
                    () -> {
                        WorkflowContext.current().sleep(Duration.ofMinutes(10));
                        return "a";
                    },
                    () -> {
                        WorkflowContext.current().sleep(Duration.ofMinutes(10));
                        return "b";
                    });
            try {
                result = String.join(",", ctx.parallel(branches));
                return result;
            } finally {
                done.countDown();
            }
        }
    }

    /**
     * Flips the row to {@code TERMINATED} in the window between the writer's
     * first CAS and its retry — a cross-node {@code terminateWorkflow} landing
     * mid-retry, which is the whole reason the terminal guard sits inside the
     * loop rather than in front of it.
     *
     * <p>The forced write bumps the version, so a retry that re-used the first
     * read would either conflict forever or, worse, match and overwrite the
     * terminate.
     */
    static class TerminateOnFirstConflictStore extends VersionedInMemoryStore {
        private final AtomicInteger casAttempts = new AtomicInteger();

        /**
         * Counted here rather than read from {@code updateAttempts()}, because
         * the injected conflict throws before {@code super} ever runs.
         *
         * @return how many compare-and-sets the writer issued
         */
        int casAttempts() {
            return casAttempts.get();
        }

        @Override
        public synchronized void updateInstance(WorkflowInstance instance) {
            if (casAttempts.incrementAndGet() == 1) {
                forceStatus(instance.workflowId(), WorkflowStatus.TERMINATED);
                throw new OptimisticLockException(
                        instance.workflowId(), instance.version() - 1, -1);
            }
            super.updateInstance(instance);
        }
    }

    /**
     * Forces the interleaving instead of hoping for it: the first
     * {@code updateInstance} on each of the two branch threads rendezvouses
     * BEFORE the CAS, so both branches have already read the same version and
     * neither may write until both are here.
     *
     * <p>The barrier must be here and not on the read — a read-side barrier
     * still lets the second read land after the first write, and the test then
     * passes against broken code.
     */
    static class BarrierStore extends VersionedInMemoryStore {
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final ThreadLocal<Boolean> used = ThreadLocal.withInitial(() -> false);
        private final CountDownLatch atBarrier = new CountDownLatch(2);
        private final List<String> committed = new CopyOnWriteArrayList<>();

        /** @return {@code true} once both branch threads have reached the pre-CAS barrier */
        boolean bothBranchesAtBarrier() {
            return atBarrier.getCount() == 0;
        }

        /**
         * Every write that actually reached the row, as {@code STATUS@version},
         * sorted. A conflicting write is never recorded — {@code super} throws
         * before this ledger is touched — so the ledger is what distinguishes
         * "the loser retried and landed" from "the loser stood down".
         *
         * @return the committed writes, sorted
         */
        List<String> committedWrites() {
            return committed.stream().sorted().toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            if (Thread.currentThread().getName().contains("-branch-") && !used.get()) {
                used.set(true);
                atBarrier.countDown();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // best effort — the assertion, not the barrier, is the oracle
                }
            }
            super.updateInstance(instance);
            committed.add(instance.status() + "@" + instance.version());
        }
    }
}
