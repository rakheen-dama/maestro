package io.b2mash.maestro.samples.perf.activity;

import io.b2mash.maestro.core.annotation.Activity;

/**
 * The one activity both load-test workflows call. Idempotent: it has no side
 * effect beyond the time it takes.
 */
@Activity
public interface PerfActivities {

    /**
     * Runs one step of a load-test workflow.
     *
     * @param index  the step's position in its workflow, from 0
     * @param millis how long the step takes; {@code 0} returns at once
     * @return {@code index + 1}
     */
    int step(int index, long millis);
}
