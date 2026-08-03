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

    /** Issue 18: event append collided with a concurrent runner's history. */
    STALE_RUN
}
