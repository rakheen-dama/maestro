package io.b2mash.maestro.core.spi;

/**
 * What one call to
 * {@link WorkflowStore#purgeTerminalInstancesBefore(io.b2mash.maestro.core.model.WorkflowStatus, java.time.Instant, int)}
 * removed, by table.
 *
 * <p>{@code instances} is the number of workflow instances deleted; the other
 * three count the child rows that went with them. A batch that found no
 * candidates is {@link #EMPTY}. Results of consecutive batches are summed
 * with {@link #plus(PurgeResult)}, so a sweep can report its total.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable value.
 *
 * @param instances workflow instance rows deleted
 * @param events    event (memo) rows deleted
 * @param signals   signal rows deleted, consumed or not
 * @param timers    timer rows deleted, whatever their status
 */
public record PurgeResult(int instances, int events, int signals, int timers) {

    /** Nothing was purged. */
    public static final PurgeResult EMPTY = new PurgeResult(0, 0, 0, 0);

    /**
     * Creates a result, rejecting negative counts.
     *
     * @throws IllegalArgumentException if any count is negative
     */
    public PurgeResult {
        if (instances < 0 || events < 0 || signals < 0 || timers < 0) {
            throw new IllegalArgumentException("purge counts must not be negative: "
                    + instances + "/" + events + "/" + signals + "/" + timers);
        }
    }

    /**
     * Returns the component-wise sum of this result and {@code other}.
     *
     * @param other the result to add
     * @return a new result with every count summed
     */
    public PurgeResult plus(PurgeResult other) {
        return new PurgeResult(
                instances + other.instances,
                events + other.events,
                signals + other.signals,
                timers + other.timers);
    }

    /**
     * Returns whether this result deleted nothing at all.
     *
     * @return {@code true} if every count is zero
     */
    public boolean isEmpty() {
        return instances == 0 && events == 0 && signals == 0 && timers == 0;
    }
}
