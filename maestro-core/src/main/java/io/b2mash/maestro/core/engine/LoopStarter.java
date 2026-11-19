package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.observe.EngineObserver;

/**
 * How the engine starts a supervised loop: {@link LoopSupervisor#start} in
 * production; a test seam replaces it to show what a loop that cannot start
 * does to its owner.
 */
@FunctionalInterface
interface LoopStarter {

    /** Production: {@link LoopSupervisor#start}. */
    LoopStarter DEFAULT = LoopSupervisor::start;

    /**
     * Starts {@code body} under a supervisor.
     *
     * @param spec     what the loop is
     * @param body     its work
     * @param observer the observer its supervisor reports to
     * @return the running supervisor
     */
    LoopSupervisor start(LoopSpec spec, LoopBody body, EngineObserver observer);
}
