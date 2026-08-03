package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the two node-local gauges from the observability design doc
 * §2.2 / §2.3: {@code maestro.workflows.running} and {@code
 * maestro.workflows.parked}.
 *
 * <p>Kept as a separate holder bean from {@link MicrometerEngineObserver}
 * because gauges need the {@code WorkflowExecutor} bean, while the counters
 * and timers in {@code MicrometerEngineObserver} need only the {@link
 * MeterRegistry} — mixing the two would create a circular executor→observer→
 * executor construction dependency (the {@code EngineObserver} handed to the
 * executor's constructor must never itself hold a reference back to that
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
 * silently turn both gauges into {@code NaN} with no test able to tell the
 * difference from a correctly-wired one, since the constructor would still
 * run and {@code .register(...)} would still succeed. Keeping a {@code
 * final} field makes {@code MaestroEngineGauges} itself a strong root for
 * as long as it exists (which, as a Spring-managed singleton, is the
 * application's lifetime), independent of whatever else the container is
 * doing with the executor.
 */
public final class MaestroEngineGauges {

    private final WorkflowExecutor executor;

    /**
     * Registers {@code maestro.workflows.running} and {@code
     * maestro.workflows.parked} against {@code executor}.
     *
     * @param registry the Micrometer registry the gauges are registered against
     * @param executor the executor whose {@link WorkflowExecutor#runningCount()}
     *                 and {@link WorkflowExecutor#parkedCount()} back the gauges
     */
    public MaestroEngineGauges(MeterRegistry registry, WorkflowExecutor executor) {
        this.executor = executor;
        Gauge.builder("maestro.workflows.running", this.executor, WorkflowExecutor::runningCount)
                .strongReference(true)
                .register(registry);
        Gauge.builder("maestro.workflows.parked", this.executor, WorkflowExecutor::parkedCount)
                .strongReference(true)
                .register(registry);
    }
}
