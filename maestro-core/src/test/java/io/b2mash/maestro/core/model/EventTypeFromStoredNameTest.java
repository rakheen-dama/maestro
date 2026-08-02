package io.b2mash.maestro.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link EventType#fromStoredName(String)} as a <b>total</b> function —
 * the property the whole unknown-event stand-down mechanism rests on.
 *
 * <p>{@link EventType#valueOf(String)} throws for a type string written by a
 * newer node. Thrown from inside a store row mapper, that exception is
 * indistinguishable from a workflow failure by the time it reaches the
 * executor, so the workflow is recorded {@code FAILED} and its sagas
 * compensate — for work that never failed. A row mapper must therefore never
 * be able to throw on the type column, which is what "total" means here.
 */
@DisplayName("EventType.fromStoredName is total — a row mapper can never throw on the type column")
class EventTypeFromStoredNameTest {

    @Test
    @DisplayName("every constant this build defines round-trips through its stored name")
    void everyDefinedConstantRoundTrips() {
        for (var type : EventType.values()) {
            assertEquals(type, EventType.fromStoredName(type.name()),
                    "constant " + type + " must round-trip through its stored name");
        }
    }

    @Test
    @DisplayName("a type string this build does not define yields the UNKNOWN sentinel, not an exception")
    void undefinedTypeYieldsSentinel() {
        assertAll(
                () -> assertEquals(EventType.UNKNOWN,
                        EventType.fromStoredName("EVT_FROM_A_NEWER_MAESTRO"),
                        "the integration fixture's future type must map to the sentinel"),
                () -> assertEquals(EventType.UNKNOWN, EventType.fromStoredName("")),
                () -> assertEquals(EventType.UNKNOWN, EventType.fromStoredName("version_marker"),
                        "the parse is case-sensitive like valueOf — a differently-cased "
                                + "string is a type this build does not define"),
                () -> assertEquals(EventType.UNKNOWN, EventType.fromStoredName(null),
                        "a null type column must not NPE inside a row mapper"));
    }

    @Test
    @DisplayName("valueOf — the call this replaces — throws on exactly the input fromStoredName absorbs")
    void valueOfThrowsWhereFromStoredNameAbsorbs() {
        assertThrows(IllegalArgumentException.class,
                () -> EventType.valueOf("EVT_FROM_A_NEWER_MAESTRO"),
                "if this ever stops throwing, the sentinel has stopped being necessary");
    }

    @Test
    @DisplayName("UNKNOWN is the last constant — a sentinel, never a persisted type")
    void sentinelIsDeclaredLast() {
        var values = EventType.values();
        assertEquals(EventType.UNKNOWN, values[values.length - 1],
                "UNKNOWN is a read-side sentinel and belongs after every real type; "
                        + "declared: " + Arrays.toString(values));
    }
}
