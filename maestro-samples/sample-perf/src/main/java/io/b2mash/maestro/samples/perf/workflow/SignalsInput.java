package io.b2mash.maestro.samples.perf.workflow;

/**
 * Input of {@link PerfSignalsWorkflow}.
 *
 * @param signals     how many distinct signals to await, {@code perf.s0} to
 *                    {@code perf.s<signals - 1>}
 * @param parkSeconds the timeout of each await
 */
public record SignalsInput(int signals, long parkSeconds) {}
