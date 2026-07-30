package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.b2mash.maestro.core.TestEventLogs.removeFailureEvents;

/**
 * In-memory {@link WorkflowStore} that enforces the two durable invariants the
 * admin-command tests depend on, which the simpler per-test fakes elsewhere in
 * this package deliberately skip:
 *
 * <ul>
 *   <li><b>Optimistic locking</b> on {@code updateInstance} — a write whose
 *       {@code version - 1} does not match the stored version is rejected with
 *       {@link OptimisticLockException}. Retry's compare-and-set and
 *       terminate's converge-loop are both arbitrated by exactly this, so a
 *       fake that blindly overwrites cannot test them.</li>
 *   <li><b>{@code (workflowInstanceId, sequenceNumber)} uniqueness</b> on
 *       {@code appendEvent} — a duplicate append is rejected with
 *       {@link DuplicateEventException}, so a replay that wrongly re-executes a
 *       memoized step is caught rather than silently duplicating a row.</li>
 * </ul>
 *
 * <p>Fault injection is limited to what the tests need: {@link #failNextUpdates}
 * forces a configurable number of {@code updateInstance} calls to conflict, and
 * {@link #updateAttempts()} exposes how often the row was written.
 *
 * <p><b>Thread safety:</b> thread-safe. Instance writes are serialised on this
 * object (the version check and the write must be atomic to model a real CAS);
 * event, signal and timer collections are copy-on-write.
 */
class VersionedInMemoryStore implements WorkflowStore {

    private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WorkflowSignal> signals = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();
    private final AtomicInteger updateAttempts = new AtomicInteger();
    private final AtomicInteger updatesToFail = new AtomicInteger();

    // ── Instances ───────────────────────────────────────────────────────

    @Override
    public WorkflowInstance createInstance(WorkflowInstance instance) {
        var prev = byWorkflowId.putIfAbsent(instance.workflowId(), instance);
        if (prev != null) {
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
    public synchronized void updateInstance(WorkflowInstance instance) {
        updateAttempts.incrementAndGet();
        if (updatesToFail.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new OptimisticLockException(instance.workflowId(), instance.version() - 1, -1);
        }
        var stored = byWorkflowId.get(instance.workflowId());
        if (stored == null) {
            throw new IllegalStateException(
                    "cannot update an instance that was never created: " + instance.workflowId());
        }
        if (stored.version() != instance.version() - 1) {
            throw new OptimisticLockException(
                    instance.workflowId(), instance.version() - 1, stored.version());
        }
        byWorkflowId.put(instance.workflowId(), instance);
    }

    /**
     * Writes a status straight into the row, bypassing the version the caller
     * would have read — how these tests model "another node got there first".
     *
     * @param workflowId the workflow to rewrite
     * @param status     the status to force
     */
    synchronized void forceStatus(String workflowId, WorkflowStatus status) {
        var stored = byWorkflowId.get(workflowId);
        if (stored == null) {
            throw new IllegalStateException("no such instance: " + workflowId);
        }
        byWorkflowId.put(workflowId, stored.toBuilder()
                .status(status)
                .completedAt(status.isTerminal() ? Instant.now() : null)
                .updatedAt(Instant.now())
                .version(stored.version() + 1)
                .build());
    }

    /** @param count how many of the next {@code updateInstance} calls must conflict */
    void failNextUpdates(int count) {
        updatesToFail.set(count);
    }

    /** @return how many times {@code updateInstance} has been called */
    int updateAttempts() {
        return updateAttempts.get();
    }

    // ── Events ──────────────────────────────────────────────────────────

    @Override
    public void appendEvent(WorkflowEvent event) {
        synchronized (events) {
            var duplicate = events.stream().anyMatch(
                    e -> e.workflowInstanceId().equals(event.workflowInstanceId())
                            && e.sequenceNumber() == event.sequenceNumber());
            if (duplicate) {
                throw new DuplicateEventException(
                        event.workflowInstanceId(), event.sequenceNumber());
            }
            events.add(event);
        }
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
    public int deleteFailureEvents(UUID instanceId) {
        synchronized (events) {
            return removeFailureEvents(events, instanceId);
        }
    }

    // ── Signals ─────────────────────────────────────────────────────────

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
        synchronized (signals) {
            for (var i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.id().equals(signalId) && !s.consumed()) {
                    signals.set(i, new WorkflowSignal(s.id(), s.workflowInstanceId(), s.workflowId(),
                            s.signalName(), s.payload(), true, s.receivedAt()));
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        synchronized (signals) {
            for (var i = 0; i < signals.size(); i++) {
                var s = signals.get(i);
                if (s.workflowId().equals(workflowId) && s.workflowInstanceId() == null) {
                    signals.set(i, new WorkflowSignal(s.id(), instanceId, s.workflowId(),
                            s.signalName(), s.payload(), s.consumed(), s.receivedAt()));
                }
            }
        }
    }

    /** @return every signal row ever saved, oldest first */
    List<WorkflowSignal> allSignals() {
        return List.copyOf(signals);
    }

    // ── Timers ──────────────────────────────────────────────────────────

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
    public boolean markTimerCancelled(UUID timerId) {
        return transitionTimer(timerId, TimerStatus.CANCELLED);
    }

    private boolean transitionTimer(UUID timerId, TimerStatus target) {
        synchronized (timers) {
            for (var i = 0; i < timers.size(); i++) {
                var t = timers.get(i);
                if (t.id().equals(timerId) && t.status() == TimerStatus.PENDING) {
                    timers.set(i, new WorkflowTimer(t.id(), t.workflowInstanceId(), t.workflowId(),
                            t.timerId(), t.fireAt(), target, t.createdAt()));
                    return true;
                }
            }
            return false;
        }
    }
}
