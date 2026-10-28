package io.b2mash.maestro.test;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.TerminalWrites;
import io.b2mash.maestro.core.spi.WorkflowStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    /**
     * {@inheritDoc}
     *
     * <p>Performed under the same per-workflow lock {@link #updateInstance}
     * uses, so the version check and both writes are one critical section with
     * respect to every other instance write: once the CAS has passed, nothing
     * else can change the row before it is written. The event is claimed first
     * (an atomic {@code putIfAbsent}, so even an unlocked concurrent
     * {@link #appendEvent} cannot slip in) and the status second, so the write
     * is all-or-nothing and a reader that sees the terminal status always also
     * sees the terminal event. Reads are lock-free, so a reader interleaving its
     * own two reads with this write may see the event a moment before the
     * status — never the reverse.
     */
    @Override
    public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
        TerminalWrites.requireValid(terminal, terminalEvent);
        var lock = instanceLocks.get(terminal.workflowId());
        if (lock == null) {
            throw new WorkflowNotFoundException(terminal.workflowId());
        }

        synchronized (lock) {
            var current = instances.get(terminal.workflowId());
            if (current == null) {
                throw new WorkflowNotFoundException(terminal.workflowId());
            }
            if (current.version() != terminal.version() - 1) {
                return false;
            }
            // Throws DuplicateEventException before the row is touched: nothing is written.
            put(terminalEvent);
            instances.put(terminal.workflowId(), terminal);
            return true;
        }
    }

    // ── Event operations ─────────────────────────────────────────────────

    @Override
    public void appendEvent(WorkflowEvent event) {
        if (event.eventType() == EventType.UNKNOWN) {
            // Mirrors AbstractJdbcWorkflowStore: the sentinel is how the READ
            // path represents a type this build does not know. Persisting it
            // would durably record "unreadable", which every node — upgraded or
            // not — would then stand down on forever.
            throw new IllegalArgumentException(
                    "EventType.UNKNOWN is a read-side sentinel and must never be persisted "
                            + "(workflowInstanceId=%s, sequenceNumber=%d)"
                                    .formatted(event.workflowInstanceId(), event.sequenceNumber()));
        }
        put(event);
    }

    /**
     * Test seam: stores {@code event} <em>without</em> the
     * {@link EventType#UNKNOWN} write guard {@link #appendEvent} applies.
     *
     * <p>This is the in-memory counterpart of the integration suite's raw SQL
     * {@code INSERT} of a future event type: it lets a core-level test plant
     * history that only a <em>newer</em> node could have written — the exact
     * shape an older node meets mid-deploy — and assert that the engine stands
     * the run down instead of recording a failure and compensating.
     *
     * <p>Production code must never call this. The guard in {@link #appendEvent}
     * is what makes the sentinel unable to round-trip; this method deliberately
     * bypasses it and exists only so the guard's <em>consequence</em> can be
     * tested.
     *
     * @param event the raw event to store, sentinel type permitted
     * @throws DuplicateEventException if an event already exists at that sequence
     */
    public void injectRawEvent(WorkflowEvent event) {
        put(event);
    }

    private void put(WorkflowEvent event) {
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

    @Override
    public int deleteFailureEvents(UUID instanceId) {
        var instanceEvents = events.get(instanceId);
        if (instanceEvents == null) {
            return 0;
        }
        // The two failure types plus — only when the WORKFLOW_FAILED payload
        // records a SignalTimeoutException as the cause — the FAILING timeout
        // memo (Issue 19: the highest-sequenced SIGNAL_TIMEOUT, written by the
        // await whose timeout failed the workflow), so a retried await can
        // consume the now-delivered signal. For any other failure cause every
        // SIGNAL_TIMEOUT memo is a CAUGHT gate that must survive, preserving
        // pre-failure replay determinism. Success and compensation memos must
        // survive a retry, or its replay re-runs real side effects.
        // The discriminator is anchored to the payload's exceptionType field —
        // never a whole-payload substring match, which would also fire on a
        // failure whose message merely embeds the FQCN ("... " + e wrapping of
        // a CAUGHT gate timeout) and wrongly delete a caught-gate memo.
        var before = instanceEvents.size();
        var failedByTimeout = instanceEvents.values().stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED && e.payload() != null)
                .anyMatch(e -> {
                    var exceptionType = e.payload().path("exceptionType");
                    return exceptionType.isString()
                            && "io.b2mash.maestro.core.exception.SignalTimeoutException"
                                    .equals(exceptionType.stringValue());
                });
        var failingTimeoutSeq = instanceEvents.values().stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_TIMEOUT)
                .mapToInt(io.b2mash.maestro.core.model.WorkflowEvent::sequenceNumber)
                .max();
        instanceEvents.values().removeIf(event ->
                event.eventType() == EventType.ACTIVITY_FAILED
                        || event.eventType() == EventType.WORKFLOW_FAILED
                        || (failedByTimeout
                                && event.eventType() == EventType.SIGNAL_TIMEOUT
                                && failingTimeoutSeq.isPresent()
                                && event.sequenceNumber() == failingTimeoutSeq.getAsInt()));
        return before - instanceEvents.size();
    }

    // ── Signal operations ────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Keeps the caller's {@link WorkflowSignal#receivedAt()}: every caller
     * of this single-process store shares one clock, so the clock-skew hazard
     * the SPI guards against cannot arise.
     */
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
    public boolean markSignalConsumed(UUID signalId) {
        synchronized (signalLock) {
            for (int i = 0; i < signals.size(); i++) {
                var signal = signals.get(i);
                if (signal.id().equals(signalId) && !signal.consumed()) {
                    signals.set(i, new WorkflowSignal(
                            signal.id(),
                            signal.workflowInstanceId(),
                            signal.workflowId(),
                            signal.signalName(),
                            signal.payload(),
                            true,
                            signal.receivedAt(),
                            // Opaque metadata: a status transition never drops it.
                            signal.traceContext()
                    ));
                    return true;
                }
            }
            return false;
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
                            signal.receivedAt(),
                            // Opaque metadata: adoption never drops it.
                            signal.traceContext()
                    ));
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluated under the signal lock against the current instance map.
     */
    @Override
    public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
        synchronized (signalLock) {
            var purgeable = new HashSet<UUID>();
            for (var instance : instances.values()) {
                var status = instance.status();
                var completedAt = instance.completedAt();
                if ((status == WorkflowStatus.COMPLETED || status == WorkflowStatus.TERMINATED)
                        && completedAt != null && completedAt.isBefore(cutoff)) {
                    purgeable.add(instance.id());
                }
            }
            int before = signals.size();
            // FAILED instances, orphans (null instance id) and consumed rows are
            // never purged — see the SPI Javadoc.
            signals.removeIf(s -> !s.consumed()
                    && s.workflowInstanceId() != null
                    && purgeable.contains(s.workflowInstanceId()));
            return before - signals.size();
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
    public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
        synchronized (timerLock) {
            return timers.stream()
                    .filter(t -> t.workflowInstanceId().equals(workflowInstanceId))
                    .filter(t -> t.timerId().equals(timerId))
                    .findFirst();
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
    public boolean markTimerCancelled(UUID timerId) {
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
                    return true;
                }
            }
            return false;
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
