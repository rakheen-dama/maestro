package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
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
