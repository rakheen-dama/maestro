package io.b2mash.maestro.core.loop;

/**
 * How a loop's failure reads in health: what the node loses while the loop
 * fails. Internal until 1.0 (see the package Javadoc).
 */
public enum LoopRole {
    /**
     * The node cannot do its job without it: timers stop firing, orphans are
     * not adopted, held locks expire (timer poller, recovery pollers,
     * instance-lock renewer).
     */
    CRITICAL,
    /**
     * Keeps the store tidy; a failure costs space and time, not correctness
     * (signal janitor, retention sweeper, lock cleaner).
     */
    HOUSEKEEPING,
    /**
     * Makes something faster; a slower fallback keeps it correct (the wake
     * listener, whose fallback is the wake re-check).
     */
    OPTIMISATION
}
