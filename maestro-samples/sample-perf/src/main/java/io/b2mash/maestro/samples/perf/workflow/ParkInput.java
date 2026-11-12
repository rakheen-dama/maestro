package io.b2mash.maestro.samples.perf.workflow;

/**
 * Input of {@link PerfParkWorkflow}.
 *
 * @param parkMode    {@code "signal"} (park on {@code awaitSignal}) or
 *                    {@code "sleep"} (park on a durable timer)
 * @param parkSeconds the signal timeout, or the sleep duration
 */
public record ParkInput(String parkMode, long parkSeconds) {

    /** {@link #parkMode()} that parks on {@code awaitSignal("perf.release", ...)}. */
    public static final String SIGNAL = "signal";

    /** {@link #parkMode()} that parks on {@code sleep(...)}. */
    public static final String SLEEP = "sleep";
}
