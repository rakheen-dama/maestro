package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fingerprint must be a function of the arguments' <em>values</em> — not
 * of map iteration order, record field order or numeric spelling — and must
 * never fail an activity call.
 */
@DisplayName("ArgumentFingerprint — a value-level, order-insensitive SHA-256 of activity arguments")
class ArgumentFingerprintTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final PayloadSerializer serializer = new PayloadSerializer(MAPPER);

    @Test
    void sixtyFourLowercaseHex() {
        var hash = ArgumentFingerprint.of(serializer, new Object[]{"order-1", 42});
        assertNotNull(hash);
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
    }

    @Test
    void sameValuesSameHash_differentValuesDifferentHash() {
        var a = ArgumentFingerprint.of(serializer, new Object[]{"order-1", 42});
        var b = ArgumentFingerprint.of(serializer, new Object[]{"order-1", 42});
        var c = ArgumentFingerprint.of(serializer, new Object[]{"order-1", 43});
        assertAll(
                () -> assertEquals(a, b),
                () -> assertNotEquals(a, c));
    }

    @Test
    @DisplayName("a LinkedHashMap and a HashMap built in reverse order fingerprint identically")
    void mapInsertionOrderDoesNotMatter() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("region", "eu");
        ordered.put("tier", "gold");
        ordered.put("items", 3);
        var reversed = new HashMap<String, Object>();
        reversed.put("items", 3);
        reversed.put("tier", "gold");
        reversed.put("region", "eu");

        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{ordered}),
                ArgumentFingerprint.of(serializer, new Object[]{reversed}));
        // The canonical text itself is sorted by key, whatever the mapper emitted.
        assertEquals("1\u001E{\"items\":3,\"region\":\"eu\",\"tier\":\"gold\"}",
                ArgumentFingerprint.canonical(serializer, new Object[]{reversed}));
    }

    @Test
    void nestedObjectsAreSortedAtEveryLevel() {
        var inner1 = new LinkedHashMap<String, Object>();
        inner1.put("b", 2);
        inner1.put("a", 1);
        var inner2 = new LinkedHashMap<String, Object>();
        inner2.put("a", 1);
        inner2.put("b", 2);
        var outer1 = Map.of("x", inner1);
        var outer2 = Map.of("x", inner2);
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{outer1}),
                ArgumentFingerprint.of(serializer, new Object[]{outer2}));
    }

    @Test
    @DisplayName("list element order IS part of the value")
    void listOrderMatters() {
        assertNotEquals(ArgumentFingerprint.of(serializer, new Object[]{List.of("a", "b")}),
                ArgumentFingerprint.of(serializer, new Object[]{List.of("b", "a")}));
    }

    @Test
    @DisplayName("1, 1.0, 1E0 and BigDecimal 1.00 fingerprint identically")
    void numericSpellingDoesNotMatter() {
        var fromInt = ArgumentFingerprint.of(serializer, new Object[]{1});
        var fromLong = ArgumentFingerprint.of(serializer, new Object[]{1L});
        var fromDouble = ArgumentFingerprint.of(serializer, new Object[]{1.0d});
        var fromSci = ArgumentFingerprint.of(serializer, new Object[]{new BigDecimal("1E0")});
        var fromScale = ArgumentFingerprint.of(serializer, new Object[]{new BigDecimal("1.00")});
        assertAll(
                () -> assertEquals(fromInt, fromLong),
                () -> assertEquals(fromInt, fromDouble),
                () -> assertEquals(fromInt, fromSci),
                () -> assertEquals(fromInt, fromScale),
                () -> assertEquals("1\u001E1", ArgumentFingerprint.canonical(serializer, new Object[]{1.0d})),
                () -> assertEquals("1\u001E0.5",
                        ArgumentFingerprint.canonical(serializer, new Object[]{new BigDecimal("5E-1")})));
    }

    @Test
    void nonFiniteDoublesRenderAsLiterals() {
        assertAll(
                () -> assertEquals("1\u001ENaN", ArgumentFingerprint.canonical(serializer, new Object[]{Double.NaN})),
                () -> assertEquals("1\u001EInfinity",
                        ArgumentFingerprint.canonical(serializer, new Object[]{Double.POSITIVE_INFINITY})),
                () -> assertEquals("1\u001E-Infinity",
                        ArgumentFingerprint.canonical(serializer, new Object[]{Double.NEGATIVE_INFINITY})));
    }

    @Test
    @DisplayName("null args == empty args == the zero-arity call; [null] is a one-arity call")
    void arityIsPartOfTheFingerprint() {
        var noArray = ArgumentFingerprint.of(serializer, null);
        var empty = ArgumentFingerprint.of(serializer, new Object[0]);
        var oneNull = ArgumentFingerprint.of(serializer, new Object[]{null});
        assertAll(
                () -> assertEquals(noArray, empty),
                () -> assertNotEquals(empty, oneNull),
                () -> assertEquals("0", ArgumentFingerprint.canonical(serializer, null)),
                () -> assertEquals("0", ArgumentFingerprint.canonical(serializer, new Object[0])),
                () -> assertEquals("1\u001Enull", ArgumentFingerprint.canonical(serializer, new Object[]{null})));
    }

    @Test
    void stringsAreQuotedAndEscaped_soStructureCannotBeForged() {
        // A string that LOOKS like an object must not collide with an object.
        var asString = ArgumentFingerprint.of(serializer, new Object[]{"{\"a\":1}"});
        var asMap = ArgumentFingerprint.of(serializer, new Object[]{Map.of("a", 1)});
        assertNotEquals(asString, asMap);
        assertEquals("1\u001E\"say \\\"hi\\\"\\n\"",
                ArgumentFingerprint.canonical(serializer, new Object[]{"say \"hi\"\n"}));
    }

    @Test
    void recordsFingerprintByFieldValuesInSortedKeyOrder() {
        record Order(String id, int qty) {}
        record OrderReordered(int qty, String id) {}
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{new Order("o-1", 2)}),
                ArgumentFingerprint.of(serializer, new Object[]{new OrderReordered(2, "o-1")}));
    }

    @Test
    void booleansAndBinary() {
        assertEquals("2\u001Etrue\u001E\"AQID\"",
                ArgumentFingerprint.canonical(serializer, new Object[]{true, new byte[]{1, 2, 3}}));
    }

    @Test
    @DisplayName("an argument the mapper cannot serialize yields null, never an exception")
    void nonSerializableArgumentYieldsNull() {
        var unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;          // a cycle the mapper refuses
            }
        };
        assertNull(ArgumentFingerprint.of(serializer, new Object[]{unserializable}));
        // The failure is per call: a serializable argument after it still hashes.
        assertNotNull(ArgumentFingerprint.of(serializer, new Object[]{"fine"}));
    }
}
