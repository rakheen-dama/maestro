package io.b2mash.maestro.samples.perf.activity;

import io.b2mash.maestro.core.annotation.Activity;

/**
 * The host application's own database work, as a load-test activity: it holds
 * one connection of the application's pool for a given time. Scenario 5
 * ({@code perf/scenarios/s5-pool-health.sh}) exhausts the pool with it — the
 * situation Maestro's health indicator must tell apart from a store outage.
 * Idempotent: it reads nothing and writes nothing.
 */
@Activity
public interface PerfDbActivities {

    /**
     * Holds one pooled connection for {@code millis}.
     *
     * @param index  the activity's position in its workflow, from 0
     * @param millis how long the connection is held; {@code 0} returns at once
     * @return {@code index + 1}
     */
    int hold(int index, long millis);
}
