package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.WorkflowInstance;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Keyset position in a paged recovery scan
 * ({@link WorkflowStore#getRecoverableInstances(String, RecoveryCursor, int)}):
 * the {@code (startedAt, id)} of the last instance of the previous page. The
 * next page holds the instances strictly after it in {@code (startedAt, id)}
 * order.
 *
 * <p>Build it from a row the store returned ({@link #of(WorkflowInstance)}),
 * never from a clock: a store may hold {@code startedAt} at a coarser
 * precision than {@link Instant} (Postgres keeps microseconds), and only the
 * value the store itself returned compares exactly against its rows. Ties on
 * {@code startedAt} are broken by {@code id} in the store's own UUID order
 * (Postgres compares UUIDs bytewise; the in-memory stores use
 * {@link #ORDER}), so a caller must not assume any order beyond
 * {@code startedAt} — only pass the cursor back.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe.
 *
 * @param startedAt the last returned instance's {@link WorkflowInstance#startedAt()}
 * @param id        the last returned instance's {@link WorkflowInstance#id()}
 */
public record RecoveryCursor(Instant startedAt, UUID id) {

    /**
     * {@code (startedAt, id)} ascending, with {@link UUID#compareTo} breaking
     * ties — the order of a recovery page for a store that sorts in Java.
     */
    public static final Comparator<WorkflowInstance> ORDER =
            Comparator.comparing(WorkflowInstance::startedAt).thenComparing(WorkflowInstance::id);

    /**
     * Creates a cursor.
     *
     * @throws NullPointerException if either component is {@code null}
     */
    public RecoveryCursor {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(id, "id");
    }

    /**
     * @param last the last instance of the page just read
     * @return the cursor positioned just after {@code last}
     */
    public static RecoveryCursor of(WorkflowInstance last) {
        return new RecoveryCursor(last.startedAt(), last.id());
    }

    /**
     * Whether {@code instance} comes strictly after this cursor in {@link #ORDER}.
     *
     * @param instance the instance to place
     * @return {@code true} if it belongs to a later page
     */
    public boolean precedes(WorkflowInstance instance) {
        var byStart = startedAt.compareTo(instance.startedAt());
        return byStart < 0 || (byStart == 0 && id.compareTo(instance.id()) < 0);
    }
}
