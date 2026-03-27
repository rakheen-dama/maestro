package io.maestro.core.engine;

import io.maestro.core.exception.WorkflowAlreadyExistsException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.TimerStatus;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowInstance;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowStatus;
import io.maestro.core.model.WorkflowTimer;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LockHandle;
import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimerPoller}.
 *
 * <p>Tests leader election, timer firing, error resilience, and graceful stop.
 */
class TimerPollerTest {

    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;
    private final Duration pollInterval = Duration.ofMillis(100);
    private TimerPoller poller;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, serializer, "test-service");
    }

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.stop();
        }
        executor.shutdown();
    }

    // ── Timer firing ─────────────────────────────────────────────────

    @Test
    @DisplayName("poller finds due timers and fires them")
    void pollerFiresDueTimers() throws Exception {
        // Create a due timer
        var timerId = UUID.randomUUID();
        store.saveTimer(new WorkflowTimer(
                timerId, UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, null, "test-service", pollInterval, 100);
        poller.start();

        // Wait for the poller to fire the timer
        Thread.sleep(500);

        // Timer should be marked as FIRED
        var dueTimers = store.getDueTimers(Instant.now().plusSeconds(60), 100);
        assertTrue(dueTimers.isEmpty(), "Timer should have been fired");
    }

    @Test
    @DisplayName("poller does not fire future timers")
    void pollerDoesNotFireFutureTimers() throws Exception {
        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().plusSeconds(600), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, null, "test-service", pollInterval, 100);
        poller.start();

        Thread.sleep(300);

        var dueTimers = store.getDueTimers(Instant.now().plusSeconds(700), 100);
        assertEquals(1, dueTimers.size(), "Future timer should not have been fired");
        assertEquals(TimerStatus.PENDING, dueTimers.getFirst().status());
    }

    // ── Error resilience ─────────────────────────────────────────────

    @Test
    @DisplayName("one timer fire failure does not stop batch processing")
    void errorResilienceInBatch() throws Exception {
        // Create two due timers — both will "fire" (mark as FIRED in store)
        // even though the executor's unpark won't find a parked thread
        var timer1 = UUID.randomUUID();
        var timer2 = UUID.randomUUID();
        store.saveTimer(new WorkflowTimer(
                timer1, UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now()));
        store.saveTimer(new WorkflowTimer(
                timer2, UUID.randomUUID(), "wf-2", "sleep-1",
                Instant.now().minusSeconds(5), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, null, "test-service", pollInterval, 100);
        poller.start();

        Thread.sleep(500);

        // Both timers should have been processed
        var remaining = store.getDueTimers(Instant.now().plusSeconds(60), 100);
        assertTrue(remaining.isEmpty(), "All due timers should have been fired");
    }

    // ── Leader election ──────────────────────────────────────────────

    @Test
    @DisplayName("poller does not poll when not leader")
    void nonLeaderDoesNotPoll() throws Exception {
        // Lock that always denies leadership
        var lock = new DenyingLock();

        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, lock, "test-service", pollInterval, 100);
        poller.start();

        Thread.sleep(500);

        // Timer should NOT have been fired because this instance isn't the leader
        var dueTimers = store.getDueTimers(Instant.now().plusSeconds(60), 100);
        assertEquals(1, dueTimers.size(), "Non-leader should not fire timers");
    }

    @Test
    @DisplayName("poller polls when it acquires leadership")
    void leaderPolls() throws Exception {
        var lock = new GrantingLock();

        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, lock, "test-service", pollInterval, 100);
        poller.start();

        Thread.sleep(500);

        var dueTimers = store.getDueTimers(Instant.now().plusSeconds(60), 100);
        assertTrue(dueTimers.isEmpty(), "Leader should fire timers");
    }

    @Test
    @DisplayName("null lock — always polls (single-node mode)")
    void nullLockAlwaysPolls() throws Exception {
        store.saveTimer(new WorkflowTimer(
                UUID.randomUUID(), UUID.randomUUID(), "wf-1", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now()));

        poller = new TimerPoller(store, executor, null, "test-service", pollInterval, 100);
        poller.start();

        Thread.sleep(500);

        var dueTimers = store.getDueTimers(Instant.now().plusSeconds(60), 100);
        assertTrue(dueTimers.isEmpty(), "Null lock should always poll");
    }

    // ── Graceful stop ────────────────────────────────────────────────

    @Test
    @DisplayName("stop terminates the poller")
    void stopTerminatesPoller() throws Exception {
        poller = new TimerPoller(store, executor, null, "test-service", pollInterval, 100);
        poller.start();
        assertTrue(poller.isRunning());

        poller.stop();
        Thread.sleep(200);
        assertFalse(poller.isRunning());
    }

    // ── Lock implementations ────────────────────────────────────────

    private static class DenyingLock implements DistributedLock {
        @Override public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return Optional.empty();
        }
        @Override public void release(LockHandle handle) {}
        @Override public void renew(LockHandle handle, Duration ttl) {}
        @Override public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    private static class GrantingLock implements DistributedLock {
        @Override public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }
        @Override public void release(LockHandle handle) {}
        @Override public void renew(LockHandle handle, Duration ttl) {}
        @Override public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    // ── In-memory WorkflowStore ──────────────────────────────────────

    private static class InMemoryWorkflowStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, WorkflowInstance> instancesById = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            instancesById.put(instance.id(), instance);
            return instance;
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override public void updateInstance(WorkflowInstance instance) {
            instancesByWorkflowId.put(instance.workflowId(), instance);
            instancesById.put(instance.id(), instance);
        }

        @Override public void appendEvent(WorkflowEvent event) { events.add(event); }

        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == seq)
                    .findFirst();
        }

        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override public void saveSignal(WorkflowSignal signal) { signals.add(signal); }

        @Override public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return signals.stream()
                    .filter(s -> s.workflowId().equals(workflowId) && s.signalName().equals(signalName) && !s.consumed())
                    .toList();
        }

        @Override public void markSignalConsumed(UUID signalId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId)) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return;
                }
            }
        }

        @Override public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            for (int i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.workflowId().equals(workflowId) && s.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }

        @Override public void saveTimer(WorkflowTimer timer) { timers.add(timer); }

        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING && !t.fireAt().isAfter(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override public void markTimerFired(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId)) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.FIRED, t.createdAt()));
                    return;
                }
            }
        }

        @Override public void markTimerCancelled(UUID timerId) {
            for (int i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), TimerStatus.CANCELLED, t.createdAt()));
                    return;
                }
            }
        }
    }
}
