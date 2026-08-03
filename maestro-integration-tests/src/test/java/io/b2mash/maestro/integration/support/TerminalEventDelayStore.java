package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.model.EventType;
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
 * A {@link WorkflowStore} decorator that holds the <em>terminal</em> event
 * append open for a fixed delay, turning an engine timing window into a
 * deterministic one.
 *
 * <h2>The window this widens</h2>
 * <p>{@code WorkflowExecutor} finalises a run with two separate, non-transactional
 * store calls, instance row first:
 *
 * <pre>{@code
 * if (transitionToTerminal(ctx, instance, COMPLETED, output)) {  // UPDATE instance
 *     emit("workflowCompleted", ...);
 *     appendEvent(ctx, EventType.WORKFLOW_COMPLETED, null, output); // INSERT event
 * }
 * }</pre>
 *
 * <p>Between the committed {@code UPDATE} and the committed {@code INSERT} the
 * instance row reads {@code COMPLETED}/{@code FAILED} while the matching
 * {@code WORKFLOW_COMPLETED}/{@code WORKFLOW_FAILED} event is still absent from
 * the log. Any reader that gates on the status and then asserts on the event log
 * can land in that gap and see one event too few. On a developer machine the gap
 * is one database round trip, so the failure surfaces perhaps once in hundreds of
 * runs; on CI it surfaces often enough to redden {@code main}.
 *
 * <p>Wrapping the store in this decorator stretches that gap to whatever the test
 * asks for, so the race is reproducible on demand rather than hoped for. A pin
 * that cannot observe the symptom proves nothing.
 *
 * <p>Only the two run-closing event types are delayed. Activity, signal, timer
 * and compensation events pass straight through, so a workflow under this
 * decorator runs at its normal speed right up to the moment it finishes.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use: the delay is immutable, the counter is an
 * {@link AtomicInteger}, and every other method delegates straight through to
 * the wrapped store, inheriting its guarantees.
 */
public final class TerminalEventDelayStore implements WorkflowStore {

    private final WorkflowStore delegate;
    private final Duration delay;
    private final AtomicInteger delayedAppends = new AtomicInteger();

    /**
     * @param delegate the real store to write through
     * @param delay    how long to hold each terminal event append before it
     *                 reaches the delegate — make this comfortably longer than
     *                 the polling interval of whatever is watching
     */
    public TerminalEventDelayStore(WorkflowStore delegate, Duration delay) {
        this.delegate = delegate;
        this.delay = delay;
    }

    /**
     * @return how many terminal event appends this decorator has delayed —
     *         zero means the window was never actually opened, and any test
     *         relying on it proved nothing
     */
    public int delayedAppends() {
        return delayedAppends.get();
    }

    /**
     * @param type an event type
     * @return whether the event closes a run, and so is written after the
     *         instance row has already turned terminal
     */
    private static boolean isTerminal(EventType type) {
        return type == EventType.WORKFLOW_COMPLETED || type == EventType.WORKFLOW_FAILED;
    }

    @Override
    public void appendEvent(WorkflowEvent event) {
        if (isTerminal(event.eventType())) {
            delayedAppends.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        delegate.appendEvent(event);
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
