package io.maestro.test;

import io.maestro.core.spi.SignalMessage;
import io.maestro.core.spi.TaskMessage;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory {@link WorkflowMessaging} for tests.
 *
 * <p>Records lifecycle events for assertion. Task and signal publish/subscribe
 * are no-ops — in a single-JVM test environment, the
 * {@link TestWorkflowEnvironment} invokes the workflow executor directly.
 *
 * <p><b>Thread safety:</b> {@link CopyOnWriteArrayList} ensures safe
 * concurrent reads and writes.
 */
public final class InMemoryWorkflowMessaging implements WorkflowMessaging {

    private final CopyOnWriteArrayList<WorkflowLifecycleEvent> lifecycleEvents = new CopyOnWriteArrayList<>();

    @Override
    public void publishTask(String taskQueue, TaskMessage message) {
        // No-op: TestWorkflowEnvironment calls executor.startWorkflow directly
    }

    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        // No-op: single-JVM, signals delivered via executor.deliverSignal
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        lifecycleEvents.add(event);
    }

    @Override
    public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        // No-op: no message broker in test mode
    }

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        // No-op: no message broker in test mode
    }

    // ── Test helpers ─────────────────────────────────────────────────────

    /**
     * Returns all recorded lifecycle events.
     */
    public List<WorkflowLifecycleEvent> getLifecycleEvents() {
        return List.copyOf(lifecycleEvents);
    }

    /**
     * Clears all recorded events.
     */
    public void clear() {
        lifecycleEvents.clear();
    }
}
