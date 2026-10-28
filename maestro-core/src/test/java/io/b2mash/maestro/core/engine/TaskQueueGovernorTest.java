package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
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

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TaskQueueLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskQueueLimits(1, 0));
    }
}
