package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryWorkflowStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delegates every {@link WorkflowStore} operation to an in-memory backing
 * store, letting test doubles override just the one method they care about
 * (e.g. {@link #getInstance(String)}) without re-implementing the whole SPI.
 */
abstract class DelegatingWorkflowStore implements WorkflowStore {

    private final InMemoryWorkflowStore delegate = new InMemoryWorkflowStore();

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
