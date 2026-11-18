package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.Activity;

/**
 * The plain steps of the rolling-upgrade workflows. Every live execution
 * records one row in {@code rolling_side_effect}, naming the node that ran it,
 * so the test can count executions across both nodes; a replayed step records
 * nothing.
 */
@Activity(name = "rolling")
public interface RollingActivities {

    /**
     * @param key  the workflow's key (its workflow id)
     * @param step the step's name
     * @return {@code step}
     */
    String step(String key, String step);
}
