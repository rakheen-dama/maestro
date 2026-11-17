package io.b2mash.maestro.samples.perf.workflow;

/**
 * Input of {@link PerfHoldWorkflow}.
 *
 * @param activities how many {@code hold} activities to run in sequence
 * @param holdMillis how long each holds its pooled connection
 */
public record HoldInput(int activities, long holdMillis) {}
