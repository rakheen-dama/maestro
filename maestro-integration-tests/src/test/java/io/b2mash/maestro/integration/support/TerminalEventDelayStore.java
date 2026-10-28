package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WorkflowStore} decorator that holds the engine's <em>terminal</em>
 * write open for a fixed delay, turning an engine timing window into a
 * deterministic one.
 *
 * <h2>The window this used to widen, and what it proves now</h2>
 * <p>Before Issue 26, {@code WorkflowExecutor} finalised a run with two separate,
 * non-transactional store calls, instance row first: an {@code UPDATE} to
 * {@code COMPLETED}/{@code FAILED}, then the {@code INSERT} of the matching
 * {@code WORKFLOW_COMPLETED}/{@code WORKFLOW_FAILED} event. Between the two, a
 * reader that gated on the status and then asserted on the event log could see
 * one event too few — on CI often enough to redden {@code main} (GitHub Actions
 * run 30728290264). This decorator used to delay the event append to stretch
 * that gap to seconds.
 *
 * <p>The engine now finalises with one atomic
 * {@link WorkflowStore#completeInstance} call, so there is no gap to stretch.
 * The delay moved onto that call, <em>before</em> it reaches the delegate: the
 * run is held for seconds right at its finalisation, and a status poll that
 * <em>does</em> observe a terminal status proves the terminal event landed in
 * the same write — the regression pin for Issue 26.
 *
 * <p>Every other call — activity, signal, timer and compensation events, and
 * non-terminal status writes — passes straight through, so a workflow under
 * this decorator runs at its normal speed right up to the moment it finishes.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use: the delay is immutable, the counter is an
 * {@link AtomicInteger}, and every other method delegates straight through to
 * the wrapped store, inheriting its guarantees.
 */
public final class TerminalEventDelayStore implements WorkflowStore {

    private final WorkflowStore delegate;
    private final Duration delay;
    private final AtomicInteger delayedCompletions = new AtomicInteger();

    /**
     * @param delegate the real store to write through
     * @param delay    how long to hold each terminal write before it reaches
     *                 the delegate — make this comfortably longer than the
     *                 polling interval of whatever is watching
     */
    public TerminalEventDelayStore(WorkflowStore delegate, Duration delay) {
        this.delegate = delegate;
        this.delay = delay;
    }

    /**
     * @return how many terminal writes this decorator has delayed — zero means
     *         the window was never actually opened, and any test relying on it
     *         proved nothing
     */
    public int delayedCompletions() {
        return delayedCompletions.get();
    }

    @Override
    public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
        delayedCompletions.incrementAndGet();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return delegate.completeInstance(terminal, terminalEvent);
    }

    // ── straight delegation ───────────────────────────────────────────────

    @Override
    public WorkflowInstance createInstance(WorkflowInstance instance) {
        return delegate.createInstance(instance);
    }

    @Override
    public Optional<WorkflowInstance> getInstance(String workflowId) {
        return delegate.getInstance(workflowId);
    }

    @Override
    public List<WorkflowInstance> getRecoverableInstances() {
        return delegate.getRecoverableInstances();
    }

    @Override
    public void updateInstance(WorkflowInstance instance) {
        delegate.updateInstance(instance);
    }

    @Override
    public void appendEvent(WorkflowEvent event) {
        delegate.appendEvent(event);
    }

    @Override
    public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
        return delegate.getEventBySequence(instanceId, sequenceNumber);
    }

    @Override
    public List<WorkflowEvent> getEvents(UUID instanceId) {
        return delegate.getEvents(instanceId);
    }

    @Override
    public int deleteFailureEvents(UUID instanceId) {
        return delegate.deleteFailureEvents(instanceId);
    }

    @Override
    public void saveSignal(WorkflowSignal signal) {
        delegate.saveSignal(signal);
    }

    @Override
    public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
        return delegate.getUnconsumedSignals(workflowId, signalName);
    }

    @Override
    public boolean markSignalConsumed(UUID signalId) {
        return delegate.markSignalConsumed(signalId);
    }

    @Override
    public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        delegate.adoptOrphanedSignals(workflowId, instanceId);
    }

    @Override
    public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
        return delegate.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff);
    }

    @Override
    public void saveTimer(WorkflowTimer timer) {
        delegate.saveTimer(timer);
    }

    @Override
    public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
        return delegate.getDueTimers(now, batchSize);
    }

    @Override
    public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
        return delegate.findTimer(workflowInstanceId, timerId);
    }

    @Override
    public boolean markTimerFired(UUID timerId) {
        return delegate.markTimerFired(timerId);
    }

    @Override
    public boolean markTimerCancelled(UUID timerId) {
        return delegate.markTimerCancelled(timerId);
    }
}
