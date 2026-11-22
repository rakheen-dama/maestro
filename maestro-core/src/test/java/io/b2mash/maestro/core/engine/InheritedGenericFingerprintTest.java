package io.b2mash.maestro.core.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue 94, PR #92 review finding 1: when does an argument built from a
 * result read as a map (rc.1–rc.4, an inherited generic result) fingerprint
 * differently from the same argument built from the typed result (this
 * build)? The fingerprint sorts object keys and compares numbers by value
 * ({@code stripTrailingZeros}), so a map of a value's fields hashes like the
 * value exactly when the typed read-back serializes back to the stored JSON
 * up to key order and numeric form. It differs when the typed read changes
 * the JSON: a decimal finer than a {@code double} (the map holds a
 * {@code Double}), a property the class drops on read, a property the
 * stored JSON lacks (the typed value writes it as {@code null}), a value the
 * typed read normalizes (an {@code OffsetDateTime} adjusted to UTC).
 */
class InheritedGenericFingerprintTest {

    private static final PayloadSerializer SERIALIZER = new PayloadSerializer(JsonMapper.builder().build());

    public record Pair(String left, String right) { }

    public record Money(BigDecimal amount, Long count, @Nullable String note) { }

    public record Stamp(OffsetDateTime at) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slim(String id) { }

    public record Wide(String id, @Nullable String note) { }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("record of strings", json(new Pair("l", "r")), Pair.class, true),
                Arguments.of("trailing zeros, Long read as Integer, a null field",
                        json(new Money(new BigDecimal("10.50"), 5L, null)), Money.class, true),
                Arguments.of("a decimal finer than a double",
                        json(new Money(new BigDecimal("0.1000000000000000055511151231257827"), 5L, null)),
                        Money.class, false),
                Arguments.of("an offset the typed read adjusts to UTC",
                        json(new Stamp(OffsetDateTime.of(2026, 11, 22, 10, 0, 0, 0, ZoneOffset.ofHours(2)))),
                        Stamp.class, false),
                Arguments.of("a property the class drops on read", json(new Wide("a", "x")), Slim.class, false),
                Arguments.of("a property the stored JSON lacks", json(new Slim("a")), Wide.class, false));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void mapFormAndTypedFormFingerprint(String label, JsonNode stored, Class<?> type, boolean same) {
        // As passed on to a later activity inside a List (the collection case of Issue 94).
        var asMap = List.of(SERIALIZER.deserialize(stored, Object.class));
        var typed = List.of(SERIALIZER.deserialize(stored, type));
        var mapHash = ArgumentFingerprint.of(SERIALIZER, new Object[]{asMap});
        var typedHash = ArgumentFingerprint.of(SERIALIZER, new Object[]{typed});
        assertEquals(same, mapHash != null && mapHash.equals(typedHash), label);
    }

    private static JsonNode json(Object value) {
        return SERIALIZER.serialize(value);
    }
}
