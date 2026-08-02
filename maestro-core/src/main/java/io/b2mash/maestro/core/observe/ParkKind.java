package io.b2mash.maestro.core.observe;

/**
 * What a workflow's virtual thread parked on — see
 * {@link EngineObserver#workflowParked(WorkflowInfo, ParkKind)}.
 */
public enum ParkKind {

    /** Parked in {@code awaitSignal()}. */
    SIGNAL,

    /** Parked in {@code sleep()}. */
    TIMER
}
