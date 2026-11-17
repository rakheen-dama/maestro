package io.b2mash.maestro.samples.perf.workflow;

/**
 * Input of {@link PerfChainWorkflow}.
 *
 * @param activities     how many {@code step} activities to run in sequence
 * @param activityMillis how long each step takes
 */
public record ChainInput(int activities, long activityMillis) {}
