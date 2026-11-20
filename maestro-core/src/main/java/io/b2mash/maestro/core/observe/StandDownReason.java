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
     * Workflow code threw while its run was still replaying recorded history
     * (Issue 89): the lane the exception escaped (the workflow method, or a
     * {@code parallel()} branch) had not consumed its recorded history — an
     * event this run did not write already holds the lane's next sequence. A
     * code change made the workflow throw inside its recorded prefix (a raised
     * {@code version()} floor, a new check, a removed {@code catch}). Never
     * recorded as a failure and never compensated: a bad deploy must not roll
     * back work that did not fail. The detail names the slot the replay stood
     * at, the recorded step there and the exception
     * ({@code seq=<n> stored=<step> type=<event type> threw=<class>: <message>}).
     * Rolling back, or fixing the workflow code, is the fix; the instance is left
     * recoverable and resumes on code that matches its history.
     */
    REPLAY_EXCEPTION,

    /**
     * Another runner owns this workflow (Issue 18 / C5). Reported when an
     * event append collided with a concurrent runner's history
     * ({@code DuplicateEventException}), and when a fresh read found the row
     * already finalised ({@code COMPLETED}/{@code FAILED}) by another runner
     * ({@code StaleRunException}) — at a park/wake status write, an
     * {@code awaitSignal()} wake, the transition to {@code COMPENSATING}, or
     * before a sequential compensation entry, or a parked {@code sleep()}'s
     * wake. Also reported when a run whose earlier stand-down workflow code
     * swallowed reaches its next stop point — a live activity,
     * {@code sleep()}, {@code awaitSignal()} or the terminal write
     * ({@code StaleRunException} from the run's cancellation latch).
     */
    STALE_RUN,

    /**
     * The store fenced one of this run's writes (Issue 11): the instance row's
     * {@code lease_epoch} had moved past the epoch this run adopted at, so
     * another node has adopted the workflow — this node lost or outlived its
     * instance lock (frozen past the TTL, or partitioned) and
     * the adopting node's progress is the durable truth. Reported from a
     * {@code FencedWriteException} at any run-scoped write — an event append,
     * a park/wake status write, a timer arm, the transition to
     * {@code COMPENSATING} or the terminal write — and, like {@link #STALE_RUN},
     * at the next stop point of a run whose workflow code swallowed the first
     * rejection. The fenced run wrote nothing; the adopting node's run
     * carries the workflow on.
     */
    LEASE_FENCED
}
