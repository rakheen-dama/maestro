package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a {@code started_at} tie, the recovery order breaks the tie by id as
 * PostgreSQL orders {@code uuid}: unsigned, byte by byte. Java's
 * {@link UUID#compareTo} compares signed longs, so an id with its top bit set
 * sorts first there and last in Postgres; a cursor compared that way would
 * disagree with the store about which rows come after it.
 */
class RecoveryCursorTest {

    private static final Instant TIE = Instant.parse("2026-01-01T00:00:00Z");
    /** Top bit clear. */
    private static final UUID LOW = new UUID(0x0000_0000_0000_0001L, 0L);
    /** Top bit set: negative as a signed long, so signed order puts it first. */
    private static final UUID HIGH = new UUID(0x8000_0000_0000_0000L, 0L);
    /** Same high bits as LOW; the low bits differ in their top bit. */
    private static final UUID LOW_LSB_HIGH = new UUID(0x0000_0000_0000_0001L, 0x8000_0000_0000_0000L);

    @Test
    void idsCompareUnsignedLikePostgres() {
        assertAll(
                () -> assertTrue(RecoveryCursor.compareIds(LOW, HIGH) < 0, "LOW before HIGH"),
                () -> assertTrue(RecoveryCursor.compareIds(HIGH, LOW) > 0),
                () -> assertTrue(RecoveryCursor.compareIds(LOW, LOW_LSB_HIGH) < 0, "the low bits, unsigned too"),
                () -> assertEquals(0, RecoveryCursor.compareIds(HIGH, new UUID(HIGH.getMostSignificantBits(),
                        HIGH.getLeastSignificantBits()))));
    }

    @Test
    void aCursorOnATiePrecedesTheRowsPostgresPutsAfterIt() {
        var atLow = new RecoveryCursor(TIE, LOW);
        var atHigh = new RecoveryCursor(TIE, HIGH);
        assertAll(
                () -> assertTrue(atLow.precedes(instance(HIGH)), "HIGH comes after a cursor at LOW"),
                () -> assertFalse(atHigh.precedes(instance(LOW)), "LOW does not come after a cursor at HIGH"),
                () -> assertTrue(atLow.precedes(instance(LOW_LSB_HIGH))));
    }

    @Test
    void theOrderSortsATieAsPostgresDoes() {
        var rows = new ArrayList<>(List.of(instance(HIGH), instance(LOW_LSB_HIGH), instance(LOW)));
        rows.sort(RecoveryCursor.ORDER);
        assertEquals(List.of(LOW, LOW_LSB_HIGH, HIGH), rows.stream().map(WorkflowInstance::id).toList());
    }

    private static WorkflowInstance instance(UUID id) {
        return WorkflowInstance.builder()
                .id(id)
                .workflowId("wf-" + id)
                .runId(UUID.randomUUID())
                .workflowType("T")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(TIE)
                .updatedAt(TIE)
                .version(0)
                .build();
    }
}
