package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
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
