package io.b2mash.maestro.test;

import io.b2mash.maestro.core.observe.StandDownReason;
import org.jspecify.annotations.Nullable;

/**
 * One stand-down of a workflow's run, as the engine reported it: the run
 * stopped without recording an outcome because its durable history governs
 * and this run cannot follow it — typically a replay that diverged from the
 * recorded history ({@link StandDownReason#NONDETERMINISTIC_HISTORY}) or a
 * persisted payload this code cannot read
 * ({@link StandDownReason#UNKNOWN_EVENT_PAYLOAD}). The instance stays active,
 * so in production every recovery pass would re-adopt it and stand down again.
 *
 * <p>Immutable and thread-safe.
 *
 * @param reason why the run stood down
 * @param detail the engine's structured detail (for a drift, the sequence and
 *               the expected versus stored step or argument fingerprint), or
 *               {@code null} if it reported none
 * @see TestWorkflowHandle#standDowns()
 * @see TestWorkflowHandle#awaitStandDown(java.time.Duration)
 */
public record StandDown(StandDownReason reason, @Nullable String detail) {
}
