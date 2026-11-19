package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the four node-local gauges: {@code maestro.workflows.running}
 * and {@code maestro.workflows.parked} from the observability design doc
 * §2.2 / §2.3, {@code maestro.wake.permits} and {@code maestro.recovery.deferred};
 * and the function counter {@code maestro.observer.errors} (Issue 81), which
 * reads {@link WorkflowExecutor#observerErrorCount()} — counted by the
 * executor, not through an observer, because the observer that failed may be
 * the Micrometer one.
 *
 * <p>Kept as a separate holder bean from {@link MicrometerEngineObserver}
 * because gauges need the {@code WorkflowExecutor} bean, while the counters
 * and timers in {@code MicrometerEngineObserver} need only the {@link
 * MeterRegistry} — mixing the two would create a circular executor→observer→
 * executor construction dependency (the {@code EngineObserver} handed to the
 * executor's builder must never itself hold a reference back to that
 * executor).
 *
 * <h2>Why node-local gauges, not store-polling</h2>
 * These gauge <em>this JVM's</em> in-flight/parked workflow counts — what an
 * operator dashboards per-pod and sums for a cluster total — rather than
 * polling the store for a cluster-wide {@code COUNT(*)} on every scrape
 * (which would put load on every node's scrape, report identical numbers
 * from every node, and couple scrape latency to store health). See the
 * design doc §2.3 for the full rationale.
 *
 * <h2>Thread safety</h2>
 * Immutable after construction; the registered gauges read {@link
 * WorkflowExecutor}'s own thread-safe counters on every scrape.
 *
 * <h2>Why {@code executor} is a field (fix round 1, F5)</h2>
 * {@link Gauge.Builder} holds its state object behind a {@code WeakReference}
 * by default — the gauge silently starts reporting {@code NaN} the moment
 * nothing else keeps that object reachable. Without a field here, the only
 * thing keeping {@code executor} alive was the Spring context's own
 * singleton reference, which happens to be true today but is an incidental
 * fact about the container, not an invariant of this class — a future
 * refactor (e.g. a factory that hands out short-lived executor views) could
 * silently turn every gauge into {@code NaN} with no test able to tell the
 * difference from a correctly-wired one, since the constructor would still
 * run and {@code .register(...)} would still succeed. Keeping a {@code
 * final} field makes {@code MaestroEngineGauges} itself a strong root for
 * as long as it exists (which, as a Spring-managed singleton, is the
 * application's lifetime), independent of whatever else the container is
 * doing with the executor.
 */
public final class MaestroEngineGauges {

    private static double failing(WorkflowExecutor executor, String loop) {
        for (var snapshot : executor.loops()) {
            if (snapshot.name().equals(loop)) {
                return snapshot.failing() ? 1.0 : 0.0;
            }
        }
        return 0.0;
    }

    private final WorkflowExecutor executor;

    /**
     * Registers the four gauges {@code maestro.workflows.running}, {@code
     * maestro.workflows.parked}, {@code maestro.wake.permits} and {@code
     * maestro.recovery.deferred}, and the function counter {@code
     * maestro.observer.errors}, against {@code executor}.
     *
     * @param registry the Micrometer registry the gauges are registered against
     * @param executor the executor whose {@link WorkflowExecutor#runningCount()},
     *                 {@link WorkflowExecutor#parkedCount()},
     *                 {@link WorkflowExecutor#wakePermitCount()},
     *                 {@link WorkflowExecutor#recoveryDeferredCount()} and
     *                 {@link WorkflowExecutor#observerErrorCount()} back the meters
     */
    public MaestroEngineGauges(MeterRegistry registry, WorkflowExecutor executor) {
        this.executor = executor;
        Gauge.builder("maestro.workflows.running", this.executor, WorkflowExecutor::runningCount)
                .strongReference(true)
                .register(registry);
        Gauge.builder("maestro.workflows.parked", this.executor, WorkflowExecutor::parkedCount)
                .strongReference(true)
                .register(registry);
        Gauge.builder("maestro.wake.permits", this.executor, WorkflowExecutor::wakePermitCount)
                .description("Wake permits held on this node for workflows that have not parked yet")
                .strongReference(true)
                .register(registry);
        Gauge.builder("maestro.recovery.deferred", this.executor, WorkflowExecutor::recoveryDeferredCount)
                .description("Recoverable workflows this node's latest full recovery pass left unadopted "
                        + "for lack of task-queue capacity")
                .strongReference(true)
                .register(registry);
        // One series per supervised loop (P2 task 5): 1 while its snapshot reads
        // failing (its FailingRule holds), 0 otherwise or before it starts.
        for (var loop : MicrometerEngineObserver.ALL_LOOPS) {
            Gauge.builder("maestro.loop.failing", this.executor, e -> failing(e, loop))
                    .tag("loop", loop)
                    .description("1 while this background loop's failure streak counts as failing")
                    .strongReference(true)
                    .register(registry);
        }
        // No tags: the log names the callback and the delegate.
        FunctionCounter.builder("maestro.observer.errors", this.executor, WorkflowExecutor::observerErrorCount)
                .description("Observer callbacks that threw and were contained (logged WARN once per callback "
                        + "and delegate)")
                .register(registry);
    }
}
