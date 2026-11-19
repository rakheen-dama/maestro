package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.observe.EngineObserver;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test helper: a loop body run under a {@link LoopSupervisor}, as the executor
 * runs it, with the stop the bodies' own threads used to offer.
 *
 * <p><b>Thread safety:</b> as {@link LoopSupervisor}.
 */
final class RunningLoop implements StoppableLoop {

    /** How long {@link #stop()} waits for the loop to end. */
    static final Duration STOP_JOIN = Duration.ofSeconds(5);

    private final LoopSupervisor supervisor;

    private RunningLoop(LoopSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    /** Starts {@code body} under a supervisor reporting to nothing. */
    static RunningLoop start(LoopSpec spec, LoopBody body) {
        return start(spec, body, EngineObserver.NOOP);
    }

    /** Starts {@code body} under a supervisor reporting to {@code observer}. */
    static RunningLoop start(LoopSpec spec, LoopBody body, EngineObserver observer) {
        return new RunningLoop(LoopSupervisor.start(spec, body, observer));
    }

    LoopSupervisor supervisor() {
        return supervisor;
    }

    /** Request, then wait at most {@link #STOP_JOIN}, which must see the loop end. */
    void stop() {
        supervisor.requestStop();
        assertTrue(supervisor.awaitStopped(STOP_JOIN), "the loop ended within " + STOP_JOIN);
    }

    boolean isRunning() {
        return supervisor.snapshot().running();
    }

    int consecutiveFailures() {
        return supervisor.snapshot().consecutiveFailures();
    }

    @Override
    public String loopName() {
        return supervisor.name();
    }

    @Override
    public void requestStop() {
        supervisor.requestStop();
    }

    @Override
    public boolean awaitStopped(Duration bound) {
        return supervisor.awaitStopped(bound);
    }
}
