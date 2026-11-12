package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.TaskQueueGovernor;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.spring.config.WorkflowRegistrar;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.TreeSet;

/**
 * Registers {@code maestro.taskqueue.slots.used{queue, kind}}: how many of a
 * task queue's per-node slots are held right now, read from the executor's
 * {@link TaskQueueGovernor}. {@code kind=workflow} counts runs executing on
 * the queue (a run parked on {@code sleep()} or {@code awaitSignal()} holds
 * no slot); {@code kind=activity} counts live activity executions (a replayed
 * step holds none). Compare against the queue's configured
 * {@code maestro.worker.*} limits to see saturation.
 *
 * <h2>Which queues get a gauge</h2>
 * One pair of gauges per distinct {@code taskQueue} of a registered
 * {@code @DurableWorkflow} that is <em>bounded</em> on this node — a queue
 * listed in {@code maestro.worker.task-queues}, or any queue under the
 * {@code maestro.worker.default-*} limits. An unbounded queue (both defaults
 * {@code 0} and not listed) has no slots and no gauge. A queue listed in
 * configuration that no registered workflow names has no gauge either, so
 * the {@code queue} tag's values are a closed set fixed at startup.
 *
 * <h2>Why registration waits for {@link ContextRefreshedEvent}</h2>
 * The queues come from {@link WorkflowRegistrar#getRegistrations()}, which
 * the registrar fills in {@code afterSingletonsInstantiated} — after this bean
 * is constructed, and in no order relative to any other bean's callback. By
 * {@code ContextRefreshedEvent} every {@code afterSingletonsInstantiated} has
 * run, so the registrations are complete. Registration is idempotent:
 * Micrometer deduplicates meters by id, so a second refresh event (a child
 * context's, propagated to this listener) registers nothing new.
 *
 * <h2>Node-local</h2>
 * Slots are per node, so are these gauges: each pod reports its own held
 * slots, and a cluster total is their sum across pods.
 *
 * <h2>Thread safety</h2>
 * Thread-safe. Each gauge reads the governor's semaphores with a non-blocking
 * call that never takes or waits for a slot, so a scrape never contends with a
 * workflow. The presence check at registration has already created a
 * default-limited queue's pools, so a scrape never creates one.
 */
public final class MaestroTaskQueueGauges implements ApplicationListener<ContextRefreshedEvent> {

    private static final String NAME = "maestro.taskqueue.slots.used";

    private final MeterRegistry registry;
    private final TaskQueueGovernor governor;
    private final WorkflowRegistrar registrar;

    /**
     * Captures what the gauges need; registers nothing until
     * {@link #onApplicationEvent}.
     *
     * @param registry  the Micrometer registry the gauges are registered against
     * @param executor  the executor whose {@link WorkflowExecutor#taskQueueGovernor()}
     *                  backs the gauges
     * @param registrar the source of the registered workflows' task queues
     */
    public MaestroTaskQueueGauges(MeterRegistry registry, WorkflowExecutor executor, WorkflowRegistrar registrar) {
        this.registry = registry;
        this.governor = executor.taskQueueGovernor();
        this.registrar = registrar;
    }

    /**
     * Registers a {@code kind=workflow} and a {@code kind=activity} gauge for
     * each bounded task queue a registered workflow names. Idempotent.
     *
     * @param event the context refresh
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        var queues = new TreeSet<String>();
        for (WorkflowRegistration registration : registrar.getRegistrations().values()) {
            queues.add(registration.taskQueue());
        }
        for (var queue : queues) {
            if (governor.workflowSlotsInUse(queue).isEmpty()) {
                continue; // unbounded on this node: no slots to count
            }
            // strongReference: the default weak reference to the state object
            // turns a gauge into NaN once nothing else keeps it reachable.
            Gauge.builder(NAME, governor, g -> g.workflowSlotsInUse(queue).orElse(0))
                    .tag("queue", queue)
                    .tag("kind", "workflow")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(NAME, governor, g -> g.activitySlotsInUse(queue).orElse(0))
                    .tag("queue", queue)
                    .tag("kind", "activity")
                    .strongReference(true)
                    .register(registry);
        }
    }
}
