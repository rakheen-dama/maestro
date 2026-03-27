package io.maestro.core.saga;

/**
 * A single compensation registered during workflow execution.
 *
 * <p>Each entry pairs a human-readable step name (for logging and events)
 * with the executable compensation action. For {@code @Compensate}-annotated
 * activities, the action calls through the activity proxy, making it
 * memoized, retriable, and replayable.
 *
 * @param stepName the compensation step name (e.g., {@code "InventoryActivities.releaseReservation"})
 * @param action   the executable compensation action
 */
public record CompensationEntry(
        String stepName,
        Runnable action
) {

    /**
     * Creates a compensation entry.
     *
     * @param stepName the compensation step name
     * @param action   the executable compensation action
     * @throws NullPointerException if either argument is null
     */
    public CompensationEntry {
        java.util.Objects.requireNonNull(stepName, "stepName must not be null");
        java.util.Objects.requireNonNull(action, "action must not be null");
    }
}
