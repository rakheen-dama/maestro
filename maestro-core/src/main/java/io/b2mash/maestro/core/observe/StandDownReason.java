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

    /** Issue 18: event append collided with a concurrent runner's history. */
    STALE_RUN
}
