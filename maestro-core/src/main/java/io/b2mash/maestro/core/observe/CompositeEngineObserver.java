package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Fans one {@link EngineObserver} callback out to an ordered list of
 * delegates.
 *
 * <h2>Containment contract</h2>
 * <p>A delegate throwing a {@link RuntimeException} is contained: the failure
 * is logged at WARN and the remaining delegates still run — one misbehaving
 * observer must never disturb the engine or its sibling observers.
 * {@link Error}s are deliberately <b>not</b> caught: the composite must never
 * swallow the engine's control-flow signals
 * ({@code ExecutorShutdownException}, {@code WorkflowTerminatedException}) —
 * and observers must never throw them.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable and thread-safe; thread safety of the callbacks themselves is
 * each delegate's obligation per the {@link EngineObserver} contract.
 */
public final class CompositeEngineObserver implements EngineObserver {

    private static final Logger logger = LoggerFactory.getLogger(CompositeEngineObserver.class);

    private final List<EngineObserver> delegates;

    private CompositeEngineObserver(List<EngineObserver> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Builds the cheapest observer for a delegate list: {@link EngineObserver#NOOP}
     * for an empty list, the sole delegate itself for a singleton list, and a
     * composite otherwise.
     *
     * @param observers the delegates, in invocation order
     * @return the collapsed observer
     */
    public static EngineObserver of(List<EngineObserver> observers) {
        // RED skeleton — collapsing rules and fan-out not yet implemented
        return new CompositeEngineObserver(observers);
    }
}
