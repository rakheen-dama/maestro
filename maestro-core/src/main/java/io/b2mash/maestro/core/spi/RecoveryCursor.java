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
     * {@code (startedAt, id)} ascending, with {@link #compareIds} breaking
     * ties — the order of a recovery page for a store that sorts in Java, and
     * the order PostgreSQL gives {@code ORDER BY started_at, id}.
     */
    public static final Comparator<WorkflowInstance> ORDER =
            Comparator.comparing(WorkflowInstance::startedAt)
                    .thenComparing(WorkflowInstance::id, RecoveryCursor::compareIds);

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
     * Compares two instance ids as PostgreSQL orders {@code uuid}: unsigned,
     * byte by byte — the high 64 bits, then the low 64 bits, each unsigned.
     * {@link UUID#compareTo} compares them signed, so an id whose top bit is
     * set would sort first there and last in Postgres.
     *
     * @param a one id
     * @param b another id
     * @return negative, zero or positive as {@code a} sorts before, with or after {@code b}
     */
    public static int compareIds(UUID a, UUID b) {
        var high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    /**
     * Whether {@code instance} comes strictly after this cursor in {@link #ORDER}.
     *
     * @param instance the instance to place
     * @return {@code true} if it belongs to a later page
     */
    public boolean precedes(WorkflowInstance instance) {
        var byStart = startedAt.compareTo(instance.startedAt());
        return byStart < 0 || (byStart == 0 && compareIds(id, instance.id()) < 0);
    }
}
