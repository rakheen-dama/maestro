package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowInstanceLockManagerCloseTest.BlockingRenewLock;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.event.Level;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5: {@link WorkflowExecutor#shutdown()} against one deadline, and what each
 * of its steps leaves behind.
 */
@DisplayName("WorkflowExecutor shutdown deadline")
@Timeout(60)
class WorkflowExecutorShutdownDeadlineTest {

    private static final Duration LOOP_INTERVAL = Duration.ofMillis(20);

    private final AtomicInteger started = new AtomicInteger();
    private final CountDownLatch latch = new CountDownLatch(1);
    /** Counted down once by each of the four loops' store calls and by the stuck publisher. */
    private final CountDownLatch entered = new CountDownLatch(5);
    /** Releases every stuck store call and the stuck publisher. */
    private final CountDownLatch release = new CountDownLatch(1);
    private final RecordingEngineObserver recorder = new RecordingEngineObserver();
    /** A lock whose next renewal blocks until {@code renewLock.release} opens. */
    private final BlockingRenewLock renewLock = new BlockingRenewLock();

    private StuckStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new StuckStore(entered, release);
        serializer = new PayloadSerializer(JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        renewLock.release.countDown();
        latch.countDown();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** Issue 64 (R4): the renewer is not interrupted inside {@code renewAll}, so nothing is counted. */
    @Test
    void aShutdownDuringARenewalPassCountsNoRenewalFailure() throws Exception {
        var lock = new BlockingRenewLock();
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .instanceLockTtl(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofMillis(300))
                .distributedLock(lock)
                .observer(recorder)
                .build();
        lock.blockNextRenew();
        start("wf-overrun", new BlockingWorkflow(proxy()));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        assertTrue(lock.inRenew.await(5, SECONDS));

        executor.shutdown();
        lock.release.countDown();
        lock.awaitBlockedRenewerEnded();

        assertEquals(List.of(), recorder.lockRenewFailed());
        assertFalse(lock.renewInterrupted.get());
    }

    @Test
    @DisplayName("shutdown() returns within maestro.shutdown.timeout even when every loop, a run, the lock renewer and the publisher are stuck (3.6)")
    void shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .messaging(new StuckMessaging(entered, release))
                .lifecycleEventsEnabled(true)
                .distributedLock(renewLock)
                .instanceLockTtl(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofSeconds(1))
                .build();
        renewLock.blockNextRenew();
        start("wf-overrun", new BlockingWorkflow(proxy()));
        executor.startTimerPoller(LOOP_INTERVAL, 10);
        executor.startRecoveryPoller(Map.of(), LOOP_INTERVAL);
        executor.startSignalJanitor(LOOP_INTERVAL, Duration.ofDays(1));
        executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(
                LOOP_INTERVAL, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1));
        assertTrue(entered.await(10, SECONDS), "every loop and the publisher are inside a call");
        assertTrue(renewLock.inRenew.await(10, SECONDS), "the lock renewer is inside renewAll");
        await().atMost(5, SECONDS).until(() -> started.get() == 1);

        var t0 = System.nanoTime();
        executor.shutdown();
        var took = (System.nanoTime() - t0) / 1_000_000;

        // timeout + 1 s: about 1 s of margin on a loaded machine, while a step
        // that owns a timeout of its own still lands past it (the renewer's
        // fixed 2 s join gives 3 s, the publisher's fixed 5 s flush 6 s).
        assertTrue(took < 2_000, "took " + took + " ms");
    }

    @Test
    void aSecondShutdownCallReturnsOnlyAfterTheFirstHasFinished() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        start("wf-park", new AwaitingWorkflow());
        start("wf-overrun", new BlockingWorkflow(proxy()));
        await().atMost(5, SECONDS).until(() -> started.get() == 1
                && store.getInstance("wf-park").orElseThrow().status() == WorkflowStatus.WAITING_SIGNAL);
        var overrunRunningWhenSecondReturned = new AtomicBoolean(true);

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var first = Thread.ofVirtual().start(executor::shutdown);
            await().atMost(5, SECONDS).until(() -> !executor.isRunning("wf-park")); // the first call is draining
            var second = Thread.ofVirtual().start(() -> {
                executor.shutdown();
                overrunRunningWhenSecondReturned.set(executor.isRunning("wf-overrun"));
            });
            awaitSecondCallerWaiting(logs);       // the second call is inside shutdown(), waiting
            latch.countDown();                    // wf-overrun completes, so the first call's drain ends

            assertTrue(first.join(Duration.ofSeconds(5)) && second.join(Duration.ofSeconds(5)));
        }
        assertFalse(overrunRunningWhenSecondReturned.get(),
                "the second call (Spring's destroy method) returned while the first was still draining");
    }

    /**
     * D-5's bound is what is left of the first call's deadline plus 2 s, not
     * the 2 s alone: a first call that drains for its whole 5 s deadline
     * still holds a second call that arrived at once.
     */
    @Test
    void aSecondCallWaitsForAFirstCallDrainingLongerThanTheGrace() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        start("wf-overrun", new BlockingWorkflow(proxy())); // never released before teardown
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        var firstFinishedWhenSecondReturned = new AtomicBoolean();

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var first = Thread.ofVirtual().start(executor::shutdown);
            await().atMost(5, SECONDS).until(() -> logged(logs, "Maestro shutdown started"));
            var second = Thread.ofVirtual().start(() -> {
                executor.shutdown();
                firstFinishedWhenSecondReturned.set(logged(logs, "Maestro shutdown hit its 5000 ms deadline"));
            });
            awaitSecondCallerWaiting(logs);

            assertTrue(first.join(Duration.ofSeconds(10)) && second.join(Duration.ofSeconds(10)));
        }
        assertTrue(firstFinishedWhenSecondReturned.get(),
                "the second call returned before the first call's 5 s drain had finished");
    }

    /**
     * A request already in flight that starts a workflow while the engine
     * drains gets {@code IllegalStateException}, as before WP5 — in a web
     * host an HTTP 500 unless the host maps it.
     */
    @Test
    void startingAWorkflowDuringTheDrainIsRefused() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        start("wf-overrun", new BlockingWorkflow(proxy()));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var first = Thread.ofVirtual().start(executor::shutdown);
            await().atMost(5, SECONDS).until(() -> logged(logs, "Maestro shutdown started"));

            var refused = assertThrows(IllegalStateException.class, () -> start("wf-late", new QuickWorkflow()));
            assertTrue(first.isAlive(), "the drain was still in progress");
            assertTrue(refused.getMessage().contains("shutting down"), refused.getMessage());
            latch.countDown();
            assertTrue(first.join(Duration.ofSeconds(5)));
        }
    }

    /** A first call that throws still releases a second caller at once (the latch counts down in a finally). */
    @Test
    void aSecondCallerIsReleasedWhenTheFirstShutdownThrows() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .build(); // the default 20 s deadline: a second caller left waiting would wait 22 s
        var secondWaiting = new CountDownLatch(1);
        executor.beforeShutdownDrain = () -> {
            try {
                secondWaiting.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("the first shutdown() fails");
        };
        var firstThrew = new AtomicReference<@Nullable Throwable>();

        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var first = Thread.ofVirtual().start(() -> {
                try {
                    executor.shutdown();
                } catch (Throwable t) {
                    firstThrew.set(t);
                }
            });
            await().atMost(5, SECONDS).until(() -> logged(logs, "Maestro shutdown started"));
            var second = Thread.ofVirtual().start(executor::shutdown);
            awaitSecondCallerWaiting(logs);
            secondWaiting.countDown();

            assertTrue(first.join(Duration.ofSeconds(5)), "the first call ended");
            assertTrue(second.join(Duration.ofSeconds(3)), "the second caller was released when the first call threw");
        }
        assertInstanceOf(IllegalStateException.class, firstThrew.get());
    }

    /**
     * The transport holds every publish until shutdown has begun (the parked
     * {@code wf-park} has left) and a further 200 ms have passed, so
     * {@code wf-quick}'s {@code WORKFLOW_COMPLETED} is still queued when the
     * flush step starts: only a flush that waits for what is left of the
     * deadline publishes it.
     */
    @Test
    void theLifecyclePublisherStillFlushesWhenTimeIsLeft() throws Exception {
        var gate = new CountDownLatch(1);
        var recording = new RecordingMessaging(gate);
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .messaging(recording)
                .lifecycleEventsEnabled(true)
                .build();
        start("wf-park", new AwaitingWorkflow());
        start("wf-quick", new QuickWorkflow());
        await().atMost(5, SECONDS).until(() ->
                store.getInstance("wf-quick").orElseThrow().status() == WorkflowStatus.COMPLETED
                        && store.getInstance("wf-park").orElseThrow().status() == WorkflowStatus.WAITING_SIGNAL);
        Thread.ofVirtual().start(() -> {
            await().pollDelay(Duration.ofMillis(200)).atMost(10, SECONDS).until(() -> !executor.isRunning("wf-park"));
            gate.countDown();
        });

        executor.shutdown();

        assertTrue(recording.types().contains(LifecycleEventType.WORKFLOW_COMPLETED), recording.types().toString());
    }

    @Test
    void theFinishLineReportsTheStepsAndNothingStillRunning() {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .build();
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.shutdown();
            var finish = logs.records().stream()
                    .filter(r -> r.message().startsWith("Maestro shutdown finished in")).toList();
            assertEquals(1, finish.size(), logs.records().toString());
            assertTrue(finish.getFirst().message().contains("0 still running"), finish.getFirst().message());
            assertTrue(finish.getFirst().message().contains("(drain "), finish.getFirst().message());
        }
    }

    /**
     * Step 6 joins the loops for what is left of the deadline: each loop here
     * needs about 100 ms after its interrupt to leave its store call, well
     * inside the deadline, so every loop has ended when {@code shutdown()}
     * returns and none is reported as not stopped.
     */
    @Test
    void everyLoopHasEndedWhenShutdownReturns() throws Exception {
        var slow = new SlowToStopStore();
        executor = WorkflowExecutor.builder(slow, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        executor.startTimerPoller(LOOP_INTERVAL, 10);
        executor.startRecoveryPoller(Map.of(), LOOP_INTERVAL);
        executor.startSignalJanitor(LOOP_INTERVAL, Duration.ofDays(1));
        executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(
                LOOP_INTERVAL, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1));
        assertTrue(slow.entered.await(10, SECONDS), "every loop is inside its store call");
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.shutdown();

            assertEquals(4, slow.returned.get(), "every loop's store call had returned when shutdown() returned");
            assertTrue(logs.records().stream().noneMatch(r -> r.message().contains("did not stop within the shutdown deadline")),
                    logs.records().toString());
        }
    }

    @Test
    void aShutdownThatHitsItsDeadlineNamesTheRunsStillRunning() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofMillis(300))
                .build();
        start("wf-overrun", new BlockingWorkflow(proxy()));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.shutdown();
            var warn = logs.records().stream()
                    .filter(r -> r.message().startsWith("Maestro shutdown hit its 300 ms deadline")).toList();
            assertEquals(1, warn.size(), logs.records().toString());
            assertEquals(Level.WARN, warn.getFirst().level());
            assertTrue(warn.getFirst().message().contains("[wf-overrun]"), warn.getFirst().message());
            assertTrue(warn.getFirst().message().contains("records nothing for them"), warn.getFirst().message());
        }
    }

    @Test
    void aLoopThatDoesNotStopByTheDeadlineIsNamed() throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofMillis(300))
                .build();
        executor.startSignalJanitor(LOOP_INTERVAL, Duration.ofDays(1)); // only the janitor's call is stuck
        await().atMost(5, SECONDS).until(() -> entered.getCount() == 4);
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            executor.shutdown();
            assertTrue(logs.records().stream().anyMatch(r -> r.level() == Level.WARN
                    && r.message().equals("Background loop 'signal-janitor' did not stop within the shutdown "
                            + "deadline; it ends after its current store call")), logs.records().toString());
        }
    }

    /**
     * Several runs overrun the drain: a deadline read from the frozen
     * {@code InstantSource} would never pass, so every join would get the full
     * 300 ms again and the drain alone would take {@code RUNS × 300 ms}.
     */
    @Test
    void theDeadlineIsMeasuredOnTheMonotonicClock() throws Exception {
        var runs = 15;
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .instantSource(InstantSource.fixed(Instant.parse("2026-01-01T00:00:00Z")))
                .shutdownTimeout(Duration.ofMillis(300))
                .build();
        for (var i = 0; i < runs; i++) {
            start("wf-overrun-" + i, new BlockingWorkflow(proxy()));
        }
        await().atMost(5, SECONDS).until(() -> started.get() == runs);

        var returned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            executor.shutdown();
            returned.countDown();
        });

        assertTrue(returned.await(3, SECONDS), "a frozen InstantSource must not stop the deadline from passing");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static boolean logged(CapturingSlf4jProvider.Capture logs, String prefix) {
        return logs.records().stream().anyMatch(r -> r.message().startsWith(prefix));
    }

    /** Waits until a second {@code shutdown()} call has logged that it is waiting for the first. */
    private static void awaitSecondCallerWaiting(CapturingSlf4jProvider.Capture logs) {
        await().atMost(5, SECONDS).until(() -> logged(logs, "Shutdown already in progress — waiting up to"));
    }

    private BlockingActivities proxy() {
        return new ActivityProxyFactory().createProxy(
                BlockingActivities.class, new BlockingActivitiesImpl(), store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, recorder,
                executor.taskQueueGovernor());
    }

    private void start(String workflowId, Object workflow) throws Exception {
        var method = workflow.getClass().getMethod("run");
        executor.startWorkflow(workflowId, workflow.getClass().getSimpleName(), "default", null,
                () -> workflow, method);
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** The activity a run overruns the drain in. */
    @Activity(name = "blocking")
    public interface BlockingActivities {
        /** @return a marker once the latch opens */
        String work();
    }

    /** Counts each live entry, then blocks until the test opens the latch. */
    private final class BlockingActivitiesImpl implements BlockingActivities {
        @Override
        public String work() {
            started.incrementAndGet();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "done";
        }
    }

    /** One blocking activity. */
    @DurableWorkflow(name = "BlockingWorkflow")
    public static final class BlockingWorkflow {
        private final BlockingActivities activities;

        BlockingWorkflow(BlockingActivities activities) {
            this.activities = activities;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return activities.work();
        }
    }

    /** Parks on {@code "go"}. */
    @DurableWorkflow(name = "AwaitingWorkflow")
    public static final class AwaitingWorkflow {
        /** @return the signal's payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
        }
    }

    /** Completes at once. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class QuickWorkflow {
        /** @return a marker */
        @WorkflowMethod
        public String run() {
            return "done";
        }
    }

    /**
     * R3's store: each background loop's store call counts {@code entered}
     * down and blocks on {@code release}, swallowing interrupts the way a
     * driver that ignores them does. Every other call is the in-memory store's.
     */
    static final class StuckStore extends VersionedInMemoryStore {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        StuckStore(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public List<WorkflowTimer> getDueTimers(int batchSize) {
            blockUninterruptibly(entered, release);
            return List.of();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(
                String serviceName, @Nullable RecoveryCursor after, int limit) {
            blockUninterruptibly(entered, release);
            return List.of();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            blockUninterruptibly(entered, release);
            return 0;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            blockUninterruptibly(entered, release);
            return PurgeResult.EMPTY;
        }
    }

    /**
     * Each background loop's store call waits until it is interrupted, then
     * takes about 100 ms more to return — a driver that cleans up after the
     * interrupt — and counts its return.
     */
    static final class SlowToStopStore extends VersionedInMemoryStore {
        final CountDownLatch entered = new CountDownLatch(4);
        final AtomicInteger returned = new AtomicInteger();

        @Override
        public List<WorkflowTimer> getDueTimers(int batchSize) {
            slowToStop();
            return List.of();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(
                String serviceName, @Nullable RecoveryCursor after, int limit) {
            slowToStop();
            return List.of();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            slowToStop();
            return 0;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            slowToStop();
            return PurgeResult.EMPTY;
        }

        private void slowToStop() {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                var until = System.nanoTime() + Duration.ofMillis(100).toNanos();
                for (var left = until - System.nanoTime(); left > 0; left = until - System.nanoTime()) {
                    LockSupport.parkNanos(left); // the flag is clear, so this parks
                }
                returned.incrementAndGet();
            }
        }
    }

    /** A transport whose lifecycle publish blocks on {@code release}, swallowing interrupts. */
    static final class StuckMessaging implements WorkflowMessaging {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean once = new AtomicBoolean();

        StuckMessaging(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            if (once.compareAndSet(false, true)) {
                blockUninterruptibly(entered, release);
            }
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        }
    }

    /**
     * Records each published lifecycle event's type once {@code gate} is open.
     * A publish interrupted while it waits (the publisher forced down) records
     * nothing.
     */
    static final class RecordingMessaging implements WorkflowMessaging {
        private final List<LifecycleEventType> types = new CopyOnWriteArrayList<>();
        private final CountDownLatch gate;

        RecordingMessaging(CountDownLatch gate) {
            this.gate = gate;
        }

        List<LifecycleEventType> types() {
            return List.copyOf(types);
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            types.add(event.eventType());
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        }
    }

    private static void blockUninterruptibly(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        while (true) {
            try {
                release.await();
                return;
            } catch (InterruptedException swallowed) {
                // a driver that swallows the interrupt (flag cleared) and keeps waiting
            }
        }
    }
}
