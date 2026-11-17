package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.core.engine.TaskQueueGovernor.UNBOUNDED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8 / Issue 25: the per-node, per-task-queue slot accounting behind
 * {@code maestro.worker.task-queues[].concurrency} and
 * {@code activity-concurrency}.
 */
@DisplayName("TaskQueueGovernor slot accounting")
class TaskQueueGovernorTest {

    @Test
    void unconfiguredQueueIsUnbounded() {
        assertTrue(UNBOUNDED.availableWorkflowSlots("x").isEmpty());
        assertTrue(UNBOUNDED.availableActivitySlots("x").isEmpty());
        UNBOUNDED.acquireWorkflowSlot("x", "wf"); // returns immediately
        UNBOUNDED.acquireActivitySlot("x");
        UNBOUNDED.releaseActivitySlot("x");
        UNBOUNDED.releaseWorkflowSlot("wf");
    }

    @Test
    void releaseIsIdempotent() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 1)));
        g.acquireWorkflowSlot("q", "wf");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
        g.releaseWorkflowSlot("wf");
        g.releaseWorkflowSlot("wf");
        assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
    }

    @Test
    void reacquiringAHeldSlotDoesNotTakeASecondOne() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 1)));
        g.acquireWorkflowSlot("q", "wf");
        g.acquireWorkflowSlot("q", "wf");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
        g.releaseWorkflowSlot("wf");
        assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
    }

    @Test
    void parkedReleasesThenReacquires() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        g.acquireWorkflowSlot("q", "wf");
        var seenInside = g.parked("wf", () -> g.availableWorkflowSlots("q"));
        assertEquals(OptionalInt.of(1), seenInside);
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"));
    }

    @Test
    void parkedReacquiresAndRethrowsATimeout() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        g.acquireWorkflowSlot("q", "wf");
        assertThrows(TimeoutException.class, () -> g.parked("wf", () -> {
            throw new TimeoutException("chunk expired");
        }));
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"),
                "a chunk expiry is not the end of the run — the slot is taken back");
    }

    @Test
    void parkedDoesNotReacquireAfterAnError() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        g.acquireWorkflowSlot("q", "wf");
        assertThrows(ExecutorShutdownException.class, () -> g.parked("wf", () -> {
            throw new ExecutorShutdownException("node stopping");
        }));
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"),
                "a control-flow Error ends the run — the slot stays released");
        g.releaseWorkflowSlot("wf"); // the run's finally: a no-op
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
    }

    @Test
    void aParkedRunsSlotGoesToAWaitingRun() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        g.acquireWorkflowSlot("q", "a");
        var bAcquired = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            g.acquireWorkflowSlot("q", "b");
            bAcquired.complete(null);
        });
        g.parked("a", () -> {
            bAcquired.get(5, SECONDS); // b runs while a is parked
            g.releaseWorkflowSlot("b"); // b finishes
            return null;
        });
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"), "a holds its slot again");
    }

    /**
     * R49: two {@code parallel()} branches of one run ending a park at the same
     * instant must not both wait on the semaphore. The old check-then-acquire
     * let both see {@code held == false}; the loser then waited for a permit
     * its own run already held and — the winner finishing its branch without
     * parking again — waited forever, wedging the run (and, at concurrency 1,
     * the whole queue). The scratch probe's shape: 2000 simultaneous wakes,
     * neither branch parking again, each join bounded.
     */
    @Test
    void twoBranchesWakingAtOnceNeverWaitOnTheirOwnRunsSlot() throws Exception {
        var stuck = 0;
        for (var i = 0; i < 2000; i++) {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 10)));
            g.acquireWorkflowSlot("q", "w"); // the run starts holding the only slot
            var gate = new CountDownLatch(1);
            var parkedBoth = new CountDownLatch(2);
            Callable<Void> park = () -> {
                parkedBoth.countDown();
                gate.await();
                return null;
            };
            var t1 = Thread.ofVirtual().start(() -> parkQuietly(g, park));
            var t2 = Thread.ofVirtual().start(() -> parkQuietly(g, park));
            parkedBoth.await();
            gate.countDown(); // both branches wake at once; neither parks again
            t1.join(Duration.ofSeconds(1));
            t2.join(Duration.ofSeconds(1));
            if (t1.isAlive() || t2.isAlive()) {
                stuck++;
            }
            g.releaseWorkflowSlot("w"); // unwedges a stuck branch so the loop can go on
            t1.join();
            t2.join();
        }
        assertEquals(0, stuck, "iterations in which a branch waited forever for its own run's slot");
    }

    /**
     * R49, deterministic form: both branches of a run wake while another run
     * holds the queue's only slot, so both must wait. When that slot is freed
     * the run takes it once and <em>both</em> branches proceed — the sibling
     * must not stay queued on the semaphore behind its own run.
     */
    @Test
    void twoBranchesWaitingBehindAnotherRunBothProceedOnOneSlot() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 10)));
        var branches = parkTwoBranchesBehindAnotherRun(g, new ConcurrentLinkedQueue<>());

        g.releaseWorkflowSlot("other");
        branches[0].join(Duration.ofSeconds(5));
        branches[1].join(Duration.ofSeconds(5));
        var wedged = branches[0].isAlive() || branches[1].isAlive();
        g.releaseWorkflowSlot("w"); // cleanup either way
        assertFalse(wedged, "a branch stayed queued for a slot its own run already held");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
    }

    /**
     * R49: whichever branch waits — on the semaphore, or on its run's lock
     * behind the sibling that waits on the semaphore — an interrupt stands it
     * down with the shutdown signal, and neither takes a slot.
     */
    @Test
    void branchesInterruptedWhileWaitingBehindAnotherRunBecomeShutdownSignals() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 10)));
        var thrown = new ConcurrentLinkedQueue<Throwable>();
        var branches = parkTwoBranchesBehindAnotherRun(g, thrown);

        branches[0].interrupt();
        branches[1].interrupt();
        branches[0].join(5_000);
        branches[1].join(5_000);
        assertFalse(branches[0].isAlive() || branches[1].isAlive());
        assertEquals(2, thrown.size(), thrown.toString());
        for (var t : thrown) {
            assertInstanceOf(ExecutorShutdownException.class, t);
        }
        g.releaseWorkflowSlot("other");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"), "neither branch took a slot");
        g.releaseWorkflowSlot("w");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
    }

    /**
     * Run "w" holds the only slot; two of its branches park (giving it back),
     * run "other" takes it, then both branches wake and block reacquiring.
     * Returns the two branch threads, both blocked; what they throw goes to
     * {@code thrown}.
     */
    private static Thread[] parkTwoBranchesBehindAnotherRun(
            TaskQueueGovernor g, ConcurrentLinkedQueue<Throwable> thrown) throws Exception {
        g.acquireWorkflowSlot("q", "w");
        var gate = new CountDownLatch(1);
        var parkedBoth = new CountDownLatch(2);
        var wokeBoth = new CountDownLatch(2);
        Callable<Void> park = () -> {
            parkedBoth.countDown();
            gate.await();
            wokeBoth.countDown();
            return null;
        };
        Runnable branch = () -> {
            try {
                g.parked("w", park);
            } catch (Throwable t) {
                thrown.add(t);
            }
        };
        var t1 = Thread.ofVirtual().start(branch);
        var t2 = Thread.ofVirtual().start(branch);
        parkedBoth.await();
        g.acquireWorkflowSlot("q", "other"); // takes the slot "w" gave back
        gate.countDown();
        wokeBoth.await(); // both parks are over: all that is left is the reacquire
        await().atMost(5, SECONDS).until(() ->
                t1.getState() == Thread.State.WAITING && t2.getState() == Thread.State.WAITING);
        return new Thread[] {t1, t2};
    }

    private static void parkQuietly(TaskQueueGovernor g, Callable<Void> park) {
        try {
            g.parked("w", park);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void activitySlotsAreBounded() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(5, 2)));
        g.acquireActivitySlot("q");
        g.acquireActivitySlot("q");
        assertEquals(OptionalInt.of(0), g.availableActivitySlots("q"));
        g.releaseActivitySlot("q");
        assertEquals(OptionalInt.of(1), g.availableActivitySlots("q"));
        assertEquals(OptionalInt.of(5), g.availableWorkflowSlots("q"), "the two pools are independent");
    }

    @Test
    void acquireActivitySlotReportsWhetherItHadToWait() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        assertFalse(g.acquireActivitySlot("q"), "a free slot is taken without waiting");
        assertFalse(UNBOUNDED.acquireActivitySlot("q"), "an unbounded queue never waits");
        var waited = new CompletableFuture<Boolean>();
        var waiter = Thread.ofVirtual().start(() -> waited.complete(g.acquireActivitySlot("q")));
        await().atMost(5, SECONDS).until(() -> waiter.getState() == Thread.State.WAITING);
        g.releaseActivitySlot("q");
        assertTrue(waited.get(5, SECONDS), "the second caller had to wait for the slot");
    }

    @Test
    void anInterruptedWaitBecomesAShutdownSignal() throws Exception {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
        g.acquireWorkflowSlot("q", "holder");
        var thrown = new AtomicReference<Throwable>();
        var waiter = Thread.ofVirtual().start(() -> {
            try {
                g.acquireWorkflowSlot("q", "waiter");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        await().atMost(5, SECONDS).until(() -> waiter.getState() == Thread.State.WAITING);
        waiter.interrupt();
        waiter.join(5_000);
        var error = assertInstanceOf(ExecutorShutdownException.class, thrown.get());
        assertTrue(error.getMessage().contains("interrupted while waiting for a workflow slot on queue 'q'"),
                error.getMessage());
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"), "the waiter took nothing");
    }

    // ── default bound for queues not configured (maestro.worker.default-*) ──

    @Test
    void unconfiguredQueueIsBoundedByDefaults() throws Exception {
        var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(2, 1));
        g.acquireWorkflowSlot("q", "a");
        g.acquireWorkflowSlot("q", "b");
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"));

        var thirdAcquired = new CompletableFuture<Void>();
        var third = Thread.ofVirtual().start(() -> {
            g.acquireWorkflowSlot("q", "c");
            thirdAcquired.complete(null);
        });
        await().atMost(5, SECONDS).until(() -> third.getState() == Thread.State.WAITING);
        assertFalse(thirdAcquired.isDone(), "the third run waits while both default slots are held");

        g.releaseWorkflowSlot("a");
        thirdAcquired.get(5, SECONDS);
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"), "c took the slot a gave back");

        assertFalse(g.acquireActivitySlot("q"));
        assertEquals(OptionalInt.of(0), g.availableActivitySlots("q"), "the activity pool is sized from defaults too");
        g.releaseActivitySlot("q");
        assertEquals(OptionalInt.of(1), g.availableActivitySlots("q"));
    }

    @Test
    void twoUnconfiguredQueuesHaveSeparatePools() {
        var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(2, 1));
        g.acquireWorkflowSlot("q1", "a");
        g.acquireWorkflowSlot("q1", "b");
        g.acquireActivitySlot("q1");
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q1"));
        assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q2"));
        assertEquals(OptionalInt.of(1), g.availableActivitySlots("q2"));
    }

    @Test
    void configuredQueueIgnoresDefaults() {
        var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(5, 5)), new TaskQueueLimits(2, 1));
        assertEquals(OptionalInt.of(5), g.availableWorkflowSlots("q"));
        assertEquals(OptionalInt.of(5), g.availableActivitySlots("q"));
        assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("other"));
    }

    @Test
    void nullDefaultsStayUnbounded() {
        var g = new TaskQueueGovernor(Map.of(), null);
        assertTrue(g.availableWorkflowSlots("q").isEmpty());
        assertTrue(g.availableActivitySlots("q").isEmpty());
        assertTrue(new TaskQueueGovernor(Map.of()).availableWorkflowSlots("q").isEmpty(),
                "the one-argument constructor keeps unconfigured queues unbounded");
    }

    @Test
    void aDefaultBoundedRunGivesItsSlotBackWhileParked() throws Exception {
        var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(1, 1));
        g.acquireWorkflowSlot("q", "a");
        var bAcquired = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            g.acquireWorkflowSlot("q", "b");
            bAcquired.complete(null);
        });
        var seenInside = g.parked("a", () -> {
            bAcquired.get(5, SECONDS); // b runs on the slot a gave back
            var free = g.availableWorkflowSlots("q");
            g.releaseWorkflowSlot("b");
            return free;
        });
        assertEquals(OptionalInt.of(0), seenInside, "b held the default queue's only slot while a was parked");
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"), "a holds its slot again");
        g.releaseWorkflowSlot("a");
        assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
    }

    @Test
    void anInterruptedWaitOnADefaultBoundedQueueBecomesAShutdownSignal() throws Exception {
        var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(1, 1));
        g.acquireWorkflowSlot("q", "holder");
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"));
        var thrown = new AtomicReference<Throwable>();
        var waiter = Thread.ofVirtual().start(() -> {
            try {
                g.acquireWorkflowSlot("q", "waiter");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        await().atMost(5, SECONDS).until(() -> waiter.getState() == Thread.State.WAITING);
        waiter.interrupt();
        waiter.join(5_000);
        assertInstanceOf(ExecutorShutdownException.class, thrown.get());
        assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"), "the waiter took nothing");
    }

    /**
     * Issue 29 / WP5 D-3: {@link TaskQueueGovernor#shutdown} lends one permit
     * to every pool. A waiter that wakes and finds the governor shut down
     * hands the permit straight back and throws the shutdown signal, which
     * wakes the next waiter — nobody is interrupted. After the drain the
     * loans are reclaimed and the counts are exact again.
     */
    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
        private final List<Runnable> holderReleases = new CopyOnWriteArrayList<>();
        private final List<Thread> otherThreads = new CopyOnWriteArrayList<>();

        /** Releases the holders and unsticks every waiter, so a red run leaks no thread. */
        @AfterEach
        void releaseStuckThreads() throws InterruptedException {
            holderReleases.forEach(Runnable::run);
            for (var t : otherThreads) {
                t.interrupt();
                t.join(Duration.ofSeconds(5));
            }
            for (var w : waiters) {
                w.thread().interrupt();
                w.thread().join(Duration.ofSeconds(5));
            }
        }

        @Test
        void aWorkflowSlotWaiterLeavesWhenTheGovernorShutsDown() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
            g.acquireWorkflowSlot("q", "holder");
            holderReleases.add(() -> g.releaseWorkflowSlot("holder"));
            var w = waitFor("w", () -> g.acquireWorkflowSlot("q", "w"));
            awaitParked(w);
            g.shutdown();
            assertTrue(w.left().await(2, SECONDS), "the waiter left");
            assertInstanceOf(ExecutorShutdownException.class, w.thrown());
            assertFalse(w.interruptFlag(), "no interrupt: its own lock release must not see one");
        }

        @Test
        void anActivitySlotWaiterLeavesWhenTheGovernorShutsDown() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
            g.acquireActivitySlot("q");
            holderReleases.add(() -> g.releaseActivitySlot("q"));
            var w = waitFor("w", () -> g.acquireActivitySlot("q"));
            awaitParked(w);
            g.shutdown();
            assertTrue(w.left().await(2, SECONDS), "the waiter left");
            assertInstanceOf(ExecutorShutdownException.class, w.thrown());
            assertFalse(w.interruptFlag(), "no interrupt: its own lock release must not see one");
        }

        @Test
        void everyQueuedWaiterLeavesAndNoneIsInterrupted() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
            g.acquireWorkflowSlot("q", "holder");
            g.acquireActivitySlot("q");
            holderReleases.add(() -> g.releaseWorkflowSlot("holder"));
            holderReleases.add(() -> g.releaseActivitySlot("q"));
            var queued = new ArrayList<Waiter>();
            for (var i = 0; i < 5; i++) {
                var id = "w" + i;
                var w = waitFor(id, () -> g.acquireWorkflowSlot("q", id));
                awaitParked(w);
                queued.add(w);
            }
            for (var i = 0; i < 5; i++) {
                var w = waitFor("a" + i, () -> g.acquireActivitySlot("q"));
                awaitParked(w);
                queued.add(w);
            }

            g.shutdown();

            for (var w : queued) {
                assertTrue(w.left().await(2, SECONDS), "the waiter " + w.thread().getName() + " left");
            }
            for (var w : queued) {
                assertInstanceOf(ExecutorShutdownException.class, w.thrown(), w.thread().getName());
                assertFalse(w.interruptFlag(), w.thread().getName() + " was not interrupted");
            }
        }

        @Test
        void twoBranchesOfOneRunWaitingBehindAnotherRunBothLeave() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 10)));
            var thrown = new ConcurrentLinkedQueue<Throwable>();
            var branches = parkTwoBranchesBehindAnotherRun(g, thrown);
            holderReleases.add(() -> g.releaseWorkflowSlot("other"));
            otherThreads.addAll(List.of(branches));
            g.shutdown();
            assertTrue(branches[0].join(Duration.ofSeconds(2)) && branches[1].join(Duration.ofSeconds(2)),
                    "both branches left");
            assertEquals(2, thrown.size(), thrown.toString());
            thrown.forEach(t -> assertInstanceOf(ExecutorShutdownException.class, t));
        }

        @Test
        void aWaitThatStartsAfterShutdownThrowsWithoutWaiting() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
            g.acquireWorkflowSlot("q", "holder");
            holderReleases.add(() -> g.releaseWorkflowSlot("holder"));
            g.shutdown();
            var late = waitFor("late", () -> g.acquireWorkflowSlot("q", "late"));
            assertTrue(late.left().await(1, SECONDS), "the waiter left");
            assertInstanceOf(ExecutorShutdownException.class, late.thrown());
        }

        /** MD-3: in-flight work drains, so a free activity slot is still admitted. */
        @Test
        void aFreeActivitySlotIsStillAdmittedAfterShutdown() {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 2)));
            g.shutdown();
            assertFalse(g.acquireActivitySlot("q"), "a free slot is taken without waiting");
            g.releaseActivitySlot("q");
        }

        @Test
        void noPermitIsLostOrGainedOnceTheLoanIsReclaimed() throws Exception {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 2)));
            g.acquireWorkflowSlot("q", "h1");
            g.acquireWorkflowSlot("q", "h2");
            g.acquireActivitySlot("q");
            g.acquireActivitySlot("q");
            var queued = new ArrayList<Waiter>();
            for (var i = 0; i < 3; i++) {
                var id = "w" + i;
                var w = waitFor(id, () -> g.acquireWorkflowSlot("q", id));
                awaitParked(w);
                queued.add(w);
            }
            for (var i = 0; i < 3; i++) {
                var w = waitFor("a" + i, () -> g.acquireActivitySlot("q"));
                awaitParked(w);
                queued.add(w);
            }

            g.shutdown();
            for (var w : queued) {
                assertTrue(w.left().await(2, SECONDS), "the waiter " + w.thread().getName() + " left");
            }
            g.reclaimShutdownPermits();
            g.releaseWorkflowSlot("h1");
            g.releaseWorkflowSlot("h2");
            g.releaseActivitySlot("q");
            g.releaseActivitySlot("q");

            assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
            assertEquals(OptionalInt.of(2), g.availableActivitySlots("q"));
        }

        /**
         * Review c1: a reclaim that runs while the poison permit is still
         * travelling — a deadline-hit drain, or here at once after
         * {@code shutdown()}, before the woken head waiter has run — must not
         * take the permit from under the waiters still queued.
         */
        @Test
        void waitersStillQueuedWhenTheReclaimRunsAllLeave() throws Exception {
            for (var round = 0; round < 20; round++) {
                var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
                g.acquireWorkflowSlot("q", "holder");
                holderReleases.add(() -> g.releaseWorkflowSlot("holder"));
                var queued = new ArrayList<Waiter>();
                for (var i = 0; i < 3; i++) {
                    var id = "r" + round + "-w" + i;
                    var w = waitFor(id, () -> g.acquireWorkflowSlot("q", id));
                    awaitParked(w);
                    queued.add(w);
                }

                g.shutdown();
                g.reclaimShutdownPermits();

                for (var w : queued) {
                    assertTrue(w.left().await(2, SECONDS),
                            "round " + round + ": the waiter " + w.thread().getName() + " left");
                    assertInstanceOf(ExecutorShutdownException.class, w.thrown(), w.thread().getName());
                }
            }
        }

        @Test
        void shutdownIsIdempotent() {
            var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 2)));
            g.shutdown();
            g.shutdown();
            g.reclaimShutdownPermits();
            g.reclaimShutdownPermits();
            assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
            assertEquals(OptionalInt.of(2), g.availableActivitySlots("q"));
        }

        /** Restart-safety: {@code UNBOUNDED} is JVM-wide and shared by every executor without limits. */
        @Test
        void shuttingDownAnUnboundedGovernorChangesNothing() {
            TaskQueueGovernor.UNBOUNDED.shutdown();
            new TaskQueueGovernor(Map.of()).shutdown();
            assertFalse(TaskQueueGovernor.UNBOUNDED.isClosed(),
                    "the JVM-wide UNBOUNDED must stay usable by the next executor");
        }

        @Test
        void aDefaultQueueFirstNamedAfterShutdownRefusesWaits() throws Exception {
            var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(1, 1));
            g.shutdown();
            var w = waitFor("w", () -> g.acquireWorkflowSlot("late", "w"));
            assertTrue(w.left().await(1, SECONDS), "the waiter left");
            assertInstanceOf(ExecutorShutdownException.class, w.thrown());

            // The late queue's pools got no loan. Its free activity slot is still
            // admitted (MD-3); a wait for a second one must throw before it
            // touches the semaphore, because no permit will ever wake it.
            assertFalse(g.acquireActivitySlot("late"), "a free slot is taken without waiting");
            holderReleases.add(() -> g.releaseActivitySlot("late"));
            var a = waitFor("a", () -> g.acquireActivitySlot("late"));
            assertTrue(a.left().await(1, SECONDS), "the activity waiter left");
            assertInstanceOf(ExecutorShutdownException.class, a.thrown());
        }

        private Waiter waitFor(String name, Runnable acquire) {
            var left = new CountDownLatch(1);
            var thrown = new AtomicReference<@Nullable Throwable>();
            var flag = new AtomicBoolean();
            var thread = Thread.ofVirtual().name(name).unstarted(() -> {
                try {
                    acquire.run();
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    flag.set(Thread.currentThread().isInterrupted());
                    left.countDown();
                }
            });
            var waiter = new Waiter(thread, left, thrown, flag);
            waiters.add(waiter);
            thread.start();
            return waiter;
        }

        private static void awaitParked(Waiter waiter) {
            await().atMost(5, SECONDS).until(() -> waiter.thread().getState() == Thread.State.WAITING);
        }
    }

    /** A thread waiting for a slot: what it threw, and its interrupt flag once it left. */
    private record Waiter(Thread thread, CountDownLatch left,
                          AtomicReference<@Nullable Throwable> thrownRef, AtomicBoolean flag) {
        @Nullable Throwable thrown() {
            return thrownRef.get();
        }

        boolean interruptFlag() {
            return flag.get();
        }
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TaskQueueLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskQueueLimits(1, 0));
    }
}
