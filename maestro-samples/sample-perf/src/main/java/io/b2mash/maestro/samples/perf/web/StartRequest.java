package io.b2mash.maestro.samples.perf.web;

import io.b2mash.maestro.samples.perf.workflow.ChainInput;
import io.b2mash.maestro.samples.perf.workflow.HoldInput;
import io.b2mash.maestro.samples.perf.workflow.ParkInput;
import io.b2mash.maestro.samples.perf.workflow.SignalsInput;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Body of {@code POST /perf/workflows}. Every knob is optional; an omitted knob
 * takes the default below. Check it with {@link StartRequestValidator} before
 * building an input from it.
 *
 * @param workflowId     the business workflow ID, {@code [A-Za-z0-9._-]{1,200}}
 * @param type           {@code "chain"}, {@code "park"}, {@code "hold"} or {@code "signals"}
 * @param activities     chain: steps to run, 1..50 (default {@value #DEFAULT_ACTIVITIES});
 *                       hold: holds to run, 1..50 (default {@value #DEFAULT_HOLD_ACTIVITIES})
 * @param activityMillis chain: duration of each step, 0..10000 (default {@value #DEFAULT_ACTIVITY_MILLIS})
 * @param parkMode       park: {@code "signal"} or {@code "sleep"} (default {@code "signal"})
 * @param parkSeconds    park: timeout or sleep; signals: each await's timeout; 1..86400
 *                       (default {@value #DEFAULT_PARK_SECONDS})
 * @param holdMillis     hold: how long each hold keeps its pooled connection, 0..20000
 *                       (default {@value #DEFAULT_HOLD_MILLIS})
 * @param signals        signals: distinct signals to await, 1..50 (default {@value #DEFAULT_SIGNALS})
 */
public record StartRequest(
        @Nullable String workflowId,
        @Nullable String type,
        @Nullable Integer activities,
        @Nullable Long activityMillis,
        @Nullable String parkMode,
        @Nullable Long parkSeconds,
        @Nullable Long holdMillis,
        @Nullable Integer signals
) {

    /** {@link #type()} of a {@code perf-chain} run. */
    public static final String CHAIN = "chain";

    /** {@link #type()} of a {@code perf-park} run. */
    public static final String PARK = "park";

    /** {@link #type()} of a {@code perf-hold} run. */
    public static final String HOLD = "hold";

    /** {@link #type()} of a {@code perf-signals} run. */
    public static final String SIGNALS = "signals";

    /** Default {@link #activities()}. */
    public static final int DEFAULT_ACTIVITIES = 5;

    /** Default {@link #activityMillis()}. */
    public static final long DEFAULT_ACTIVITY_MILLIS = 50;

    /** Default {@link #parkMode()}. */
    public static final String DEFAULT_PARK_MODE = ParkInput.SIGNAL;

    /** Default {@link #parkSeconds()}. */
    public static final long DEFAULT_PARK_SECONDS = 7200;

    /** Default {@link #activities()} of a {@code hold} run. */
    public static final int DEFAULT_HOLD_ACTIVITIES = 1;

    /** Default {@link #holdMillis()}. */
    public static final long DEFAULT_HOLD_MILLIS = 1000;

    /** Default {@link #signals()}. */
    public static final int DEFAULT_SIGNALS = 5;

    /** @return the {@code perf-chain} input, defaults applied */
    public ChainInput chainInput() {
        return new ChainInput(
                Objects.requireNonNullElse(activities, DEFAULT_ACTIVITIES),
                Objects.requireNonNullElse(activityMillis, DEFAULT_ACTIVITY_MILLIS));
    }

    /** @return the {@code perf-park} input, defaults applied */
    public ParkInput parkInput() {
        return new ParkInput(
                Objects.requireNonNullElse(parkMode, DEFAULT_PARK_MODE),
                Objects.requireNonNullElse(parkSeconds, DEFAULT_PARK_SECONDS));
    }

    /** @return the {@code perf-hold} input, defaults applied */
    public HoldInput holdInput() {
        return new HoldInput(
                Objects.requireNonNullElse(activities, DEFAULT_HOLD_ACTIVITIES),
                Objects.requireNonNullElse(holdMillis, DEFAULT_HOLD_MILLIS));
    }

    /** @return the {@code perf-signals} input, defaults applied */
    public SignalsInput signalsInput() {
        return new SignalsInput(
                Objects.requireNonNullElse(signals, DEFAULT_SIGNALS),
                Objects.requireNonNullElse(parkSeconds, DEFAULT_PARK_SECONDS));
    }
}
