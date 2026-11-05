package io.b2mash.maestro.test;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.FencedWriteException;
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
import io.b2mash.maestro.core.spi.RunLease;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.TerminalWrites;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

    /**
     * Logical-ID prefix of an {@code awaitSignal} deadline row; the suffix is
     * the sequence of the await's {@code TIMER_SCHEDULED} memo (the layout
     * {@link WorkflowStore#deleteFailureEvents} names).
     */
    private static final String AWAIT_SIGNAL_TIMER_ID_PREFIX = "await-signal-";

    // Lock object per workflowId for updateInstance optimistic locking
    private final ConcurrentHashMap<String, Object> instanceLocks = new ConcurrentHashMap<>();

    // instanceId → workflowId: the fenced overloads are keyed by instance id
    // (a RunLease names the instance, not the business id) and must check the
    // epoch under the same per-workflow monitor the instance writes use.
    private final ConcurrentHashMap<UUID, String> idToWorkflowId = new ConcurrentHashMap<>();

    // ── Instance operations ──────────────────────────────────────────────

    @Override
    public WorkflowInstance createInstance(WorkflowInstance instance) {
        var existing = instances.putIfAbsent(instance.workflowId(), instance);
        if (existing != null) {
            throw new WorkflowAlreadyExistsException(instance.workflowId());
        }
        instanceLocks.putIfAbsent(instance.workflowId(), new Object());
        idToWorkflowId.put(instance.id(), instance.workflowId());
        return instance;
    }

    // ── Lease fencing (Issue 11) ─────────────────────────────────────────

    /**
     * Runs {@code write} under the per-workflow monitor of the lease's
     * instance, after checking that the stored row still carries the lease's
     * epoch. Mirrors the JDBC store's {@code INSERT ... SELECT ... WHERE
     * lease_epoch = ?} + row lock: the check and the write are one critical
     * section with respect to every instance write, so an adoption bump
     * ({@link #updateInstance}) orders strictly before or after them.
     *
     * @return {@code write}'s result if the lease is current; {@code null} if
     *         the lease is stale or the instance is unknown (nothing ran)
     */
    private <T> @Nullable T underCurrentLease(RunLease lease, Supplier<T> write) {
        var workflowId = idToWorkflowId.get(lease.instanceId());
        var lock = workflowId == null ? null : instanceLocks.get(workflowId);
        if (lock == null) {
            return null;
        }
        synchronized (lock) {
            var current = instances.get(workflowId);
            if (current == null || current.leaseEpoch() != lease.epoch()) {
                return null;
            }
            return write.get();
        }
    }

    /** The loud form: a stale lease (or an unknown instance) is a {@link FencedWriteException}. */
    private <T> T requireCurrentLease(RunLease lease, String operation, Supplier<T> write) {
        var result = underCurrentLease(lease, write);
        if (result == null) {
            throw new FencedWriteException(lease.instanceId(), lease.epoch(), operation);
        }
        return result;
    }

    @Override
    public void appendEvent(WorkflowEvent event, RunLease lease) {
        requireCurrentLease(lease, "appendEvent", () -> {
            appendEvent(event);
            return Boolean.TRUE;
        });
    }

    // Lock order. Every multi-lock path takes the DATA lock (signalLock,
    // timerLock) before the per-workflow instance lock — the order the purge
    // uses (signalLock → timerLock → instanceLock). The fenced data writes
    // below therefore enter their data lock OUTSIDE underCurrentLease; the
    // inner unfenced call re-takes it reentrantly. Taking the instance lock
    // first would let a purge concurrent with a fenced timer or signal write
    // deadlock both.

    @Override
    public void saveTimer(WorkflowTimer timer, RunLease lease) {
        synchronized (timerLock) {
            requireCurrentLease(lease, "saveTimer", () -> {
                saveTimer(timer);
                return Boolean.TRUE;
            });
        }
    }

    @Override
    public void updateInstance(WorkflowInstance instance, RunLease lease) {
        if (idToWorkflowId.get(lease.instanceId()) == null) {
            throw new WorkflowNotFoundException(instance.workflowId());
        }
        // Same monitor as the unfenced form, so the epoch check and the version
        // CAS are one critical section; the inner call re-takes it reentrantly.
        requireCurrentLease(lease, "updateInstance", () -> {
            updateInstance(instance);
            return Boolean.TRUE;
        });
    }

    @Override
    public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent, RunLease lease) {
        if (idToWorkflowId.get(lease.instanceId()) == null) {
            throw new WorkflowNotFoundException(terminal.workflowId());
        }
        return requireCurrentLease(lease, "completeInstance", () -> completeInstance(terminal, terminalEvent));
    }

    @Override
    public boolean markSignalConsumed(UUID signalId, RunLease lease) {
        synchronized (signalLock) {
            return Boolean.TRUE.equals(underCurrentLease(lease, () -> markSignalConsumed(signalId)));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Atomic: under the signal lock and then the lease's instance lock (the
     * purge's order), the row is checked, the memo inserted (a duplicate
     * sequence throws before anything is written) and the row flipped — one
     * critical section with respect to every other consumer, delivery and
     * adoption bump.
     */
    @Override
    public boolean appendEventAndConsumeSignal(WorkflowEvent memo, RunLease lease, UUID signalId) {
        synchronized (signalLock) {
            return requireCurrentLease(lease, "appendEventAndConsumeSignal", () -> {
                var unconsumed = signals.stream().anyMatch(s -> s.id().equals(signalId) && !s.consumed());
                if (!unconsumed) {
                    return Boolean.FALSE;
                }
                appendEvent(memo); // throws DuplicateEventException before the flip
                markSignalConsumed(signalId);
                return Boolean.TRUE;
            });
        }
    }

    @Override
    public boolean markTimerFired(UUID timerId, RunLease lease) {
        synchronized (timerLock) {
            return Boolean.TRUE.equals(underCurrentLease(lease, () -> markTimerFired(timerId)));
        }
    }

    @Override
    public boolean markTimerCancelled(UUID timerId, RunLease lease) {
        synchronized (timerLock) {
            return Boolean.TRUE.equals(underCurrentLease(lease, () -> markTimerCancelled(timerId)));
        }
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
        if (failedByTimeout && failingTimeoutSeq.isPresent()) {
            var timeoutSeq = failingTimeoutSeq.getAsInt();
            var failingTimeout = instanceEvents.remove(timeoutSeq);
            // R61: the failing await's spent deadline goes with it — its
            // TIMER_SCHEDULED park memo in the slot just before (same step
            // name) and its deadline rows, any status — so the retried await
            // arms a fresh full timeout instead of resuming past the old one.
            var parkMemo = instanceEvents.get(timeoutSeq - 1);
            if (failingTimeout != null && failingTimeout.stepName() != null && parkMemo != null
                    && parkMemo.eventType() == EventType.TIMER_SCHEDULED
                    && failingTimeout.stepName().equals(parkMemo.stepName())) {
                instanceEvents.remove(timeoutSeq - 1);
                var timerId = AWAIT_SIGNAL_TIMER_ID_PREFIX + (timeoutSeq - 1);
                synchronized (timerLock) {
                    timers.removeIf(t -> t.workflowInstanceId().equals(instanceId)
                            && t.timerId().equals(timerId));
                }
            }
        }
        instanceEvents.values().removeIf(event ->
                event.eventType() == EventType.ACTIVITY_FAILED
                        || event.eventType() == EventType.WORKFLOW_FAILED);
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

    /**
     * {@inheritDoc}
     *
     * <p>The identity check and the insert are one critical section under the
     * signal lock — the in-memory counterpart of the JDBC store's partial
     * unique index on {@code (workflow_id, signal_name, message_id)}.
     */
    @Override
    public boolean saveSignalIfAbsent(WorkflowSignal signal) {
        synchronized (signalLock) {
            var messageId = signal.messageId();
            if (messageId != null && signals.stream().anyMatch(s ->
                    signal.workflowId().equals(s.workflowId())
                            && signal.signalName().equals(s.signalName())
                            && messageId.equals(s.messageId()))) {
                return false;
            }
            signals.add(signal);
            return true;
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
                            // Metadata: a status transition never drops it.
                            signal.traceContext(),
                            signal.messageId()
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
                            // Metadata: adoption never drops it.
                            signal.traceContext(),
                            signal.messageId()
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

    // ── Retention ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Performed under the signal and timer locks, and each candidate's own
     * per-workflow lock: the candidate's status and {@code completedAt} are
     * re-checked under that lock immediately before it is removed, so a
     * concurrent retry (which writes under the same lock) can never leave a
     * now-active workflow with its history gone. Where the JDBC store rolls
     * the whole batch back on that race, this store simply skips the one
     * instance that stopped qualifying — the same guarantee, without a
     * transaction to roll back.
     */
    @Override
    public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("Only terminal statuses can be purged, got " + status);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, got " + batchSize);
        }
        synchronized (signalLock) {
            synchronized (timerLock) {
                var candidates = instances.values().stream()
                        .filter(i -> qualifies(i, status, cutoff))
                        .sorted(Comparator.comparing(WorkflowInstance::completedAt))
                        .limit(batchSize)
                        .toList();
                int removedInstances = 0;
                int removedEvents = 0;
                int removedSignals = 0;
                int removedTimers = 0;
                for (var candidate : candidates) {
                    var lock = instanceLocks.get(candidate.workflowId());
                    if (lock == null) {
                        continue; // removed concurrently
                    }
                    synchronized (lock) {
                        var current = instances.get(candidate.workflowId());
                        if (current == null || !qualifies(current, status, cutoff)) {
                            continue; // retried (or purged) since the candidate select
                        }
                        var id = current.id();
                        var instanceEvents = events.remove(id);
                        removedEvents += instanceEvents == null ? 0 : instanceEvents.size();
                        int signalsBefore = signals.size();
                        signals.removeIf(s -> id.equals(s.workflowInstanceId()));
                        removedSignals += signalsBefore - signals.size();
                        int timersBefore = timers.size();
                        timers.removeIf(t -> id.equals(t.workflowInstanceId()));
                        removedTimers += timersBefore - timers.size();
                        instances.remove(current.workflowId());
                        instanceLocks.remove(current.workflowId());
                        // A lease on a purged instance must look unknown, not stale.
                        idToWorkflowId.remove(id);
                        removedInstances++;
                    }
                }
                return new PurgeResult(removedInstances, removedEvents, removedSignals, removedTimers);
            }
        }
    }

    private static boolean qualifies(WorkflowInstance instance, WorkflowStatus status, Instant cutoff) {
        var completedAt = instance.completedAt();
        return instance.status() == status && completedAt != null && completedAt.isBefore(cutoff);
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
        idToWorkflowId.clear();
        events.clear();
        synchronized (signalLock) {
            signals.clear();
        }
        synchronized (timerLock) {
            timers.clear();
        }
    }
}
