package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.TimerCancelledException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowExecutor}.
 *
 * <p>Uses an in-memory {@link WorkflowStore} to verify the full lifecycle
 * of workflow execution, signal delivery, recovery, and shutdown.
 */
class WorkflowExecutorTest {

    private InMemoryWorkflowStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── Start and complete ─────────────────────────────────────────────

    @Test
    @DisplayName("Start workflow → run to completion → status COMPLETED")
    void startWorkflowCompletesSuccessfully() throws Exception {
        var latch = new CountDownLatch(1);

        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow(
                "order-1", "SimpleWorkflow", "default",
                "hello", workflow, method);

        assertNotNull(instanceId);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Workflow should complete within timeout");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-1");
            assertTrue(instance.isPresent(), "Instance should exist in store");
            assertEquals(WorkflowStatus.COMPLETED, instance.get().status());
            assertFalse(executor.isRunning("order-1"), "Workflow should no longer be running");
        });
    }

    @Test
    @DisplayName("Start workflow with no input → completes with null input")
    void startWorkflowNoInput() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new NoInputWorkflow(latch);
        var method = NoInputWorkflow.class.getMethod("run");

        executor.startWorkflow("order-2", "NoInputWorkflow", "default",
                null, workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-2");
            assertTrue(instance.isPresent());
            assertEquals(WorkflowStatus.COMPLETED, instance.get().status());
        });
    }

    // ── Failure handling ───────────────────────────────────────────────

    @Test
    @DisplayName("Workflow exception → status FAILED")
    void workflowExceptionResultsInFailed() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new FailingWorkflow(latch);
        var method = FailingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-3", "FailingWorkflow", "default",
                "input", workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var instance = store.getInstance("order-3");
            assertTrue(instance.isPresent());
            assertEquals(WorkflowStatus.FAILED, instance.get().status());
        });
    }

    // ── Signal delivery ────────────────────────────────────────────────

    @Test
    @DisplayName("Deliver signal → unparks waiting workflow")
    void deliverSignalUnparksWorkflow() throws Exception {
        var completedLatch = new CountDownLatch(1);
        var waitingLatch = new CountDownLatch(1);
        var workflow = new SignalWorkflow(waitingLatch, completedLatch);
        var method = SignalWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-4", "SignalWorkflow", "default",
                "input", workflow, method);

        // Wait for workflow to reach awaitSignal and park
        assertTrue(waitingLatch.await(5, TimeUnit.SECONDS), "Workflow should reach await point");
        await().atMost(Duration.ofSeconds(2)).until(() ->
                !store.getUnconsumedSignals("order-4", "payment.result").isEmpty()
                || executor.isRunning("order-4"));

        // Deliver the signal
        executor.deliverSignal("order-4", "payment.result", "paid");

        // Workflow should complete
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS), "Workflow should complete after signal");
    }

    @Test
    @DisplayName("A configured wake-recheck-interval reaches the SignalManager, bounding cross-node signal latency")
    void wakeRecheckIntervalReachesSignalManager() throws Exception {
        // Default recheck interval (30s) would never notice a signal persisted
        // without a local unpark inside this test's short await window — so a
        // short custom interval completing the workflow proves it was threaded
        // from the constructor into SignalManager, not just left at the default.
        executor.shutdown();
        executor = new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(200));

        var completedLatch = new CountDownLatch(1);
        var waitingLatch = new CountDownLatch(1);
        var workflow = new SignalWorkflow(waitingLatch, completedLatch);
        var method = SignalWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("wake-recheck", "SignalWorkflow", "default", "input", workflow, method);
        assertTrue(waitingLatch.await(5, TimeUnit.SECONDS), "Workflow should reach await point");
        await().atMost(Duration.ofSeconds(2)).until(() ->
                store.getInstance("wake-recheck")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // Persist the signal directly — bypasses executor.deliverSignal, so no
        // local unpark happens. Only the periodic store re-check can find it.
        var instanceId = store.getInstance("wake-recheck").orElseThrow().id();
        store.saveSignal(new WorkflowSignal(
                UUID.randomUUID(), instanceId, "wake-recheck", "payment.result",
                serializer.serialize("cross-node"), false, Instant.now()));

        assertTrue(completedLatch.await(3, TimeUnit.SECONDS),
                "the 200ms wake-recheck-interval configured on the constructor must have reached "
                        + "SignalManager — with the 30s default this would still be waiting");
    }

    // ── Shutdown ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Shutdown rejects new workflows")
    void shutdownRejectsNewWorkflows() throws Exception {
        executor.shutdown();

        var workflow = new SimpleWorkflow(new CountDownLatch(1));
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        assertThrows(IllegalStateException.class, () ->
                executor.startWorkflow("order-5", "SimpleWorkflow", "default",
                        "input", workflow, method));
    }

    @Test
    @DisplayName("isRunning() returns true while workflow is active")
    void isRunningReturnsTrueWhileActive() throws Exception {
        var startedLatch = new CountDownLatch(1);
        var blockLatch = new CountDownLatch(1);
        var workflow = new BlockingWorkflow(startedLatch, blockLatch);
        var method = BlockingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-6", "BlockingWorkflow", "default",
                "input", workflow, method);

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
        assertTrue(executor.isRunning("order-6"));

        blockLatch.countDown();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertFalse(executor.isRunning("order-6")));
    }

    @Test
    @DisplayName("Recovery re-invokes recoverable workflows")
    void recoverWorkflowsReInvokesRecoverable() throws Exception {
        // Pre-populate a recoverable instance
        var instanceId = UUID.randomUUID();
        var instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("recover-1")
                .runId(UUID.randomUUID())
                .workflowType("SimpleWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.createInstance(instance);

        var latch = new CountDownLatch(1);
        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);
        var reg = new WorkflowRegistration("SimpleWorkflow", "default", workflow, method);

        var count = executor.recoverWorkflows(Map.of("SimpleWorkflow", reg));

        assertEquals(1, count, "Should recover 1 workflow");
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Recovered workflow should complete");
    }

    @Test
    @DisplayName("Recovery heals a timer marked FIRED before its TIMER_FIRED event was appended")
    void recoverWorkflowsHealsTimerFiredBeforeEventAppend() throws Exception {
        // The crash window inside fireTimer: the row goes PENDING → FIRED, then
        // the node dies before its workflow thread appends TIMER_FIRED. The
        // event log now says "scheduled, never fired" while the row says
        // otherwise — and getDueTimers only ever returns PENDING rows, so no
        // poller will look at this timer again. Replay has to notice.
        var nodeAWorkflow = new SleepingWorkflow(Duration.ofMinutes(10), new CountDownLatch(1));
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-heal-1", "SleepingWorkflow", "default",
                "hello", nodeAWorkflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-heal-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        // The sleep is the first operation, so it owns sequence 1.
        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertEquals(TimerStatus.PENDING, timer.status());
        assertTrue(store.markTimerFired(timer.id()), "the crash window opens once the row transitions");
        // node-a is now gone: nothing unparks its thread, nothing appends TIMER_FIRED.

        var completed = new CountDownLatch(1);
        var nodeBWorkflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var registration = new WorkflowRegistration(
                "SleepingWorkflow", "default", nodeBWorkflow, method);
        var nodeB = new WorkflowExecutor(store, null, messaging, null, serializer, "node-b");
        try {
            assertEquals(1, nodeB.recoverWorkflows(Map.of("SleepingWorkflow", registration)));

            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "a timer already marked FIRED must not re-park the workflow forever");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(WorkflowStatus.COMPLETED,
                            store.getInstance("timer-heal-1").orElseThrow().status()));

            var firedEvents = store.getEvents(instanceId).stream()
                    .filter(e -> e.eventType() == EventType.TIMER_FIRED)
                    .toList();
            assertEquals(1, firedEvents.size(),
                    "replay must heal the missing TIMER_FIRED event exactly once");
        } finally {
            nodeB.shutdown();
        }
    }

    @Test
    @DisplayName("Recovery re-parks a sleep whose timer is still PENDING")
    void recoverWorkflowsReParksWhileTimerStillPending() throws Exception {
        // The counterpart of the healing test: a genuinely pending timer must
        // still park, or the self-heal would turn every sleep into a no-op.
        var nodeAWorkflow = new SleepingWorkflow(Duration.ofMinutes(10), new CountDownLatch(1));
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-pending-1", "SleepingWorkflow", "default",
                "hello", nodeAWorkflow, method);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-pending-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var completed = new CountDownLatch(1);
        var nodeBWorkflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var registration = new WorkflowRegistration(
                "SleepingWorkflow", "default", nodeBWorkflow, method);
        var nodeB = new WorkflowExecutor(store, null, messaging, null, serializer, "node-b");
        try {
            assertEquals(1, nodeB.recoverWorkflows(Map.of("SleepingWorkflow", registration)));

            await().atMost(Duration.ofSeconds(5)).until(() -> nodeB.isRunning("timer-pending-1"));
            assertFalse(completed.await(500, TimeUnit.MILLISECONDS),
                    "a PENDING timer must leave the recovered workflow parked");
            assertEquals(WorkflowStatus.WAITING_TIMER,
                    store.getInstance("timer-pending-1").orElseThrow().status());
            assertEquals(0, store.getEvents(instanceId).stream()
                            .filter(e -> e.eventType() == EventType.TIMER_FIRED).count(),
                    "no TIMER_FIRED event may be written while the timer is pending");

            // Firing it for real releases the recovered run.
            var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
            nodeB.fireTimer("timer-pending-1", "sleep-1", timer.id());
            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "firing the timer must release the re-parked workflow");
        } finally {
            nodeB.shutdown();
        }
    }

    // ── Timer cancel (Issue 13) ──────────────────────────────────────────

    @Test
    @DisplayName("cancelTimer unparks a workflow parked on it; uncaught it fails the workflow deterministically")
    void cancelTimer_unparksWorkflow_uncaughtFailsTheWorkflow() throws Exception {
        // Today (pre-fix): nothing unparks the workflow and it hangs forever.
        // This is the live-cancel repro from the design's test plan (§9.1).
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-cancel-1", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-cancel-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertEquals(TimerStatus.PENDING, timer.status());
        assertTrue(executor.cancelTimer("timer-cancel-1", "sleep-1", timer.id()),
                "cancel must win the CAS against a PENDING timer");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.FAILED,
                        store.getInstance("timer-cancel-1").orElseThrow().status()));
        assertFalse(completed.await(300, TimeUnit.MILLISECONDS),
                "an uncaught TimerCancelledException must not reach the workflow's own return path");

        var events = store.getEvents(instanceId);
        assertEquals(1, events.stream().filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count(),
                "the cancelled outcome must be memoized exactly once");
        assertEquals(0, events.stream().filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
        assertEquals(TimerStatus.CANCELLED, store.findTimer(instanceId, "sleep-1").orElseThrow().status());
    }

    @Test
    @DisplayName("a caught TimerCancelledException lets the workflow take a fallback branch")
    void cancelTimer_caught_takesFallbackBranch() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new CancellableSleepWorkflow(Duration.ofMinutes(10), completed);
        var method = CancellableSleepWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-cancel-catch-1", "CancellableSleepWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-cancel-catch-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertTrue(executor.cancelTimer("timer-cancel-catch-1", "sleep-1", timer.id()));

        assertTrue(completed.await(5, TimeUnit.SECONDS), "the catch block must let the workflow finish");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("timer-cancel-catch-1").orElseThrow().status()));
        assertEquals(1, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count());
    }

    @Test
    @DisplayName("cancelTimer on an already-fired timer is a false no-op (R1)")
    void cancelTimer_onFiredTimer_returnsFalse() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMillis(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-cancel-2", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-cancel-2")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        executor.fireTimer("timer-cancel-2", "sleep-1", timer.id());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED, store.getInstance("timer-cancel-2").orElseThrow().status()));

        assertFalse(executor.cancelTimer("timer-cancel-2", "sleep-1", timer.id()),
                "a FIRED timer must never transition to CANCELLED");
        assertEquals(1, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
        assertEquals(0, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count());
    }

    @Test
    @DisplayName("Recovery heals a timer cancelled before its TIMER_CANCELLED event was appended")
    void recoverWorkflowsHealsTimerCancelledBeforeEventAppend() throws Exception {
        // The crash window inside cancelTimer: the row goes PENDING → CANCELLED,
        // then the node dies before its workflow thread appends
        // TIMER_CANCELLED. Mirrors recoverWorkflowsHealsTimerFiredBeforeEventAppend
        // exactly, with the cancelled outcome instead of fired (design §6, C2/C3).
        // Today (pre-fix): recovery re-parks the workflow forever.
        var nodeAWorkflow = new CancellableSleepWorkflow(Duration.ofMinutes(10), new CountDownLatch(1));
        var method = CancellableSleepWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("timer-cancel-heal-1", "CancellableSleepWorkflow", "default",
                "hello", nodeAWorkflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("timer-cancel-heal-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertEquals(TimerStatus.PENDING, timer.status());
        assertTrue(store.markTimerCancelled(timer.id()), "the crash window opens once the row transitions");
        // node-a is now gone: nothing unparks its thread, nothing appends TIMER_CANCELLED.

        var completed = new CountDownLatch(1);
        var nodeBWorkflow = new CancellableSleepWorkflow(Duration.ofMinutes(10), completed);
        var registration = new WorkflowRegistration(
                "CancellableSleepWorkflow", "default", nodeBWorkflow, method);
        var nodeB = new WorkflowExecutor(store, null, messaging, null, serializer, "node-b");
        try {
            assertEquals(1, nodeB.recoverWorkflows(Map.of("CancellableSleepWorkflow", registration)));

            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "a timer already marked CANCELLED must not re-park the workflow forever");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(WorkflowStatus.COMPLETED,
                            store.getInstance("timer-cancel-heal-1").orElseThrow().status()));

            var cancelledEvents = store.getEvents(instanceId).stream()
                    .filter(e -> e.eventType() == EventType.TIMER_CANCELLED)
                    .toList();
            assertEquals(1, cancelledEvents.size(),
                    "replay must heal the missing TIMER_CANCELLED event exactly once");
        } finally {
            nodeB.shutdown();
        }
    }

    @Test
    @DisplayName("Replay of a log ending in TIMER_CANCELLED throws deterministically without new store writes")
    void replayOnly_timerCancelledEvent_throwsWithoutNewStoreWrites() throws Exception {
        // A finished decision: TIMER_SCHEDULED then TIMER_CANCELLED are already
        // in the log, as if a prior run already lived through the live-cancel
        // path. Replaying sleep() from this log must reproduce the throw purely
        // by reading the log — no new events, no timer row ever touched
        // (design §3.4, §7).
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("timer-cancel-replay-1")
                .runId(UUID.randomUUID())
                .workflowType("SleepingWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.WAITING_TIMER)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.createInstance(instance);
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.TIMER_SCHEDULED, "$maestro:sleep",
                serializer.serialize(new SeedTimerDetail("sleep-1", Duration.ofMinutes(10).toString())),
                Instant.now()));
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 2,
                EventType.TIMER_CANCELLED, "$maestro:timer-cancelled", null, Instant.now()));

        var registration = new WorkflowRegistration("SleepingWorkflow", "default", workflow, method);
        assertEquals(1, executor.recoverWorkflows(Map.of("SleepingWorkflow", registration)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.FAILED,
                        store.getInstance("timer-cancel-replay-1").orElseThrow().status()));
        assertFalse(completed.await(300, TimeUnit.MILLISECONDS),
                "an uncaught TimerCancelledException must not reach the workflow's own return path");

        // The only new event is WORKFLOW_FAILED — the workflow's own terminal
        // transition. Replaying sleep() itself must not touch the store at
        // all: no duplicate TIMER_CANCELLED, no TIMER_FIRED, no timer row.
        var events = store.getEvents(instance.id());
        assertEquals(3, events.size(), "the only new event must be the workflow's own WORKFLOW_FAILED");
        assertEquals(1, events.stream().filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count(),
                "replay must not duplicate the already-memoized TIMER_CANCELLED event");
        assertEquals(0, events.stream().filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
        assertTrue(store.findTimer(instance.id(), "sleep-1").isEmpty(),
                "no timer row was ever saved for this seeded log — replay must not create one");
    }

    @Test
    @DisplayName("Replay of a caught TIMER_CANCELLED does not re-invoke the catch handler's activity")
    void replayOnly_catchHandlerActivityAlreadyMemoized_isNotReInvoked() throws Exception {
        // Extends the replay-only proof above one step further: a workflow
        // that catches TimerCancelledException and calls a real (proxied)
        // activity in its handler. The log already contains TIMER_SCHEDULED,
        // TIMER_CANCELLED, and the handler activity's ACTIVITY_COMPLETED —
        // as if a prior run already lived through the catch-and-continue
        // path in full. Replaying must reproduce the exact same output
        // WITHOUT re-invoking the real activity (design §9 item 3 / §7).
        var completed = new CountDownLatch(1);
        var activityImpl = new CountingHandlerActivity();
        var proxyFactory = new ActivityProxyFactory();
        var activityProxy = proxyFactory.createProxy(
                HandlerActivity.class,
                activityImpl,
                store,
                null,
                messaging,
                RetryPolicy.noRetry(),
                Duration.ofSeconds(30),
                serializer,
                new RetryExecutor());
        var workflow = new CancellableSleepWorkflowWithActivity(
                Duration.ofMinutes(10), completed, activityProxy);
        var method = CancellableSleepWorkflowWithActivity.class.getMethod("run", String.class);

        var instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("timer-cancel-replay-handler-1")
                .runId(UUID.randomUUID())
                .workflowType("CancellableSleepWorkflowWithActivity")
                .taskQueue("default")
                .status(WorkflowStatus.WAITING_TIMER)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.createInstance(instance);
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.TIMER_SCHEDULED, "$maestro:sleep",
                serializer.serialize(new SeedTimerDetail("sleep-1", Duration.ofMinutes(10).toString())),
                Instant.now()));
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 2,
                EventType.TIMER_CANCELLED, "$maestro:timer-cancelled", null, Instant.now()));
        // The catch handler's activity call already completed durably in the
        // "prior run" this log stands in for.
        var memoizedOutcome = "cancelled:seed-handled";
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 3,
                EventType.ACTIVITY_COMPLETED, "HandlerActivity.handle",
                serializer.serialize(memoizedOutcome), Instant.now()));

        var registration = new WorkflowRegistration(
                "CancellableSleepWorkflowWithActivity", "default", workflow, method);
        assertEquals(1, executor.recoverWorkflows(Map.of("CancellableSleepWorkflowWithActivity", registration)));

        assertTrue(completed.await(5, TimeUnit.SECONDS), "the handler must run to completion on replay");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("timer-cancel-replay-handler-1").orElseThrow().status()));

        assertEquals(0, activityImpl.invocations.get(),
                "the handler's activity must replay from the memoized event, never re-invoke the real impl");
        assertEquals(memoizedOutcome,
                serializer.deserialize(
                        store.getInstance("timer-cancel-replay-handler-1").orElseThrow().output(), String.class),
                "replay must produce the exact same output as the original run");

        // No new writes beyond the workflow's own terminal event.
        var events = store.getEvents(instance.id());
        assertEquals(4, events.size(), "the only new event must be the workflow's own WORKFLOW_COMPLETED");
        assertEquals(1, events.stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
                "replay must not duplicate the already-memoized ACTIVITY_COMPLETED event");
    }

    private record SeedTimerDetail(String timerId, String duration) {}

    // ── Instance lock ──────────────────────────────────────────────────

    @Test
    @DisplayName("Start acquires the instance lock and releases it on completion")
    void startAcquiresInstanceLockAndReleasesOnCompletion() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("lock-1", "SimpleWorkflow", "default",
                    "hello", workflow, method);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(lock.acquiredKeys.contains("maestro:lock:workflow:lock-1"),
                    "instance lock must be acquired with the documented key");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertFalse(lock.isHeld("maestro:lock:workflow:lock-1"),
                            "instance lock must be released after completion"));
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Instance lock is acquired before the instance row is created (recovery-poller steal guard)")
    void lockAcquiredBeforeInstanceCreated() throws Exception {
        var lock = new MapLock();
        var lockHeldDuringCreate = new CopyOnWriteArrayList<Boolean>();
        var checkingStore = new InMemoryWorkflowStore() {
            @Override
            public WorkflowInstance createInstance(WorkflowInstance instance) {
                lockHeldDuringCreate.add(lock.isHeld("maestro:lock:workflow:" + instance.workflowId()));
                return super.createInstance(instance);
            }
        };
        var lockedExecutor = new WorkflowExecutor(checkingStore, lock, messaging, null, serializer, "test-service");
        try {
            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("lock-pre", "SimpleWorkflow", "default",
                    "hello", workflow, method);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(true), lockHeldDuringCreate,
                    "the instance lock must already be held when createInstance runs, "
                            + "so the recovery poller can never steal a just-created workflow");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Duplicate workflow start releases the pre-acquired instance lock")
    void duplicateStartReleasesLock() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("dup-1", "SimpleWorkflow", "default",
                    "hello", workflow, method);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertFalse(lock.isHeld("maestro:lock:workflow:dup-1")));

            assertThrows(WorkflowAlreadyExistsException.class, () ->
                    lockedExecutor.startWorkflow("dup-1", "SimpleWorkflow", "default",
                            "hello", workflow, method));
            assertFalse(lock.isHeld("maestro:lock:workflow:dup-1"),
                    "a failed start must not leak the pre-acquired instance lock");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Instance lock is released after workflow failure")
    void lockReleasedAfterFailure() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var latch = new CountDownLatch(1);
            var workflow = new FailingWorkflow(latch);
            var method = FailingWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("lock-2", "FailingWorkflow", "default",
                    "input", workflow, method);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertEquals(WorkflowStatus.FAILED, store.getInstance("lock-2").orElseThrow().status());
                assertFalse(lock.isHeld("maestro:lock:workflow:lock-2"),
                        "instance lock must be released after failure handling");
            });
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Instance lock stays held while parked awaiting a signal")
    void lockHeldWhileParked() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var waitingLatch = new CountDownLatch(1);
            var completedLatch = new CountDownLatch(1);
            var workflow = new SignalWorkflow(waitingLatch, completedLatch);
            var method = SignalWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("lock-3", "SignalWorkflow", "default",
                    "input", workflow, method);

            assertTrue(waitingLatch.await(5, TimeUnit.SECONDS));
            assertTrue(lock.isHeld("maestro:lock:workflow:lock-3"),
                    "instance lock must be held while parked");

            lockedExecutor.deliverSignal("lock-3", "payment.result", "paid");
            assertTrue(completedLatch.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertFalse(lock.isHeld("maestro:lock:workflow:lock-3")));
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Resume is skipped when the instance lock is held by another node")
    void resumeSkippedWhenLockHeldByAnotherNode() throws Exception {
        var lock = new MapLock();
        lock.holdForeign("maestro:lock:workflow:recover-locked");
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            store.createInstance(recoverableInstance("recover-locked"));

            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);
            var reg = new WorkflowRegistration("SimpleWorkflow", "default", workflow, method);

            var count = lockedExecutor.recoverWorkflows(Map.of("SimpleWorkflow", reg));

            assertEquals(0, count, "a foreign-locked workflow must not be counted as recovered");
            assertFalse(latch.await(300, TimeUnit.MILLISECONDS),
                    "the foreign-locked workflow must not execute");
            assertFalse(lockedExecutor.isRunning("recover-locked"));
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Resume skips an instance that turned terminal between query and lock grant")
    void resumeSkipsTerminalInstanceAfterLockGrant() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            // The store already shows COMPLETED …
            var completed = recoverableInstance("recover-done").toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .build();
            store.createInstance(completed);

            // … but the recovery query returned a stale RUNNING snapshot
            var stale = completed.toBuilder().status(WorkflowStatus.RUNNING).build();

            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);

            var resumed = lockedExecutor.resumeWorkflow(stale, workflow, method);

            assertFalse(resumed, "terminal instance must not be resumed");
            assertFalse(latch.await(300, TimeUnit.MILLISECONDS));
            assertFalse(lock.isHeld("maestro:lock:workflow:recover-done"),
                    "lock must be released after the terminal re-check");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Resume skips a terminal instance even without a lock backend")
    void resumeSkipsTerminalInstanceWithoutLockBackend() throws Exception {
        // The store already shows COMPLETED …
        var completed = recoverableInstance("recover-done-unlocked").toBuilder()
                .status(WorkflowStatus.COMPLETED)
                .build();
        store.createInstance(completed);

        // … but the recovery query returned a stale RUNNING snapshot. The
        // default executor has no DistributedLock (NO_BACKEND) — the terminal
        // re-check must still run.
        var stale = completed.toBuilder().status(WorkflowStatus.RUNNING).build();

        var latch = new CountDownLatch(1);
        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        var resumed = executor.resumeWorkflow(stale, workflow, method);

        assertFalse(resumed, "terminal instance must not be resumed without a lock backend");
        assertFalse(latch.await(300, TimeUnit.MILLISECONDS),
                "the terminal workflow must not execute");
    }

    @Test
    @DisplayName("Constructor rejects a non-positive instance lock TTL")
    void constructorRejectsNonPositiveInstanceLockTtl() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(-5)));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", null));
    }

    @Test
    @DisplayName("Constructor rejects a non-positive shutdown timeout")
    void constructorRejectsNonPositiveShutdownTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        Duration.ofSeconds(-5), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        null, Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("Constructor rejects a non-positive wake-recheck interval")
    void constructorRejectsNonPositiveWakeRecheckInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        Duration.ofSeconds(30), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        Duration.ofSeconds(30), Duration.ofSeconds(-5)));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowExecutor(store, null, messaging, null, serializer, "test-service",
                        "maestro:lock:", Duration.ofSeconds(30), true,
                        Duration.ofSeconds(30), null));
    }

    @Test
    @DisplayName("Fresh start proceeds when the instance lock cannot be acquired")
    void freshStartProceedsWhenLockUnavailable() throws Exception {
        var lock = new MapLock();
        lock.holdForeign("maestro:lock:workflow:lock-4");
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);

            lockedExecutor.startWorkflow("lock-4", "SimpleWorkflow", "default",
                    "hello", workflow, method);

            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "a fresh start must proceed despite a stale foreign lock — "
                            + "createInstance uniqueness already proved ownership");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    // ── Recovery poller ────────────────────────────────────────────────

    @Test
    @DisplayName("Recovery poller picks up a workflow once a foreign lock is released")
    void recoveryPollerPicksUpAfterForeignLockRelease() throws Exception {
        var lock = new MapLock();
        lock.holdForeign("maestro:lock:workflow:recover-later");
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            store.createInstance(recoverableInstance("recover-later"));

            var latch = new CountDownLatch(1);
            var workflow = new SimpleWorkflow(latch);
            var method = SimpleWorkflow.class.getMethod("run", String.class);
            var reg = new WorkflowRegistration("SimpleWorkflow", "default", workflow, method);
            var registrations = Map.of("SimpleWorkflow", reg);

            // Startup recovery: skipped (foreign lock)
            assertEquals(0, lockedExecutor.recoverWorkflows(registrations));

            lockedExecutor.startRecoveryPoller(registrations, Duration.ofMillis(100));
            assertFalse(latch.await(300, TimeUnit.MILLISECONDS),
                    "poller must not steal a workflow whose lock is held elsewhere");

            // The other node releases (crash → TTL expiry, or graceful release)
            lock.releaseForeign("maestro:lock:workflow:recover-later");

            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "poller must pick up the workflow after the foreign lock is gone");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    private WorkflowInstance recoverableInstance(String workflowId) {
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("SimpleWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── Poller status accessors ──────────────────────────────────────────

    @Test
    @DisplayName("isTimerPollerRunning() reflects the timer poller's lifecycle")
    void isTimerPollerRunningReflectsLifecycle() {
        assertFalse(executor.isTimerPollerRunning(), "no poller started yet");

        executor.startTimerPoller(Duration.ofMillis(50), 10);
        assertTrue(executor.isTimerPollerRunning());

        executor.shutdown();
        assertFalse(executor.isTimerPollerRunning(), "stopped by shutdown");
    }

    @Test
    @DisplayName("isRecoveryPollerRunning() reflects the recovery poller's lifecycle")
    void isRecoveryPollerRunningReflectsLifecycle() {
        assertFalse(executor.isRecoveryPollerRunning(), "no poller started yet");

        executor.startRecoveryPoller(Map.of(), Duration.ofMillis(50));
        assertTrue(executor.isRecoveryPollerRunning());

        executor.shutdown();
        assertFalse(executor.isRecoveryPollerRunning(), "stopped by shutdown");
    }

    @Test
    @DisplayName("hasTimerPollerStarted() is monotonic — stays true after the poller stops, "
            + "so callers can tell \"never started\" apart from \"started, then died\"")
    void hasTimerPollerStartedStaysTrueAfterStop() {
        assertFalse(executor.hasTimerPollerStarted(), "never started yet");

        executor.startTimerPoller(Duration.ofMillis(50), 10);
        assertTrue(executor.hasTimerPollerStarted());
        assertTrue(executor.isTimerPollerRunning());

        executor.shutdown();
        assertTrue(executor.hasTimerPollerStarted(),
                "monotonic — must not revert to \"never started\" once shutdown stops the poller");
        assertFalse(executor.isTimerPollerRunning(), "but it is no longer running");
    }

    @Test
    @DisplayName("hasRecoveryPollerStarted() is monotonic — stays true after the poller stops")
    void hasRecoveryPollerStartedStaysTrueAfterStop() {
        assertFalse(executor.hasRecoveryPollerStarted(), "never started yet");

        executor.startRecoveryPoller(Map.of(), Duration.ofMillis(50));
        assertTrue(executor.hasRecoveryPollerStarted());
        assertTrue(executor.isRecoveryPollerRunning());

        executor.shutdown();
        assertTrue(executor.hasRecoveryPollerStarted(),
                "monotonic — must not revert to \"never started\" once shutdown stops the poller");
        assertFalse(executor.isRecoveryPollerRunning(), "but it is no longer running");
    }

    @Test
    @DisplayName("Lifecycle events are published")
    void lifecycleEventsPublished() throws Exception {
        var latch = new CountDownLatch(1);
        var workflow = new SimpleWorkflow(latch);
        var method = SimpleWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("order-7", "SimpleWorkflow", "default",
                "hello", workflow, method);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertFalse(messaging.events.isEmpty(), "Lifecycle events should have been published"));
    }

    // ── Saga compensation tests ──────────────────────────────────────

    @Test
    @DisplayName("Manual addCompensation runs compensations on failure (no @Saga required)")
    void manualCompensationRunsOnFailure() throws Exception {
        var compensationRan = new CountDownLatch(1);
        var failedLatch = new CountDownLatch(1);

        var workflow = new CompensatingWorkflow(failedLatch, compensationRan);
        var method = CompensatingWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("comp-1", "CompensatingWorkflow", "default",
                "input", workflow, method);

        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        // Wait for workflow to fully complete (transition to FAILED)
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var inst = store.getInstance("comp-1");
            assertTrue(inst.isPresent());
            assertEquals(WorkflowStatus.FAILED, inst.get().status());
        });

        // Compensation should have run
        assertTrue(compensationRan.await(2, TimeUnit.SECONDS),
                "Compensation should have been executed");

        // COMPENSATION_STARTED and COMPENSATION_COMPLETED events should exist
        var events = store.events.stream()
                .filter(e -> e.workflowInstanceId().equals(
                        store.getInstance("comp-1").get().id()))
                .map(WorkflowEvent::eventType)
                .toList();
        assertTrue(events.contains(EventType.COMPENSATION_STARTED));
        assertTrue(events.contains(EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("@Saga workflow runs compensations on failure")
    void sagaAnnotatedWorkflowRunsCompensationsOnFailure() throws Exception {
        var compensationRan = new CountDownLatch(1);
        var failedLatch = new CountDownLatch(1);

        var workflow = new SagaAnnotatedWorkflow(failedLatch, compensationRan);
        var method = SagaAnnotatedWorkflow.class.getMethod("run", String.class);

        executor.startWorkflow("saga-1", "SagaWorkflow", "default",
                "input", workflow, method);

        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var inst = store.getInstance("saga-1");
            assertTrue(inst.isPresent());
            assertEquals(WorkflowStatus.FAILED, inst.get().status());
        });

        assertTrue(compensationRan.await(2, TimeUnit.SECONDS),
                "Saga compensation should have been executed");
    }

    // ── Test workflow implementations ──────────────────────────────────

    /**
     * Workflow that registers a manual compensation and then fails.
     */
    public static class CompensatingWorkflow {
        private final CountDownLatch failedLatch;
        private final CountDownLatch compensationRan;

        public CompensatingWorkflow(CountDownLatch failedLatch, CountDownLatch compensationRan) {
            this.failedLatch = failedLatch;
            this.compensationRan = compensationRan;
        }

        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            workflow.addCompensation(() -> compensationRan.countDown());
            failedLatch.countDown();
            throw new RuntimeException("Intentional failure after compensation registration");
        }
    }

    /**
     * Workflow with @Saga annotation that registers compensation and fails.
     */
    public static class SagaAnnotatedWorkflow {
        private final CountDownLatch failedLatch;
        private final CountDownLatch compensationRan;

        public SagaAnnotatedWorkflow(CountDownLatch failedLatch, CountDownLatch compensationRan) {
            this.failedLatch = failedLatch;
            this.compensationRan = compensationRan;
        }

        @io.b2mash.maestro.core.annotation.Saga
        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            workflow.addCompensation("manual-comp", () -> compensationRan.countDown());
            failedLatch.countDown();
            throw new RuntimeException("Intentional saga failure");
        }
    }

    /**
     * Simple workflow that returns its input uppercased.
     */
    public static class SimpleWorkflow {
        private final CountDownLatch latch;

        public SimpleWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run(String input) {
            latch.countDown();
            return input != null ? input.toUpperCase() : "DONE";
        }
    }

    /**
     * Workflow whose only step is a durable sleep — the fixture for timer
     * replay. The nap is long enough that nothing fires it by accident; the
     * tests fire (or pretend to fire) it explicitly.
     */
    public static class SleepingWorkflow {
        private final Duration nap;
        private final CountDownLatch completed;

        public SleepingWorkflow(Duration nap, CountDownLatch completed) {
            this.nap = nap;
            this.completed = completed;
        }

        public String run(String input) {
            WorkflowContext.current().sleep(nap);
            completed.countDown();
            return "awake";
        }
    }

    /**
     * Sleeps like {@link SleepingWorkflow} but catches a cancelled timer and
     * takes a fallback branch instead of failing — the fixture for
     * {@link TimerCancelledException} determinism (Issue 13).
     */
    public static class CancellableSleepWorkflow {
        private final Duration nap;
        private final CountDownLatch completed;

        public CancellableSleepWorkflow(Duration nap, CountDownLatch completed) {
            this.nap = nap;
            this.completed = completed;
        }

        public String run(String input) {
            String outcome;
            try {
                WorkflowContext.current().sleep(nap);
                outcome = "fired";
            } catch (TimerCancelledException e) {
                outcome = "cancelled:" + e.timerId();
            }
            completed.countDown();
            return outcome;
        }
    }

    /**
     * Activity interface for {@link CancellableSleepWorkflowWithActivity}'s
     * catch handler — real activity-proxy memoization, not a bare Java call,
     * so replaying the handler after a cancel proves the handler's activity
     * is not re-invoked (Issue 13).
     */
    public interface HandlerActivity {
        String handle(String input);
    }

    /**
     * Counts real invocations so a test can assert replay never reaches it.
     */
    public static class CountingHandlerActivity implements HandlerActivity {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String handle(String input) {
            invocations.incrementAndGet();
            return input + "-handled";
        }
    }

    /**
     * Like {@link CancellableSleepWorkflow}, but the catch handler calls a
     * memoizing activity proxy instead of doing plain Java — the fixture for
     * proving a caught {@link TimerCancelledException}'s handler activity is
     * not re-invoked on replay (Issue 13).
     */
    public static class CancellableSleepWorkflowWithActivity {
        private final Duration nap;
        private final CountDownLatch completed;
        private final HandlerActivity activity;

        public CancellableSleepWorkflowWithActivity(
                Duration nap, CountDownLatch completed, HandlerActivity activity) {
            this.nap = nap;
            this.completed = completed;
            this.activity = activity;
        }

        public String run(String input) {
            String outcome;
            try {
                WorkflowContext.current().sleep(nap);
                outcome = activity.handle("fired:" + input);
            } catch (TimerCancelledException e) {
                outcome = activity.handle("cancelled:" + input);
            }
            completed.countDown();
            return outcome;
        }
    }

    /**
     * Workflow with no input parameter.
     */
    public static class NoInputWorkflow {
        private final CountDownLatch latch;

        public NoInputWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run() {
            latch.countDown();
            return "completed";
        }
    }

    /**
     * Workflow that always throws.
     */
    public static class FailingWorkflow {
        private final CountDownLatch latch;

        public FailingWorkflow(CountDownLatch latch) {
            this.latch = latch;
        }

        public String run(String input) {
            latch.countDown();
            throw new RuntimeException("Intentional failure");
        }
    }

    /**
     * Workflow that waits for a signal.
     */
    public static class SignalWorkflow {
        private final CountDownLatch waitingLatch;
        private final CountDownLatch completedLatch;

        public SignalWorkflow(CountDownLatch waitingLatch, CountDownLatch completedLatch) {
            this.waitingLatch = waitingLatch;
            this.completedLatch = completedLatch;
        }

        public String run(String input) {
            var workflow = io.b2mash.maestro.core.context.WorkflowContext.current();
            waitingLatch.countDown();
            var result = workflow.awaitSignal("payment.result", String.class, Duration.ofSeconds(10));
            completedLatch.countDown();
            return result;
        }
    }

    /**
     * Workflow that blocks on a latch (for testing isRunning).
     */
    public static class BlockingWorkflow {
        private final CountDownLatch startedLatch;
        private final CountDownLatch blockLatch;

        public BlockingWorkflow(CountDownLatch startedLatch, CountDownLatch blockLatch) {
            this.startedLatch = startedLatch;
            this.blockLatch = blockLatch;
        }

        public String run(String input) {
            startedLatch.countDown();
            try {
                blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "unblocked";
        }
    }

    // ── Map-backed DistributedLock ─────────────────────────────────────

    private static class MapLock implements DistributedLock {

        final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<String> acquiredKeys = new CopyOnWriteArrayList<>();

        void holdForeign(String key) {
            locks.put(key, new LockHandle(key, "foreign-token", Instant.now().plusSeconds(60)));
        }

        void releaseForeign(String key) {
            locks.remove(key);
        }

        boolean isHeld(String key) {
            return locks.containsKey(key);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            var existing = locks.putIfAbsent(key, handle);
            if (existing != null) {
                return Optional.empty();
            }
            acquiredKeys.add(key);
            return Optional.of(handle);
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    // ── In-memory WorkflowStore ────────────────────────────────────────

    /**
     * Simple in-memory implementation of {@link WorkflowStore} for testing.
     */
    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, WorkflowInstance> instancesById = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) {
                throw new WorkflowAlreadyExistsException(instance.workflowId());
            }
            instancesById.put(instance.id(), instance);
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream()
                    .filter(i -> i.status().isActive())
                    .toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
            instancesById.put(instance.id(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            events.add(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId)
                            && e.sequenceNumber() == sequenceNumber)
                    .findFirst();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId))
                    .toList();
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            signals.add(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId)
                            && s.signalName().equals(signalName)
                            && !s.consumed())
                    .toList();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            // Replace the signal with consumed=true
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId) && !s.consumed()) {
                    signals.set(i, new WorkflowSignal(
                            s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.workflowId().equals(workflowId) && s.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(
                            s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            timers.add(timer);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING && !t.fireAt().isAfter(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return timers.stream()
                    .filter(t -> t.workflowInstanceId().equals(workflowInstanceId)
                            && t.timerId().equals(timerId))
                    .findFirst();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(
                            t.id(), t.workflowInstanceId(), t.workflowId(), t.timerId(),
                            t.fireAt(), TimerStatus.FIRED, t.createdAt()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(
                            t.id(), t.workflowInstanceId(), t.workflowId(), t.timerId(),
                            t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return true;
                }
            }
            return false;
        }
    }

    // ── Recording WorkflowMessaging ────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {

        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {}

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {}

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {}

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
