package io.b2mash.maestro.rollingnode.workflow;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;

/**
 * The saga's steps. {@code reserve} and {@code charge} return the key, which
 * their compensations receive. Every live execution records a {@code done} row
 * in {@code rolling_side_effect}; {@code refund} also records a {@code started}
 * row first, and for a key starting with {@value #GATED_PREFIX} waits between
 * the two until the test opens the key's row in {@code rolling_gate}, so a
 * compensation can be caught in flight.
 */
@Activity(name = "rolling-saga")
public interface RollingSagaActivities {

    /** A key with this prefix holds {@code refund} until its gate opens. */
    String GATED_PREFIX = "gated-";

    /**
     * @param key the workflow's key
     * @return {@code key}
     */
    @Compensate("release")
    String reserve(String key);

    /** @param key the key whose reservation is released */
    void release(String key);

    /**
     * @param key the workflow's key
     * @return {@code key}
     */
    @Compensate("refund")
    String charge(String key);

    /** @param key the key whose charge is refunded */
    void refund(String key);

    /**
     * @param key the workflow's key
     * @return never returns
     * @throws RollingBusinessFailure always
     */
    String ship(String key);
}
