package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PayloadSerializer}.
 *
 * <p>Uses Jackson 3 ({@code tools.jackson.databind.ObjectMapper}) with a
 * no-arg constructor. No mocking frameworks — pure JUnit 5 assertions.
 */
class PayloadSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadSerializer serializer = new PayloadSerializer(objectMapper);

    /**
     * Simple POJO used across serialization/deserialization tests.
     */
    record TestPayload(String name, int value) {}

    // ── Serialize tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("serialize POJO produces JsonNode with expected fields")
    void serializePojo() {
        var payload = new TestPayload("workflow-1", 42);

        JsonNode node = serializer.serialize(payload);

        assertNotNull(node);
        assertTrue(node.has("name"), "JsonNode should have 'name' field");
        assertTrue(node.has("value"), "JsonNode should have 'value' field");
        assertEquals("workflow-1", node.get("name").stringValue());
        assertEquals(42, node.get("value").asInt());
    }

    @Test
    @DisplayName("serialize null returns NullNode")
    void serializeNullReturnsNullNode() {
        JsonNode node = serializer.serialize(null);

        assertNotNull(node);
        assertInstanceOf(NullNode.class, node);
        assertTrue(node.isNull());
    }

    // ── Deserialize (Class) tests ────────────────────────────────────────

    @Test
    @DisplayName("deserialize round-trips a POJO correctly")
    void deserializeRoundTrip() {
        var original = new TestPayload("order-abc", 99);
        JsonNode node = serializer.serialize(original);

        TestPayload result = serializer.deserialize(node, TestPayload.class);

        assertNotNull(result);
        assertEquals("order-abc", result.name());
        assertEquals(99, result.value());
    }

    @Test
    @DisplayName("deserialize null node returns null")
    void deserializeNullNodeReturnsNull() {
        TestPayload result = serializer.deserialize(null, TestPayload.class);

        assertNull(result);
    }

    @Test
    @DisplayName("deserialize NullNode returns null")
    void deserializeNullNodeInstanceReturnsNull() {
        TestPayload result = serializer.deserialize(NullNode.instance, TestPayload.class);

        assertNull(result);
    }

    // ── Deserialize (Type) tests ─────────────────────────────────────────

    /**
     * Helper interface whose method return type is used to obtain a
     * generic {@link Type} for {@code List<String>}.
     */
    interface StringListSupplier {
        List<String> getStrings();
    }

    @Test
    @DisplayName("deserialize with generic Type round-trips List<String>")
    void deserializeGenericType() throws NoSuchMethodException {
        var strings = List.of("alpha", "bravo", "charlie");
        JsonNode node = serializer.serialize(strings);

        Type listOfStringType = StringListSupplier.class
                .getMethod("getStrings")
                .getGenericReturnType();

        Object result = serializer.deserialize(node, listOfStringType);

        assertNotNull(result);
        assertInstanceOf(List.class, result);
        @SuppressWarnings("unchecked")
        List<String> typedResult = (List<String>) result;
        assertEquals(3, typedResult.size());
        assertEquals("alpha", typedResult.get(0));
        assertEquals("bravo", typedResult.get(1));
        assertEquals("charlie", typedResult.get(2));
    }

    @Test
    @DisplayName("deserialize with generic Type returns null for null node")
    void deserializeGenericTypeNullNode() throws NoSuchMethodException {
        Type listOfStringType = StringListSupplier.class
                .getMethod("getStrings")
                .getGenericReturnType();

        Object result = serializer.deserialize(null, listOfStringType);

        assertNull(result);
    }

    @Test
    @DisplayName("deserialize with generic Type returns null for NullNode")
    void deserializeGenericTypeNullNodeInstance() throws NoSuchMethodException {
        Type listOfStringType = StringListSupplier.class
                .getMethod("getStrings")
                .getGenericReturnType();

        Object result = serializer.deserialize(NullNode.instance, listOfStringType);

        assertNull(result);
    }

    // ── Void handling ────────────────────────────────────────────────────

    @Test
    @DisplayName("serialize null and deserialize with void.class returns null")
    void serializeDeserializeVoid() {
        JsonNode node = serializer.serialize(null);

        Object result = serializer.deserialize(node, void.class);

        assertNull(result);
    }

    // ── Error handling ───────────────────────────────────────────────────

    @Test
    @DisplayName("serialize self-referencing object throws SerializationException")
    void serializeSelfReferencingThrows() {
        // Self-referencing object causes infinite recursion in Jackson
        var selfRef = new SelfReferencing();
        selfRef.self = selfRef;

        assertThrows(SerializationException.class, () -> serializer.serialize(selfRef));
    }

    /** Object with a circular reference that Jackson cannot serialize. */
    static class SelfReferencing {
        public SelfReferencing self;
    }

    // ── Read through JSON text (WP1 Task 5) ──────────────────────────────

    /** The mapper a JDBC store parses payload columns with — and the one the text path renders and parses with. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Declared types the tests read into: each method's generic return type. */
    interface Targets {
        Optional<String> optionalString();

        Map<String, Object> untypedMap();

        List<Object> untypedList();
    }

    private static final Type OPTIONAL_STRING_TYPE = returnTypeOf("optionalString");

    private static Type returnTypeOf(String method) {
        try {
            return Targets.class.getMethod(method).getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void untypedNumbersReadBackAsATextStoreReadsThem() {
        var m = (Map<?, ?>) serializer.deserialize(serializer.serialize(Map.of("f", 0.1f, "n", 5L)), Object.class);
        assertEquals(Double.class, m.get("f").getClass());
        assertEquals(0.1d, m.get("f"));
        assertEquals(Integer.class, m.get("n").getClass());
    }

    @Test
    void aJsonNodeResultIsACopyNotTheStoredNode() {
        var stored = serializer.serialize(Map.of("a", 1));
        var read = serializer.deserialize(stored, JsonNode.class);
        assertEquals(stored, read);
        assertNotSame(stored, read);
    }

    @Test
    void nullReadsAsTheTargetTypesNullValue() {
        assertEquals(Optional.empty(), serializer.deserialize(NullNode.instance, OPTIONAL_STRING_TYPE));
        assertEquals(Optional.empty(), serializer.deserialize(null, OPTIONAL_STRING_TYPE));
        assertNull(serializer.deserialize(NullNode.instance, String.class));
    }

    /**
     * Anti-goal 2 — history replays as before: for every node a JDBC store
     * parses from a payload column, the text path returns what the tree path
     * returned, value and class.
     */
    @ParameterizedTest(name = "[{index}] {0} as {1}")
    @MethodSource("jdbcReadCorpus")
    void textPathEqualsTreePathForEveryNodeTheJdbcStoreReads(String json, Type target) {
        var node = MAPPER.readTree(json);                     // what today's JDBC store hands the engine
        var before = MAPPER.treeToValue(node, MAPPER.getTypeFactory().constructType(target));
        var after = new PayloadSerializer(MAPPER).deserialize(node, target);
        assertEquals(contentOf(before), contentOf(after));
        assertEquals(before == null ? null : before.getClass(), after == null ? null : after.getClass());
    }

    /** A {@code byte[]} compared by content; anything else by its own {@code equals}. */
    private static Object contentOf(Object value) {
        return value instanceof byte[] bytes ? Arrays.toString(bytes) : value;
    }

    /** Every component kind a typed result carries; {@code equals} compares the bytes by content. */
    record Typed(BigDecimal amount, double d, float f, int i, long l, Instant at, OffsetDateTime odt, String s,
                 List<String> list, Set<String> set, Map<String, Integer> map, byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Typed t
                    && Objects.equals(amount, t.amount) && Double.compare(d, t.d) == 0
                    && Float.compare(f, t.f) == 0 && i == t.i && l == t.l
                    && Objects.equals(at, t.at) && Objects.equals(odt, t.odt) && Objects.equals(s, t.s)
                    && Objects.equals(list, t.list) && Objects.equals(set, t.set)
                    && Objects.equals(map, t.map) && Arrays.equals(bytes, t.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(amount, d, f, i, l, at, odt, s, list, set, map, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "Typed[amount=%s, d=%s, f=%s, i=%s, l=%s, at=%s, odt=%s, s=%s, list=%s, set=%s, map=%s, bytes=%s]"
                    .formatted(amount, d, f, i, l, at, odt, s, list, set, map, Arrays.toString(bytes));
        }
    }

    static Stream<Arguments> jdbcReadCorpus() {
        Type map = returnTypeOf("untypedMap");
        Type list = returnTypeOf("untypedList");
        return Stream.of(
                // typed
                Arguments.of("""
                        {"amount":10.50,"d":0.1,"f":0.1,"i":7,"l":9007199254740993,\
                        "at":"2026-11-12T10:00:00.123456789Z","odt":"2026-11-12T10:00:00+02:00","s":"x",\
                        "list":["b","a"],"set":["b","a"],"map":{"k":1},"bytes":"AQID"}""", Typed.class),
                Arguments.of("""
                        {"amount":1.123456789012345678,"d":1.5E-7,"f":3.4028235E38,"i":-2147483648,\
                        "l":-9223372036854775808,"at":1762941600.123456789,"odt":"2026-11-12T10:00:00Z","s":"",\
                        "list":[],"set":[],"map":{},"bytes":""}""", Typed.class),
                Arguments.of("""
                        {"amount":1E+2,"d":5,"f":2,"i":0,"l":0,"at":1762941600,"odt":"2026-11-12T10:00:00.5-03:30",\
                        "s":null,"list":null,"set":null,"map":null,"bytes":null}""", Typed.class),
                Arguments.of("""
                        {"amount":12345678901234567.89,"d":1e308,"f":1.17549435E-38,"i":42,"l":1152921504606846976,\
                        "at":"2026-11-12T10:00:00Z","odt":"2026-11-12T10:00:00+14:00","s":"\\u00e9",\
                        "list":["x"],"set":["x"],"map":{"a":-1,"b":2147483647},"bytes":"AA=="}""", Typed.class),
                Arguments.of("10.50", BigDecimal.class),
                Arguments.of("1.123456789012345678", BigDecimal.class),
                Arguments.of("1E+2", BigDecimal.class),
                Arguments.of("12345678901234567.89", BigDecimal.class),
                Arguments.of("\"AQID\"", byte[].class),
                // untyped: decimals, exponents, ints, longs, big integers, booleans, nested objects
                Arguments.of("0.1", Object.class),
                Arguments.of("10.50", Object.class),
                Arguments.of("1.123456789012345678", Object.class),
                Arguments.of("12345678901234567.89", Object.class),
                Arguments.of("1e3", Object.class),
                Arguments.of("1.5E-7", Object.class),
                Arguments.of("1E+2", Object.class),
                Arguments.of("7", Object.class),
                Arguments.of("2147483648", Object.class),
                Arguments.of("9007199254740993", Object.class),
                Arguments.of("1180591620717411303424", Object.class),
                Arguments.of("true", Object.class),
                Arguments.of("\"s\"", Object.class),
                Arguments.of("null", Object.class),
                Arguments.of("""
                        {"a":{"b":[1,2.5,{"c":null}],"d":1e3,"e":false}}""", Object.class),
                Arguments.of("""
                        [1,2.5,true,null,{"k":"v"}]""", Object.class),
                Arguments.of("""
                        {"d":0.1,"dp":10.50,"dp18":1.123456789012345678,"e":1e3,"e2":2.5E-3,"i":5,\
                        "l":1152921504606846976,"big":1180591620717411303424,"b":true,"n":null,\
                        "bin":"AQID","o":{"x":[1,2.5e-3,{"y":1E+2}]}}""", map),
                Arguments.of("""
                        [0.1,10.50,1.123456789012345678,1e3,7,1152921504606846976,1180591620717411303424,\
                        false,null,{"k":"v","n":-0.0},[1,[2.0]]]""", list));
    }
}
