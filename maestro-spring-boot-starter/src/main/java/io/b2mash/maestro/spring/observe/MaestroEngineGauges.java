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
 */
public final class MaestroEngineGauges {

    /**
     * Registers {@code maestro.workflows.running} and {@code
     * maestro.workflows.parked} against {@code executor}.
     *
     * @param registry the Micrometer registry the gauges are registered against
     * @param executor the executor whose {@link WorkflowExecutor#runningCount()}
     *                 and {@link WorkflowExecutor#parkedCount()} back the gauges
     */
    public MaestroEngineGauges(MeterRegistry registry, WorkflowExecutor executor) {
        Gauge.builder("maestro.workflows.running", executor, WorkflowExecutor::runningCount)
                .register(registry);
        Gauge.builder("maestro.workflows.parked", executor, WorkflowExecutor::parkedCount)
                .register(registry);
    }
}
