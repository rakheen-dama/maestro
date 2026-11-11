package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.RunLease;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link VersionedInMemoryStore} with per-method fault injection, modelling a
 * store that is down or flapping (modelled on
 * {@code ParkedProbeStoreOutageTest.OutageStore}).
 *
 * <p>Faults are keyed by method name — the lease overload of a method
 * delegates to its unfenced form here, so one key covers both. Supported
 * methods: {@code getInstance}, {@code updateInstance}, {@code completeInstance},
 * {@code appendEvent}, {@code getEventBySequence}, {@code getEvents},
 * {@code getUnconsumedSignals}, {@code markSignalConsumed},
 * {@code appendEventAndConsumeSignal}, {@code saveTimer}, {@code findTimer},
 * {@code markTimerFired}, {@code markTimerCancelled}. The composite writes
 * of the base fixture call its component methods, so {@code completeInstance}
 * also passes the {@code updateInstance} and {@code appendEvent} hooks, and
 * {@code appendEventAndConsumeSignal} the {@code appendEvent} and
 * {@code markSignalConsumed} hooks (and counters).
 *
 * <ul>
 *   <li>{@link #failNext(String, int)} — the next {@code times} calls throw
 *       {@link StoreUnavailableException} without touching the store.</li>
 *   <li>{@link #failNext(String, int, Supplier)} — the same, throwing the
 *       supplied fault ({@code RuntimeException} or {@code Error}).</li>
 *   <li>{@link #commitThenFailNext(String, int)} — the next {@code times}
 *       calls perform the write and THEN throw {@code StoreUnavailableException}:
 *       a commit whose acknowledgement was lost in flight.</li>
 *   <li>{@link #failAlways(String)} / {@link #failWhen(String, Predicate)} —
 *       a persistent fault, for every call or for calls whose arguments match,
 *       until {@link #heal()}.</li>
 * </ul>
 *
 * <p>{@link #calls(String)} counts every call of a method, faulted or not.
 *
 * <p><b>Thread safety:</b> thread-safe; fault configuration and counters are
 * concurrent maps of atomics.
 */
final class FaultyStore extends VersionedInMemoryStore {

    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Fault> failBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failAfter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Predicate<Object[]>> persistent = new ConcurrentHashMap<>();

    private record Fault(AtomicInteger remaining, Supplier<? extends Throwable> fault) {}

    /** The next {@code times} calls of {@code method} throw {@link StoreUnavailableException}. */
    void failNext(String method, int times) {
        failNext(method, times, () -> unavailable(method));
    }

    /** The next {@code times} calls of {@code method} throw {@code fault} (unchecked only). */
    void failNext(String method, int times, Supplier<? extends Throwable> fault) {
        failBefore.put(method, new Fault(new AtomicInteger(times), fault));
    }

    /** The next {@code times} calls of {@code method} take effect, then throw {@link StoreUnavailableException}. */
    void commitThenFailNext(String method, int times) {
        failAfter.put(method, new AtomicInteger(times));
    }

    /** Every call of {@code method} throws {@link StoreUnavailableException} until {@link #heal()}. */
    void failAlways(String method) {
        failWhen(method, args -> true);
    }

    /** Every call of {@code method} whose arguments match throws {@link StoreUnavailableException} until {@link #heal()}. */
    void failWhen(String method, Predicate<Object[]> args) {
        persistent.put(method, args);
    }

    /** Clears every configured fault; call counters are kept. */
    void heal() {
        failBefore.clear();
        failAfter.clear();
        persistent.clear();
    }

    /** How many times {@code method} has been called, faulted or not. */
    int calls(String method) {
        var count = calls.get(method);
        return count == null ? 0 : count.get();
    }

    private void before(String method, Object... args) {
        calls.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
        var fault = failBefore.get(method);
        if (fault != null && fault.remaining().getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throwUnchecked(fault.fault().get());
        }
        var predicate = persistent.get(method);
        if (predicate != null && predicate.test(args)) {
            throw unavailable(method);
        }
    }

    private void after(String method) {
        var remaining = failAfter.get(method);
        if (remaining != null && remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw unavailable(method);
        }
    }

    private static StoreUnavailableException unavailable(String method) {
        return new StoreUnavailableException("injected store outage in " + method,
                new RuntimeException("connection refused (injected)"));
    }

    private static void throwUnchecked(Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        throw new IllegalStateException("FaultyStore can only inject unchecked faults", t);
    }

    // ── Instances ───────────────────────────────────────────────────────

    @Override
    public Optional<WorkflowInstance> getInstance(String workflowId) {
        before("getInstance", workflowId);
        var result = super.getInstance(workflowId);
        after("getInstance");
        return result;
    }

    @Override
    public synchronized void updateInstance(WorkflowInstance instance) {
        before("updateInstance", instance);
        super.updateInstance(instance);
        after("updateInstance");
    }

    @Override
    public synchronized boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
        before("completeInstance", terminal, terminalEvent);
        var result = super.completeInstance(terminal, terminalEvent);
        after("completeInstance");
        return result;
    }

    // ── Events ──────────────────────────────────────────────────────────

    @Override
    public void appendEvent(WorkflowEvent event) {
        before("appendEvent", event);
        super.appendEvent(event);
        after("appendEvent");
    }

    @Override
    public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
        before("getEventBySequence", instanceId, sequenceNumber);
        var result = super.getEventBySequence(instanceId, sequenceNumber);
        after("getEventBySequence");
        return result;
    }

    @Override
    public List<WorkflowEvent> getEvents(UUID instanceId) {
        before("getEvents", instanceId);
        var result = super.getEvents(instanceId);
        after("getEvents");
        return result;
    }

    // ── Signals ─────────────────────────────────────────────────────────

    @Override
    public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
        before("getUnconsumedSignals", workflowId, signalName);
        var result = super.getUnconsumedSignals(workflowId, signalName);
        after("getUnconsumedSignals");
        return result;
    }

    @Override
    public boolean markSignalConsumed(UUID signalId) {
        before("markSignalConsumed", signalId);
        var result = super.markSignalConsumed(signalId);
        after("markSignalConsumed");
        return result;
    }

    @Override
    public boolean appendEventAndConsumeSignal(WorkflowEvent memo, RunLease lease, UUID signalId) {
        before("appendEventAndConsumeSignal", memo, lease, signalId);
        var result = super.appendEventAndConsumeSignal(memo, lease, signalId);
        after("appendEventAndConsumeSignal");
        return result;
    }

    // ── Timers ──────────────────────────────────────────────────────────

    @Override
    public void saveTimer(WorkflowTimer timer) {
        before("saveTimer", timer);
        super.saveTimer(timer);
        after("saveTimer");
    }

    @Override
    public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
        before("findTimer", workflowInstanceId, timerId);
        var result = super.findTimer(workflowInstanceId, timerId);
        after("findTimer");
        return result;
    }

    @Override
    public boolean markTimerFired(UUID timerId) {
        before("markTimerFired", timerId);
        var result = super.markTimerFired(timerId);
        after("markTimerFired");
        return result;
    }

    @Override
    public boolean markTimerCancelled(UUID timerId) {
        before("markTimerCancelled", timerId);
        var result = super.markTimerCancelled(timerId);
        after("markTimerCancelled");
        return result;
    }
}
