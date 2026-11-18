package io.b2mash.maestro.core.loop;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One consistent reading of a supervised loop, for health and meters.
 * Internal until 1.0 (see the package Javadoc).
 *
 * @param name                the loop's fixed name
 * @param role                how health reads its failure
 * @param state               where the loop is in its life
 * @param consecutiveFailures failed iterations since the last success, not
 *                            counting failures while stopping
 * @param failingSince        when the current streak's first failure was;
 *                            {@code null} without a streak
 * @param lastSuccess         when the last successful iteration ended;
 *                            {@code null} before the first
 * @param lastFailureType     the class name of the streak's latest failure;
 *                            {@code null} without a streak
 * @param inIterationFor      how long the iteration in progress has run;
 *                            {@code null} between iterations
 * @param failing             whether the streak counts as failing under the
 *                            spec's {@link FailingRule}
 * @param notes               the body's informational notes
 *                            ({@link LoopContext#note})
 */
public record LoopSnapshot(
        String name,
        LoopRole role,
        State state,
        int consecutiveFailures,
        @Nullable Instant failingSince,
        @Nullable Instant lastSuccess,
        @Nullable String lastFailureType,
        @Nullable Duration inIterationFor,
        boolean failing,
        Map<String, Object> notes
) {

    /** Copies {@code notes}. */
    public LoopSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        notes = Map.copyOf(notes);
    }

    /**
     * @return {@code true} while the loop is {@link State#STARTING} or
     *         {@link State#RUNNING}: not asked to stop, and not ended
     */
    public boolean running() {
        return state == State.STARTING || state == State.RUNNING;
    }

    /** Where a supervised loop is in its life. */
    public enum State {
        /** Built, its thread not yet running. */
        STARTING,
        /** Running iterations, or waiting between them. */
        RUNNING,
        /** Asked to stop; the iteration in progress, if any, has not returned yet. */
        STOPPING,
        /** Ended after a stop was requested. */
        STOPPED,
        /** Ended while not asked to stop: a control-flow {@code Error} escaped it. */
        DEAD,
        /** Ended because its body reported its work complete ({@link Next#done()}). */
        DONE;

        /**
         * @return {@code true} for {@link #STOPPED}, {@link #DEAD} and {@link #DONE}
         */
        public boolean ended() {
            return this == STOPPED || this == DEAD || this == DONE;
        }
    }
}
