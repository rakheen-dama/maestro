package io.b2mash.maestro.test;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WorkflowStore} for fast, deterministic workflow tests.
 *
 * <p>Backed by {@link ConcurrentHashMap} for instances and events, and
 * {@code synchronized} {@link ArrayList} for signals and timers.
 * Implements the full SPI contract including optimistic locking, event
 * deduplication, signal adoption, and timer CAS transitions.
 *
 * <p><b>Thread safety:</b> All operations are thread-safe. Signal and
 * timer operations synchronize on dedicated lock objects to prevent
 * TOCTOU races during check-then-act sequences (e.g., read-by-index
 * then set-by-index). Instance operations use per-workflowId locks
 * for optimistic lock version checks.
 *
 * @see TestWorkflowEnvironment
 */
public final class InMemoryWorkflowStore implements WorkflowStore {

    // workflowId → WorkflowInstance
    private final ConcurrentHashMap<String, WorkflowInstance> instances = new ConcurrentHashMap<>();

    // instanceId → (sequenceNumber → WorkflowEvent)
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, WorkflowEvent>> events = new ConcurrentHashMap<>();

    // Guarded by signalLock
    private final ArrayList<WorkflowSignal> signals = new ArrayList<>();
    private final Object signalLock = new Object();

    // Guarded by timerLock
    private final ArrayList<WorkflowTimer> timers = new ArrayList<>();
    private final Object timerLock = new Object();

    // Lock object per workflowId for updateInstance optimistic locking
    private final ConcurrentHashMap<String, Object> instanceLocks = new ConcurrentHashMap<>();

    // ── Instance operations ──────────────────────────────────────────────

    @Override
    public WorkflowInstance createInstance(WorkflowInstance instance) {
        var existing = instances.putIfAbsent(instance.workflowId(), instance);
        if (existing != null) {
            throw new WorkflowAlreadyExistsException(instance.workflowId());
        }
        instanceLocks.putIfAbsent(instance.workflowId(), new Object());
        return instance;
    }

    @Override
    public Optional<WorkflowInstance> getInstance(String workflowId) {
        return Optional.ofNullable(instances.get(workflowId));
    }

    @Override
    public List<WorkflowInstance> getRecoverableInstances() {
        return instances.values().stream()
                .filter(i -> i.status().isActive())
                .sorted(Comparator.comparing(WorkflowInstance::startedAt))
                .toList();
    }

    @Override
    public void updateInstance(WorkflowInstance instance) {
        var lock = instanceLocks.get(instance.workflowId());
        if (lock == null) {
            throw new WorkflowNotFoundException(instance.workflowId());
        }

        synchronized (lock) {
            var current = instances.get(instance.workflowId());
            if (current == null) {
                throw new WorkflowNotFoundException(instance.workflowId());
            }

            // The caller has already built the updated instance with version = current + 1.
            // We verify the caller's expected previous version matches what's stored.
            var expectedPreviousVersion = instance.version() - 1;
            if (current.version() != expectedPreviousVersion) {
                throw new OptimisticLockException(
                        instance.workflowId(), expectedPreviousVersion, current.version());
            }

            instances.put(instance.workflowId(), instance);
        }
    }

    // ── Event operations ─────────────────────────────────────────────────

    @Override
    public void appendEvent(WorkflowEvent event) {
        var instanceEvents = events.computeIfAbsent(
                event.workflowInstanceId(), _ -> new ConcurrentHashMap<>());

        var existing = instanceEvents.putIfAbsent(event.sequenceNumber(), event);
        if (existing != null) {
            throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber());
        }
    }

    @Override
    public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
        var instanceEvents = events.get(instanceId);
        if (instanceEvents == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(instanceEvents.get(sequenceNumber));
    }

    @Override
    public List<WorkflowEvent> getEvents(UUID instanceId) {
        var instanceEvents = events.get(instanceId);
        if (instanceEvents == null) {
            return List.of();
        }
        return instanceEvents.values().stream()
                .sorted(Comparator.comparingInt(WorkflowEvent::sequenceNumber))
                .toList();
    }

    // ── Signal operations ────────────────────────────────────────────────

    @Override
    public void saveSignal(WorkflowSignal signal) {
        synchronized (signalLock) {
            signals.add(signal);
        }
    }

    @Override
    public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
        synchronized (signalLock) {
            return signals.stream()
                    .filter(s -> workflowId.equals(s.workflowId()))
                    .filter(s -> signalName.equals(s.signalName()))
                    .filter(s -> !s.consumed())
                    .sorted(Comparator.comparing(WorkflowSignal::receivedAt))
                    .toList();
        }
    }

    @Override
    public void markSignalConsumed(UUID signalId) {
        synchronized (signalLock) {
            for (int i = 0; i < signals.size(); i++) {
                var signal = signals.get(i);
                if (signal.id().equals(signalId)) {
                    signals.set(i, new WorkflowSignal(
                            signal.id(),
                            signal.workflowInstanceId(),
                            signal.workflowId(),
                            signal.signalName(),
                            signal.payload(),
                            true,
                            signal.receivedAt()
                    ));
                    return;
                }
            }
        }
    }

    @Override
    public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        synchronized (signalLock) {
            for (int i = 0; i < signals.size(); i++) {
                var signal = signals.get(i);
                if (workflowId.equals(signal.workflowId()) && signal.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(
                            signal.id(),
                            instanceId,
                            signal.workflowId(),
                            signal.signalName(),
                            signal.payload(),
                            signal.consumed(),
                            signal.receivedAt()
                    ));
                }
            }
        }
    }

    // ── Timer operations ─────────────────────────────────────────────────

    @Override
    public void saveTimer(WorkflowTimer timer) {
        synchronized (timerLock) {
            timers.add(timer);
        }
    }

    @Override
    public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
        synchronized (timerLock) {
            return timers.stream()
                    .filter(t -> t.status() == TimerStatus.PENDING)
                    .filter(t -> !t.fireAt().isAfter(now))
                    .sorted(Comparator.comparing(WorkflowTimer::fireAt))
                    .limit(batchSize)
                    .toList();
        }
    }

    @Override
    public boolean markTimerFired(UUID timerId) {
        synchronized (timerLock) {
            for (int i = 0; i < timers.size(); i++) {
                var timer = timers.get(i);
                if (timer.id().equals(timerId)) {
                    if (timer.status() != TimerStatus.PENDING) {
                        return false;
                    }
                    timers.set(i, new WorkflowTimer(
                            timer.id(),
                            timer.workflowInstanceId(),
                            timer.workflowId(),
                            timer.timerId(),
                            timer.fireAt(),
                            TimerStatus.FIRED,
                            timer.createdAt()
                    ));
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void markTimerCancelled(UUID timerId) {
        synchronized (timerLock) {
            for (int i = 0; i < timers.size(); i++) {
                var timer = timers.get(i);
                if (timer.id().equals(timerId) && timer.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(
                            timer.id(),
                            timer.workflowInstanceId(),
                            timer.workflowId(),
                            timer.timerId(),
                            timer.fireAt(),
                            TimerStatus.CANCELLED,
                            timer.createdAt()
                    ));
                    return;
                }
            }
        }
    }

    // ── Test helpers ─────────────────────────────────────────────────────

    /**
     * Returns all stored signals (consumed and unconsumed).
     * Useful for test assertions.
     */
    public List<WorkflowSignal> getAllSignals() {
        synchronized (signalLock) {
            return List.copyOf(signals);
        }
    }

    /**
     * Returns all stored timers.
     * Useful for test assertions.
     */
    public List<WorkflowTimer> getAllTimers() {
        synchronized (timerLock) {
            return List.copyOf(timers);
        }
    }

    /**
     * Clears all data. Useful between test runs.
     */
    public void clear() {
        instances.clear();
        instanceLocks.clear();
        events.clear();
        synchronized (signalLock) {
            signals.clear();
        }
        synchronized (timerLock) {
            timers.clear();
        }
    }
}
