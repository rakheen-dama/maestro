package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.context.WorkflowContext;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graceful-shutdown contract of {@link WorkflowExecutor}.
 *
 * <p>Shutting a node down is a routine operational event — a deploy, a scale-in,
 * a pod eviction. It must be indistinguishable, from the workflow's point of
 * view, from that node never having existed: a workflow parked on
 * {@code awaitSignal} or {@code sleep} owns durable state that is still valid,
 * so shutdown must leave it in its {@code WAITING_*} status, run no
 * compensation, and hand its instance lock back so another node can adopt it.
 *
 * <p>The contract asserted here, from {@code docs/test-plan.md} §P5:
 * <ol>
 *   <li>A parked workflow stays {@code WAITING_SIGNAL}/{@code WAITING_TIMER}
 *       and stays recoverable — never {@code FAILED}.</li>
 *   <li>Compensations do not run: nothing failed.</li>
 *   <li>In-flight activities drain rather than being killed.</li>
 *   <li>Instance locks are released, so a surviving node adopts the workflow
 *       immediately instead of waiting out the TTL.</li>
 *   <li>A second executor over the same store recovers the workflow and runs
 *       it to completion.</li>
 * </ol>
 *
 * <p>A genuine workflow failure must be unaffected — the last test pins that,
 * so the shutdown path cannot be widened into a blanket exception swallow.
 */
@DisplayName("Graceful shutdown leaves parked workflows recoverable, not failed")
class WorkflowExecutorShutdownTest {

    private static final Duration NEVER = Duration.ofMinutes(10);

    private InMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;
    private WorkflowExecutor secondExecutor;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        if (secondExecutor != null) {
            secondExecutor.shutdown();
        }
    }

    // ── 1. Parked workflows stay recoverable ───────────────────────────

    @Test
    @DisplayName("A workflow parked on awaitSignal stays WAITING_SIGNAL and recoverable")
    void shutdown_withWorkflowParkedOnSignal_leavesItWaitingSignalAndRecoverable() throws Exception {
        var workflow = new AwaitingWorkflow();
        executor.startWorkflow("park-signal", "AwaitingWorkflow", "default",
                "input", workflow, AwaitingWorkflow.class.getMethod("run", String.class));
        awaitStatus("park-signal", WorkflowStatus.WAITING_SIGNAL);

        executor.shutdown();

        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("park-signal").orElseThrow().status(),
                "shutdown must not fail a workflow that was merely waiting for a signal");
        assertFalse(executor.isRunning("park-signal"),
                "the parked thread must have exited, otherwise shutdown never actually reached it");
        assertEquals(List.of("park-signal"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList(),
                "the workflow must still be recoverable by another node");
        assertFalse(hasEvent("park-signal", EventType.WORKFLOW_FAILED),
                "no WORKFLOW_FAILED event may be written for a shutdown");
    }

    @Test
    @DisplayName("A workflow that wraps awaitSignal in catch (Exception) still survives shutdown as WAITING_SIGNAL")
    void shutdown_withCatchExceptionAroundAwaitSignal_stillLeavesItWaitingSignalAndRecoverable() throws Exception {
        // A broad try/catch around a park point is ordinary, reasonable-looking
        // workflow code. If ExecutorShutdownException were a RuntimeException
        // (like every other Maestro exception), this catch would swallow it and
        // reinstate the exact bug it exists to prevent: a routine shutdown
        // reported as this workflow's own failure.
        var workflow = new CatchAllAwaitingWorkflow();
        var method = CatchAllAwaitingWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("park-catchall", "CatchAllAwaitingWorkflow", "default",
                "input", workflow, method);
        awaitStatus("park-catchall", WorkflowStatus.WAITING_SIGNAL);

        executor.shutdown();

        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("park-catchall").orElseThrow().status(),
                "a workflow author's catch (Exception) around awaitSignal must not turn a shutdown into a failure");
        assertFalse(executor.isRunning("park-catchall"));
        assertEquals(List.of("park-catchall"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());
        assertFalse(hasEvent("park-catchall", EventType.WORKFLOW_FAILED),
                "ExecutorShutdownException must not be catchable by ordinary workflow code");

        // Prove the durable state is genuinely intact, not just untouched in
        // memory: a second executor recovers it and drives it to completion.
        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var registrations = Map.of("CatchAllAwaitingWorkflow",
                new WorkflowRegistration("CatchAllAwaitingWorkflow", "default", workflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations));
        awaitStatus("park-catchall", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("park-catchall", CatchAllAwaitingWorkflow.SIGNAL, "approved");

        awaitStatus("park-catchall", WorkflowStatus.COMPLETED);
        assertEquals("approved",
                serializer.deserialize(store.getInstance("park-catchall").orElseThrow().output(), String.class));
    }

    @Test
    @DisplayName("A workflow parked on sleep stays WAITING_TIMER and recoverable")
    void shutdown_withWorkflowParkedOnSleep_leavesItWaitingTimerAndRecoverable() throws Exception {
        var workflow = new SleepingWorkflow();
        executor.startWorkflow("park-timer", "SleepingWorkflow", "default",
                "input", workflow, SleepingWorkflow.class.getMethod("run", String.class));
        awaitStatus("park-timer", WorkflowStatus.WAITING_TIMER);

        executor.shutdown();

        assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance("park-timer").orElseThrow().status(),
                "shutdown must not fail a workflow that was merely sleeping on a durable timer");
        assertFalse(executor.isRunning("park-timer"));
        assertEquals(List.of("park-timer"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());
        assertFalse(hasEvent("park-timer", EventType.WORKFLOW_FAILED));
    }

    // ── 2. No compensation on shutdown ─────────────────────────────────

    @Test
    @DisplayName("Shutting down while parked runs no compensation — nothing failed")
    void shutdown_withCompensationsRegisteredAndParked_runsNoCompensation() throws Exception {
        var compensations = new AtomicInteger();
        var workflow = new CompensatingAwaitingWorkflow(compensations);
        executor.startWorkflow("park-saga", "CompensatingAwaitingWorkflow", "default",
                "input", workflow, CompensatingAwaitingWorkflow.class.getMethod("run", String.class));
        awaitStatus("park-saga", WorkflowStatus.WAITING_SIGNAL);

        executor.shutdown();

        assertEquals(0, compensations.get(),
                "compensating a workflow that is merely parked would undo committed business work");
        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("park-saga").orElseThrow().status());
        assertFalse(hasEvent("park-saga", EventType.COMPENSATION_STARTED),
                "no compensation may be recorded in the event log");
    }

    @Test
    @DisplayName("Shutdown mid-compensation leaves the workflow recoverable with no step recorded failed, "
            + "and a second executor completes the compensation")
    void shutdown_duringCompensation_leavesItRecoverableAndCompletesOnRecovery() throws Exception {
        var released = new AtomicInteger();
        var workflow = new ParkingCompensationWorkflow(released);
        var method = ParkingCompensationWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("mid-comp", "ParkingCompensationWorkflow", "default", "input", workflow, method);

        // LIFO order runs "parking-compensation" (registered second) before
        // "release" (registered first) — so shutdown here interrupts the very
        // first compensation, before the second has had a chance to run.
        awaitStatus("mid-comp", WorkflowStatus.WAITING_SIGNAL);
        assertTrue(hasEvent("mid-comp", EventType.COMPENSATION_STARTED),
                "must be genuinely mid-compensation, not the original park");

        executor.shutdown();

        assertEquals(0, released.get(),
                "the compensation later in LIFO order must not have run yet");
        assertFalse(hasEvent("mid-comp", EventType.COMPENSATION_STEP_FAILED),
                "the compensation step interrupted by shutdown must not be recorded as failed");
        assertFalse(hasEvent("mid-comp", EventType.WORKFLOW_FAILED),
                "shutdown must not finalise the workflow as FAILED while compensation is unresolved");
        var statusAfterShutdown = store.getInstance("mid-comp").orElseThrow().status();
        assertTrue(statusAfterShutdown.isActive(),
                "the instance must stay in an active, recoverable status — was " + statusAfterShutdown);
        assertEquals(List.of("mid-comp"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());

        // A second node recovers it: the interrupted compensation re-parks
        // (nothing was memoized for it — it never completed), and once resumed,
        // finishes compensation and reaches FAILED, the correct outcome for a
        // genuine failure.
        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var registrations = Map.of("ParkingCompensationWorkflow",
                new WorkflowRegistration("ParkingCompensationWorkflow", "default", workflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations),
                "the workflow abandoned mid-compensation must be recoverable by a fresh node");

        awaitStatus("mid-comp", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("mid-comp", ParkingCompensationWorkflow.RESUME_SIGNAL, "resumed");

        awaitStatus("mid-comp", WorkflowStatus.FAILED);
        assertEquals(1, released.get(), "the remaining compensation must complete once resumed");
        assertFalse(hasEvent("mid-comp", EventType.COMPENSATION_STEP_FAILED),
                "no compensation step may be recorded failed across the full recovery");
        assertTrue(hasEvent("mid-comp", EventType.COMPENSATION_COMPLETED));
        assertTrue(hasEvent("mid-comp", EventType.WORKFLOW_FAILED),
                "the genuine failure must still be finalised once compensation completes");
    }

    // NOTE: SagaManager.executeParallel's shutdown-rethrow check (the analogous
    // fix for @Saga(parallelCompensation = true)) has no test here — it is
    // currently unreachable. See the fix-round-1 report for the call-chain
    // proof: parallel-compensation branch contexts are built with
    // `operations = null` (SagaManager.executeParallel), so any compensation
    // that calls WorkflowContext.current().awaitSignal()/sleep() throws
    // IllegalStateException immediately (WorkflowContext.requireOperations())
    // and never reaches ParkingLot — confirmed by attempting exactly this
    // test, which timed out waiting for WAITING_SIGNAL because the workflow
    // raced straight to FAILED via the IllegalStateException instead.

    @Test
    @DisplayName("Shutdown while a workflow's parallel() branch is parked propagates without being wrapped, "
            + "leaving it recoverable")
    void shutdown_withParkedParallelBranch_leavesItRecoverableAndCompletable() throws Exception {
        // DefaultWorkflowOperations.parallel's branch error-collection loop
        // (not SagaManager's) — the forward fork-join API, unrelated to sagas.
        var workflow = new ParkingParallelBranchWorkflow();
        var method = ParkingParallelBranchWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("park-parallel-branch", "ParkingParallelBranchWorkflow", "default", "input",
                workflow, method);

        awaitStatus("park-parallel-branch", WorkflowStatus.WAITING_SIGNAL);

        executor.shutdown();

        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("park-parallel-branch").orElseThrow().status(),
                "a shutdown Error from a parallel() branch must not be wrapped into a RuntimeException "
                        + "and recorded as this workflow's own failure");
        assertFalse(executor.isRunning("park-parallel-branch"));
        assertFalse(hasEvent("park-parallel-branch", EventType.WORKFLOW_FAILED));
        assertEquals(List.of("park-parallel-branch"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());

        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var registrations = Map.of("ParkingParallelBranchWorkflow",
                new WorkflowRegistration("ParkingParallelBranchWorkflow", "default", workflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations));
        awaitStatus("park-parallel-branch", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("park-parallel-branch", ParkingParallelBranchWorkflow.SIGNAL, "approved");

        awaitStatus("park-parallel-branch", WorkflowStatus.COMPLETED);
        assertEquals("approved|quick", serializer.deserialize(
                store.getInstance("park-parallel-branch").orElseThrow().output(), String.class));
    }

    @Test
    @DisplayName("Shutdown while a retried activity is parked propagates without being retried or wrapped")
    void shutdown_withRetriedActivityParked_propagatesWithoutRetryOrWrap() throws Exception {
        // Drives the FULL activity proxy dispatch — Method.invoke() wrapping
        // the park in InvocationTargetException, ActivityInvocationHandler
        // .invokeActivity's cause-unwrap, and RetryExecutor.executeWithRetry —
        // not just RetryExecutor in isolation (see RetryExecutorTest for that).
        var invocations = new AtomicInteger();
        var activityImpl = new ParkingActivityImpl(invocations, RetryShutdownWorkflow.RESUME_SIGNAL);
        var proxy = new ActivityProxyFactory().createProxy(
                ParkingActivity.class, activityImpl, store, null, null,
                RetryPolicy.builder().maxAttempts(5).initialInterval(Duration.ofMillis(1))
                        .maxInterval(Duration.ofMillis(10)).backoffMultiplier(1.0).build(),
                Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new RetryShutdownWorkflow(proxy);
        var method = RetryShutdownWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("park-retry-activity", "RetryShutdownWorkflow", "default", "input",
                workflow, method);

        awaitStatus("park-retry-activity", WorkflowStatus.WAITING_SIGNAL);
        assertEquals(1, invocations.get(), "must not have been retried before shutdown either");

        executor.shutdown();

        assertEquals(1, invocations.get(),
                "a shutdown signal reaching a retried activity (maxAttempts=5) must not be retried");
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("park-retry-activity").orElseThrow().status(),
                "must not be wrapped into ActivityExecutionException and recorded as this workflow's failure");
        assertFalse(executor.isRunning("park-retry-activity"));
        assertFalse(hasEvent("park-retry-activity", EventType.ACTIVITY_FAILED),
                "must not be recorded as an exhausted-retries activity failure");
        assertFalse(hasEvent("park-retry-activity", EventType.WORKFLOW_FAILED));
        assertEquals(List.of("park-retry-activity"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());

        // Recovery: the activity never completed (no ACTIVITY_COMPLETED was
        // persisted), so it replays live again on the second node.
        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var restartedInvocations = new AtomicInteger();
        var restartedActivityImpl = new ParkingActivityImpl(restartedInvocations, RetryShutdownWorkflow.RESUME_SIGNAL);
        var restartedProxy = new ActivityProxyFactory().createProxy(
                ParkingActivity.class, restartedActivityImpl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var restartedWorkflow = new RetryShutdownWorkflow(restartedProxy);
        var registrations = Map.of("RetryShutdownWorkflow",
                new WorkflowRegistration("RetryShutdownWorkflow", "default", restartedWorkflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations),
                "the workflow abandoned mid-activity must be recoverable by a fresh node");

        awaitStatus("park-retry-activity", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("park-retry-activity", RetryShutdownWorkflow.RESUME_SIGNAL, "resumed");

        awaitStatus("park-retry-activity", WorkflowStatus.COMPLETED);
        assertEquals(1, restartedInvocations.get());
    }

    @Test
    @DisplayName("Shutdown while a @Compensate-annotated activity's compensation method is parked leaves the "
            + "workflow recoverable with no step recorded failed")
    void shutdown_withCompensateActivityParked_leavesItRecoverableAndCompletesOnRecovery() throws Exception {
        // Drives ActivityInvocationHandler.registerCompensationIfAnnotated's
        // reflective compensationMethod.invoke(proxy, ...) call — a SEPARATE
        // InvocationTargetException-unwrap fix from invokeActivity's, in a
        // different method. Sequential (default) compensation: unlike a
        // parallel-compensation branch, this runs on the main, fully-wired
        // WorkflowContext, so awaitSignal() genuinely parks.
        var undoCount = new AtomicInteger();
        var activityImpl = new ParkingCompensateActivityImpl(undoCount, CompensateShutdownWorkflow.RESUME_SIGNAL);
        var proxy = new ActivityProxyFactory().createProxy(
                ParkingCompensateActivity.class, activityImpl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new CompensateShutdownWorkflow(proxy);
        var method = CompensateShutdownWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("park-compensate-activity", "CompensateShutdownWorkflow", "default", "widget",
                workflow, method);

        awaitStatus("park-compensate-activity", WorkflowStatus.WAITING_SIGNAL);
        assertTrue(hasEvent("park-compensate-activity", EventType.COMPENSATION_STARTED),
                "must be genuinely mid-compensation, not an ordinary park");

        executor.shutdown();

        assertFalse(hasEvent("park-compensate-activity", EventType.COMPENSATION_STEP_FAILED),
                "the @Compensate activity's method interrupted by shutdown must not be recorded as failed");
        assertFalse(hasEvent("park-compensate-activity", EventType.WORKFLOW_FAILED),
                "shutdown must not finalise the workflow as FAILED while compensation is unresolved");
        var statusAfterShutdown = store.getInstance("park-compensate-activity").orElseThrow().status();
        assertTrue(statusAfterShutdown.isActive(),
                "the instance must stay in an active, recoverable status — was " + statusAfterShutdown);
        assertEquals(List.of("park-compensate-activity"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList());

        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var restartedUndoCount = new AtomicInteger();
        var restartedActivityImpl = new ParkingCompensateActivityImpl(
                restartedUndoCount, CompensateShutdownWorkflow.RESUME_SIGNAL);
        var restartedProxy = new ActivityProxyFactory().createProxy(
                ParkingCompensateActivity.class, restartedActivityImpl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var restartedWorkflow = new CompensateShutdownWorkflow(restartedProxy);
        var registrations = Map.of("CompensateShutdownWorkflow",
                new WorkflowRegistration("CompensateShutdownWorkflow", "default", restartedWorkflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations),
                "the workflow abandoned mid-compensation must be recoverable by a fresh node");

        awaitStatus("park-compensate-activity", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("park-compensate-activity", CompensateShutdownWorkflow.RESUME_SIGNAL, "resumed");

        awaitStatus("park-compensate-activity", WorkflowStatus.FAILED);
        assertTrue(restartedUndoCount.get() >= 1, "the compensation activity must complete once resumed");
        assertFalse(hasEvent("park-compensate-activity", EventType.COMPENSATION_STEP_FAILED),
                "no compensation step may be recorded failed across the full recovery");
        assertTrue(hasEvent("park-compensate-activity", EventType.COMPENSATION_COMPLETED));
        assertTrue(hasEvent("park-compensate-activity", EventType.WORKFLOW_FAILED));
    }

    // ── 3. In-flight activities drain ──────────────────────────────────

    @Test
    @DisplayName("An in-flight workflow drains to completion before shutdown returns")
    void shutdown_withInFlightWork_waitsForItToDrain() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workflow = new BlockingWorkflow(entered, release);
        executor.startWorkflow("drain", "BlockingWorkflow", "default",
                "input", workflow, BlockingWorkflow.class.getMethod("run", String.class));
        assertTrue(entered.await(15, TimeUnit.SECONDS), "the workflow must be in-flight before shutdown");

        // Shut down from another thread so the test thread can observe that
        // shutdown *blocks* while the work is unfinished — the direct evidence
        // of draining, with no sleep standing in for synchronisation.
        var shutdownReturned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            executor.shutdown();
            shutdownReturned.countDown();
        });

        assertFalse(shutdownReturned.await(500, TimeUnit.MILLISECONDS),
                "shutdown must wait for in-flight work, not return while it is still running");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("drain").orElseThrow().status());

        release.countDown();

        assertTrue(shutdownReturned.await(15, TimeUnit.SECONDS), "shutdown must return once work drains");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("drain").orElseThrow().status(),
                "in-flight work must be allowed to finish, not cancelled");
        assertFalse(executor.isRunning("drain"));
    }

    @Test
    @DisplayName("A configured shutdown timeout bounds the drain wait instead of the 30s default")
    void shutdown_withConfiguredTimeout_returnsAfterTheConfiguredWaitNotTheDefault() throws Exception {
        executor.shutdown(); // discard the default, 30s-timeout executor from setUp
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, Duration.ofMillis(300), SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL);

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1); // deliberately never counted down
        var workflow = new BlockingWorkflow(entered, release);
        executor.startWorkflow("shutdown-timeout", "BlockingWorkflow", "default",
                "input", workflow, BlockingWorkflow.class.getMethod("run", String.class));
        assertTrue(entered.await(15, TimeUnit.SECONDS), "the workflow must be in-flight before shutdown");

        var start = Instant.now();
        executor.shutdown();
        var elapsed = Duration.between(start, Instant.now());

        assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0,
                "shutdown must return once the configured 300ms timeout elapses, not wait out the "
                        + "default 30s — took " + elapsed);
        assertTrue(executor.isRunning("shutdown-timeout"),
                "work that overruns the configured timeout is left running rather than killed");
    }

    // ── 4. Instance locks are handed back ──────────────────────────────

    @Test
    @DisplayName("The instance lock of a parked workflow is released on shutdown")
    void shutdown_withWorkflowParkedOnSignal_releasesTheInstanceLock() throws Exception {
        var lock = new MapLock();
        executor.shutdown(); // discard the default, lock-less executor
        executor = new WorkflowExecutor(store, lock, null, null, serializer, "node-a");

        var workflow = new AwaitingWorkflow();
        executor.startWorkflow("park-lock", "AwaitingWorkflow", "default",
                "input", workflow, AwaitingWorkflow.class.getMethod("run", String.class));
        awaitStatus("park-lock", WorkflowStatus.WAITING_SIGNAL);
        assertTrue(lock.isHeld("maestro:lock:workflow:park-lock"),
                "the lock must be held while the workflow is parked here");

        executor.shutdown();

        assertFalse(lock.isHeld("maestro:lock:workflow:park-lock"),
                "a shut-down node must hand its instance locks back, not make peers wait out the TTL");
    }

    // ── 5. Restart round trip ──────────────────────────────────────────

    @Test
    @DisplayName("A second executor recovers the workflow left parked by shutdown and completes it")
    void afterShutdown_aSecondExecutorRecoversTheParkedWorkflowAndCompletesIt() throws Exception {
        var workflow = new AwaitingWorkflow();
        var method = AwaitingWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("restart", "AwaitingWorkflow", "default", "input", workflow, method);
        awaitStatus("restart", WorkflowStatus.WAITING_SIGNAL);

        executor.shutdown();

        secondExecutor = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        var registrations = Map.of("AwaitingWorkflow",
                new WorkflowRegistration("AwaitingWorkflow", "default", workflow, method));
        assertEquals(1, secondExecutor.recoverWorkflows(registrations),
                "the workflow abandoned by shutdown must be recoverable by a fresh node");

        awaitStatus("restart", WorkflowStatus.WAITING_SIGNAL);
        secondExecutor.deliverSignal("restart", AwaitingWorkflow.SIGNAL, "approved");

        awaitStatus("restart", WorkflowStatus.COMPLETED);
        var instance = store.getInstance("restart").orElseThrow();
        assertEquals("approved", serializer.deserialize(instance.output(), String.class),
                "the recovered run must produce the result the interrupted one would have");
    }

    // ── Regression guard: genuine failures are untouched ───────────────

    @Test
    @DisplayName("A workflow that genuinely fails is still FAILED and still compensated")
    void genuineFailure_isStillFailedAndCompensated() throws Exception {
        var compensations = new AtomicInteger();
        var workflow = new FailingCompensatingWorkflow(compensations);
        executor.startWorkflow("boom", "FailingCompensatingWorkflow", "default",
                "input", workflow, FailingCompensatingWorkflow.class.getMethod("run", String.class));

        awaitStatus("boom", WorkflowStatus.FAILED);
        assertEquals(1, compensations.get(),
                "the shutdown path must not swallow real failures — compensation still runs");
        assertTrue(hasEvent("boom", EventType.WORKFLOW_FAILED));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> i.status() == expected)
                        .orElse(false));
    }

    private boolean hasEvent(String workflowId, EventType type) {
        var instance = store.getInstance(workflowId).orElseThrow();
        return store.getEvents(instance.id()).stream().anyMatch(e -> e.eventType() == type);
    }

    // ── Workflow fixtures ──────────────────────────────────────────────

    /** Parks on a signal that the test never sends unless it means to. */
    public static class AwaitingWorkflow {

        /** The signal this workflow waits for. */
        public static final String SIGNAL = "approval";

        /**
         * @param input unused seed
         * @return the signal payload once one arrives
         */
        public String run(String input) {
            return WorkflowContext.current().awaitSignal(SIGNAL, String.class, NEVER);
        }
    }

    /**
     * Parks on a signal, wrapped in a broad {@code catch (Exception)} — the
     * ordinary-looking workflow code shape that would swallow
     * {@link io.b2mash.maestro.core.exception.ExecutorShutdownException} if it
     * were a {@code RuntimeException} like every other Maestro exception.
     */
    public static class CatchAllAwaitingWorkflow {

        /** The signal this workflow waits for. */
        public static final String SIGNAL = "approval";

        /**
         * @param input unused seed
         * @return the signal payload once one arrives
         */
        public String run(String input) {
            try {
                return WorkflowContext.current().awaitSignal(SIGNAL, String.class, NEVER);
            } catch (Exception e) {
                // A reasonable-looking broad catch a workflow author might write
                // around a park point. Swallowing ExecutorShutdownException here
                // would misreport a routine shutdown as this failure.
                throw new IllegalStateException("awaitSignal failed unexpectedly", e);
            }
        }
    }

    /** Registers a compensation and then parks — the saga shape shutdown must not disturb. */
    public static class CompensatingAwaitingWorkflow {
        private final AtomicInteger compensations;

        /** @param compensations counter incremented by the registered compensation */
        public CompensatingAwaitingWorkflow(AtomicInteger compensations) {
            this.compensations = compensations;
        }

        /**
         * @param input unused seed
         * @return the signal payload once one arrives
         */
        public String run(String input) {
            var wf = WorkflowContext.current();
            wf.addCompensation("undo-committed-work", compensations::incrementAndGet);
            return wf.awaitSignal(AwaitingWorkflow.SIGNAL, String.class, NEVER);
        }
    }

    /** Sleeps on a durable timer far beyond the test's lifetime. */
    public static class SleepingWorkflow {

        /**
         * @param input unused seed
         * @return a constant, never reached in these tests
         */
        public String run(String input) {
            WorkflowContext.current().sleep(NEVER);
            return "woke";
        }
    }

    /** Blocks inside the workflow body — an activity that is still in flight. */
    public static class BlockingWorkflow {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        /**
         * @param entered counted down once the workflow body is running
         * @param release the test releases this to let the work finish
         */
        public BlockingWorkflow(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        /**
         * @param input unused seed
         * @return a constant once released
         */
        public String run(String input) {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("in-flight work was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("in-flight work was interrupted", e);
            }
            return "drained";
        }
    }

    /** Registers a compensation and then genuinely fails. */
    public static class FailingCompensatingWorkflow {
        private final AtomicInteger compensations;

        /** @param compensations counter incremented by the registered compensation */
        public FailingCompensatingWorkflow(AtomicInteger compensations) {
            this.compensations = compensations;
        }

        /**
         * @param input unused seed
         * @return never returns
         */
        public String run(String input) {
            var wf = WorkflowContext.current();
            wf.addCompensation("undo-committed-work", compensations::incrementAndGet);
            throw new IllegalStateException("Intentional failure");
        }
    }

    /**
     * Registers two compensations and then genuinely fails. LIFO order runs
     * {@code parking-compensation} (registered second) before {@code release}
     * (registered first) — the shape that reveals whether shutdown mid-saga
     * is wrongly recorded as a compensation failure, since it interrupts the
     * first compensation the moment it parks, before the second ever runs.
     */
    public static class ParkingCompensationWorkflow {

        /** The signal that unblocks the parking compensation on recovery. */
        public static final String RESUME_SIGNAL = "resume-compensation";

        private final AtomicInteger released;

        /** @param released counter incremented by the "release" compensation */
        public ParkingCompensationWorkflow(AtomicInteger released) {
            this.released = released;
        }

        /**
         * @param input unused seed
         * @return never returns
         */
        public String run(String input) {
            var wf = WorkflowContext.current();
            wf.addCompensation("release", released::incrementAndGet);
            wf.addCompensation("parking-compensation",
                    () -> WorkflowContext.current().awaitSignal(RESUME_SIGNAL, String.class, NEVER));
            throw new IllegalStateException("Intentional failure to trigger compensation");
        }
    }

    /**
     * Calls {@code parallel()} with two branches — one parks on a signal, the
     * other returns immediately — so the fork spawns real branch threads
     * (DefaultWorkflowOperations.parallel's single-task fast path only
     * applies to a list of exactly one, and needs no fix).
     */
    public static class ParkingParallelBranchWorkflow {

        /** The signal the parking branch waits for. */
        public static final String SIGNAL = "approval";

        /**
         * @param input unused seed
         * @return the two branch results, joined once both complete
         */
        public String run(String input) {
            var wf = WorkflowContext.current();
            List<String> results = wf.parallel(List.of(
                    () -> wf.awaitSignal(SIGNAL, String.class, NEVER),
                    () -> "quick"
            ));
            return results.get(0) + "|" + results.get(1);
        }
    }

    /** Activity interface whose single method parks — see {@link ParkingActivityImpl}. */
    public interface ParkingActivity {
        /**
         * @param input unused seed
         * @return the signal payload once one arrives
         */
        String work(String input);
    }

    /**
     * Deliberately reaches into {@link WorkflowContext} from an activity
     * implementation's own method body — not the recommended pattern
     * (activities normally do I/O only and have no business touching
     * workflow context), but technically reachable: activities execute
     * synchronously on the workflow's own virtual thread, so
     * {@code WorkflowContext.current()} resolves correctly. Exists to drive
     * a shutdown signal through {@code RetryExecutor} and
     * {@code ActivityInvocationHandler.invokeActivity}'s reflection-unwrap.
     */
    public static class ParkingActivityImpl implements ParkingActivity {
        private final AtomicInteger invocations;
        private final String signalName;

        /**
         * @param invocations counter incremented on every call, proving retry didn't happen
         * @param signalName  the signal this activity awaits
         */
        public ParkingActivityImpl(AtomicInteger invocations, String signalName) {
            this.invocations = invocations;
            this.signalName = signalName;
        }

        @Override
        public String work(String input) {
            invocations.incrementAndGet();
            return WorkflowContext.current().awaitSignal(signalName, String.class, NEVER);
        }
    }

    /** Calls a single retried activity that parks. */
    public static class RetryShutdownWorkflow {

        /** The signal that unblocks the parked activity on recovery. */
        public static final String RESUME_SIGNAL = "resume-retried-activity";

        private final ParkingActivity activity;

        /** @param activity the (proxied) activity to call */
        public RetryShutdownWorkflow(ParkingActivity activity) {
            this.activity = activity;
        }

        /**
         * @param input unused seed
         * @return the signal payload once one arrives
         */
        public String run(String input) {
            return activity.work(input);
        }
    }

    /** Activity interface whose compensation method parks — see {@link ParkingCompensateActivityImpl}. */
    public interface ParkingCompensateActivity {
        /**
         * @param item the item being reserved
         * @return a reservation reference
         */
        @Compensate("undo")
        String reserve(String item);

        /** @param reservation the value returned by {@link #reserve(String)} */
        void undo(String reservation);
    }

    /**
     * {@code reserve} completes immediately; {@code undo} (the {@code @Compensate}
     * target) reaches into {@link WorkflowContext} — reachable because the
     * compensation method is invoked through the same activity proxy
     * machinery as any other activity call
     * ({@code ActivityInvocationHandler.registerCompensationIfAnnotated}), and
     * for sequential (default) compensation that call runs on the main,
     * fully-wired {@code WorkflowContext} (unlike a parallel-compensation
     * branch — see the fix-round-1 report for why that path is untestable).
     */
    public static class ParkingCompensateActivityImpl implements ParkingCompensateActivity {
        private final AtomicInteger undoCount;
        private final String signalName;

        /**
         * @param undoCount  counter incremented on every {@code undo} call
         * @param signalName the signal {@code undo} awaits
         */
        public ParkingCompensateActivityImpl(AtomicInteger undoCount, String signalName) {
            this.undoCount = undoCount;
            this.signalName = signalName;
        }

        @Override
        public String reserve(String item) {
            return "reserved:" + item;
        }

        @Override
        public void undo(String reservation) {
            undoCount.incrementAndGet();
            WorkflowContext.current().awaitSignal(signalName, String.class, NEVER);
        }
    }

    /** Reserves (compensable via {@code @Compensate}) and then genuinely fails. */
    public static class CompensateShutdownWorkflow {

        /** The signal that unblocks the parked compensation on recovery. */
        public static final String RESUME_SIGNAL = "resume-compensate-activity";

        private final ParkingCompensateActivity activity;

        /** @param activity the (proxied) activity to call */
        public CompensateShutdownWorkflow(ParkingCompensateActivity activity) {
            this.activity = activity;
        }

        /**
         * @param item unused seed
         * @return never returns
         */
        public String run(String item) {
            activity.reserve(item);
            throw new IllegalStateException("Intentional failure to trigger compensation");
        }
    }

    // ── Map-backed DistributedLock ─────────────────────────────────────

    /** Minimal in-memory {@link DistributedLock}; thread-safe. */
    private static final class MapLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        boolean isHeld(String key) {
            return locks.containsKey(key);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
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

    /** Minimal in-memory {@link io.b2mash.maestro.core.spi.WorkflowStore}; thread-safe. */
    private static final class InMemoryStore implements io.b2mash.maestro.core.spi.WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            if (byWorkflowId.putIfAbsent(instance.workflowId(), instance) != null) {
                throw new WorkflowAlreadyExistsException(instance.workflowId());
            }
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(byWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return byWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            byWorkflowId.put(instance.workflowId(), instance);
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
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
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
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId) && !s.consumed()) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
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
                    signals.set(i, new WorkflowSignal(s.id(), instanceId, s.workflowId(),
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
            return transitionTimer(timerId, TimerStatus.FIRED);
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            transitionTimer(timerId, TimerStatus.CANCELLED);
        }

        private boolean transitionTimer(UUID timerId, TimerStatus to) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), to, t.createdAt()));
                    return true;
                }
            }
            return false;
        }
    }
}
