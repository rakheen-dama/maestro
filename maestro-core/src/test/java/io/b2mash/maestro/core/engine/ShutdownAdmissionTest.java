package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 1 (Issue 80, and the unguarded {@code retryWorkflow}
 * path): every launch is admitted, or refused, by the executor's lifecycle
 * registry before it takes an instance lock, so a launch that was already past
 * its admission when shutdown began is waited for, and one that arrives after
 * shutdown began is refused before it writes anything.
 */
@Timeout(60)
class ShutdownAdmissionTest {

    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final BlockingWork work = new BlockingWork();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, SERVICE)
            .lifecycleEventsEnabled(false)
            .distributedLock(lock)
            .shutdownTimeout(Duration.ofSeconds(10))
            .build();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        executor.shutdown();
    }

    /**
     * A launch held between its admission and its registration — the gap
     * between today's {@code shuttingDown} check and the drain's map — when
     * shutdown begins: the drain waits for it, so {@code shutdown()} returns
     * only once the run it started has released its instance lock.
     */
    @Test
    void aLaunchPastItsAdmissionCheckWhenShutdownBeginsIsWaitedFor() throws Exception {
        var held = new CountDownLatch(1);
        var go = new CountDownLatch(1);
        executor.afterLaunchAdmission = () -> {
            held.countDown();
            await(go, "the launch was let go");
        };
        var launchFailure = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.startWorkflow("wf-late", "QuickWorkflow", "default", null, QuickWorkflow::new,
                        quickMethod());
            } catch (RuntimeException | Error e) {
                launchFailure.set(e);
            }
        });
        await(held, "the launch holds its instance lock and is not registered yet");
        assertEquals(1, lock.heldCount());
        // Let the launch go 300 ms into the drain: a drain that does not know
        // about it returns well inside that window.
        executor.beforeShutdownDrain = () -> openLater(go);

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.shutdown();

            assertEquals(0, lock.heldCount(),
                    "shutdown() returned while a launch admitted before it began still held its instance lock");
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains("1 run(s) in flight at start, 0 still running")),
                    logs.records().toString());
        }
        assertTrue(launcher.join(Duration.ofSeconds(5)));
        assertNull(launchFailure.get(), () -> "the admitted launch failed: " + launchFailure.get());
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-late").orElseThrow().status());
    }

    /**
     * A {@code $maestro:retry} command consumed while the node drains — and
     * one consumed after it stopped — is refused before it reads or writes:
     * the row stays {@code FAILED}, nothing is deleted, nothing is launched,
     * and the {@code IllegalStateException} reaches the admin dispatcher,
     * which does not acknowledge the record, so a peer retries it.
     */
    @Test
    void aRetryDuringShutdownIsRefusedAndWritesNothing() throws Exception {
        executor.startWorkflow("wf-failed", "FailingWorkflow", "default", null, FailingWorkflow::new,
                failingMethod());
        assertEquals(WorkflowStatus.FAILED, valueWithin(
                () -> store.getInstance("wf-failed").orElseThrow().status(), WorkflowStatus.FAILED,
                Duration.ofSeconds(5)));
        var failed = store.getInstance("wf-failed").orElseThrow();
        var eventsBefore = store.getEvents(failed.id());
        var registration = new WorkflowRegistration("FailingWorkflow", "default", FailingWorkflow::new,
                failingMethod());

        // A run inside its activity keeps the drain going.
        var proxy = work.proxy(executor, store, serializer, "a");
        executor.startWorkflow("wf-busy", "WorkWorkflow", "default", null, () -> new WorkWorkflow(proxy),
                workMethod());
        assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5)));
        var draining = new CountDownLatch(1);
        executor.beforeShutdownDrain = draining::countDown;
        var shutter = Thread.ofVirtual().start(executor::shutdown);
        await(draining, "shutdown reached its drain");

        var refused = assertThrows(IllegalStateException.class, () -> executor.retryWorkflow("wf-failed",
                registration));
        assertTrue(refused.getMessage().contains("shutting down"), refused.getMessage());
        assertTrue(shutter.isAlive(), "the drain was still in progress");
        assertUntouched(failed, eventsBefore);

        work.releaseAll();
        assertTrue(shutter.join(Duration.ofSeconds(10)), "shutdown returned");

        assertThrows(IllegalStateException.class, () -> executor.retryWorkflow("wf-failed", registration),
                "a retry after shutdown returned is refused too");
        assertUntouched(failed, eventsBefore);
    }

    /**
     * F4: {@code shutdown()} called on a launching thread — here from an
     * observer's {@code instanceLockAcquired}, inside the start's lock
     * acquisition — does not wait its whole deadline for that very launch. The
     * launch is still given up: it writes no row and is refused.
     */
    @Test
    void aShutdownCalledOnALaunchingThreadDoesNotWaitForThatLaunch() throws Exception {
        var holder = new AtomicReference<@Nullable WorkflowExecutor>();
        var calledFromLaunch = new java.util.concurrent.atomic.AtomicBoolean();
        var node = WorkflowExecutor.builder(store, serializer, SERVICE)
                .lifecycleEventsEnabled(false)
                .distributedLock(lock)
                .shutdownTimeout(Duration.ofSeconds(10))
                .observer(new io.b2mash.maestro.core.observe.EngineObserver() {
                    @Override
                    public void instanceLockAcquired(String workflowId) {
                        if (calledFromLaunch.compareAndSet(false, true)) {
                            java.util.Objects.requireNonNull(holder.get()).shutdown();
                        }
                    }
                })
                .build();
        holder.set(node);
        try {
            var start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> node.startWorkflow("wf-self", "QuickWorkflow", "default",
                    null, QuickWorkflow::new, quickMethod()));
            var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(tookMs < 3_000, "shutdown() waited " + tookMs + " ms for the launch it was called from");
            assertTrue(store.getInstance("wf-self").isEmpty(), "the given-up start wrote its row");
            assertEquals(0, lock.heldCount(), "the given-up start released its lock");
        } finally {
            node.shutdown();
        }
    }

    /**
     * F4: a workflow that calls {@code shutdown()} on its own run thread does
     * not make it wait the whole deadline for itself; the run is given up and
     * does not complete.
     */
    @Test
    void aShutdownCalledFromAWorkflowsOwnThreadDoesNotWaitForThatRun() throws Exception {
        SelfStoppingWorkflow.node.set(executor);
        SelfStoppingWorkflow.tookMs.set(-1);
        try {
            executor.startWorkflow("wf-stops-itself", "SelfStoppingWorkflow", "default", null,
                    SelfStoppingWorkflow::new, method(SelfStoppingWorkflow.class));

            assertTrue(valueWithin(() -> SelfStoppingWorkflow.tookMs.get() >= 0, true, Duration.ofSeconds(15)));
            assertTrue(SelfStoppingWorkflow.tookMs.get() < 3_000,
                    "shutdown() waited " + SelfStoppingWorkflow.tookMs.get() + " ms for the run it was called from");
            assertEquals(0, valueWithin(lock::heldCount, 0, Duration.ofSeconds(5)));
            assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-stops-itself").orElseThrow().status(),
                    "a run given up by its own shutdown() call does not complete");
        } finally {
            SelfStoppingWorkflow.node.set(null);
        }
    }

    private void assertUntouched(io.b2mash.maestro.core.model.WorkflowInstance failed,
                                 java.util.List<io.b2mash.maestro.core.model.WorkflowEvent> eventsBefore) {
        var now = store.getInstance("wf-failed").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, now.status(), "the row stays FAILED");
        assertEquals(failed.version(), now.version(), "the row was not rewritten");
        assertEquals(eventsBefore, store.getEvents(failed.id()), "no event was deleted or appended");
        assertFalse(executor.isRunning("wf-failed"), "no run was launched");
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what, e);
        }
    }

    /**
     * Opens {@code gate} 300 ms from now. The sleep only widens the window for
     * the code before the fix: a drain that does not wait for the launch
     * returns within it. With the fix the drain waits whatever the timing.
     */
    private static void openLater(CountDownLatch gate) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
    }

    private static Method quickMethod() {
        return method(QuickWorkflow.class);
    }

    private static Method failingMethod() {
        return method(FailingWorkflow.class);
    }

    private static Method method(Class<?> type) {
        try {
            return type.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Completes at once. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class QuickWorkflow {
        /** @return a constant */
        @WorkflowMethod
        public String run() {
            return "done";
        }
    }

    /** Calls {@code shutdown()} on its own executor, from its own run thread, and records how long it took. */
    @DurableWorkflow(name = "SelfStoppingWorkflow")
    public static final class SelfStoppingWorkflow {
        static final AtomicReference<@Nullable WorkflowExecutor> node = new AtomicReference<>();
        static final java.util.concurrent.atomic.AtomicLong tookMs = new java.util.concurrent.atomic.AtomicLong(-1);

        /** @return a constant */
        @WorkflowMethod
        public String run() {
            var start = System.nanoTime();
            java.util.Objects.requireNonNull(node.get()).shutdown();
            tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return "done";
        }
    }

    /** Fails at once. */
    @DurableWorkflow(name = "FailingWorkflow")
    public static final class FailingWorkflow {
        /** @return never */
        @WorkflowMethod
        public String run() {
            throw new IllegalStateException("the workflow fails (test)");
        }
    }
}
