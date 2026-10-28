package io.b2mash.maestro.core.observe;

/**
 * Why a local run stood down without recording a workflow outcome — see
 * {@link EngineObserver#standDown(StandDownReason, String, String)}.
 */
public enum StandDownReason {

    /** Persisted event whose type string this build does not know (§6). */
    UNKNOWN_EVENT_TYPE,

    /** Persisted payload of a known event could not be deserialized on replay (§6). */
    UNKNOWN_EVENT_PAYLOAD,

    /**
     * Replay met a persisted event whose step name, type, or fork shape does
     * not match the operation the current code performs at that sequence — the
     * workflow code changed without a {@code workflow.version()} gate (C10). A
     * deploy rollback is the fix; the instance is left recoverable.
     */
    NONDETERMINISTIC_HISTORY,

    /**
     * Another runner owns this workflow (Issue 18 / C5). Reported when an
     * event append collided with a concurrent runner's history
     * ({@code DuplicateEventException}), and when a fresh read found the row
     * already finalised ({@code COMPLETED}/{@code FAILED}) by another runner
     * ({@code StaleRunException}) — at a park/wake status write, an
     * {@code awaitSignal()} wake, the transition to {@code COMPENSATING}, or
     * before a sequential compensation entry. Also reported when the next
     * live activity of a run whose earlier collision workflow code swallowed
     * is stopped before it executes ({@code StaleRunException} from the run's
     * cancellation latch).
     */
    STALE_RUN
}
