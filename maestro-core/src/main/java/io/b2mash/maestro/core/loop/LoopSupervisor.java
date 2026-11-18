package io.b2mash.maestro.core.loop;

import io.b2mash.maestro.core.observe.EngineObserver;

import java.time.Duration;
import java.time.InstantSource;
import java.util.function.LongSupplier;

/** Skeleton: the behaviour lands with the fix. */
public final class LoopSupervisor {

    static final Duration REMINDER_INTERVAL = Duration.ofMinutes(5);

    enum Logged { FIRST, REPEAT, REMINDER, STOPPING }

    private final LoopSpec spec;

    private LoopSupervisor(LoopSpec spec) {
        this.spec = spec;
    }

    public static LoopSupervisor start(LoopSpec spec, LoopBody body, EngineObserver observer) {
        throw new UnsupportedOperationException("skeleton");
    }

    static LoopSupervisor unstarted(LoopSpec spec, LoopBody body, EngineObserver observer, LongSupplier nanoTime,
                                    InstantSource wallClock) {
        return new LoopSupervisor(spec);
    }

    void start() {
        throw new UnsupportedOperationException("skeleton");
    }

    public String name() {
        return spec.name();
    }

    public void requestStop() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean awaitStopped(Duration bound) {
        throw new UnsupportedOperationException("skeleton");
    }

    public LoopSnapshot snapshot() {
        throw new UnsupportedOperationException("skeleton");
    }

    Logged failed(Throwable failure) {
        throw new UnsupportedOperationException("skeleton");
    }

    boolean succeeded() {
        throw new UnsupportedOperationException("skeleton");
    }

    Thread.UncaughtExceptionHandler uncaughtExceptionHandler() {
        throw new UnsupportedOperationException("skeleton");
    }
}
