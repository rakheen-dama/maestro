package io.b2mash.maestro.core.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.b2mash.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.Base64Variants;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

        List<String> stringList();

        Map<String, Object> untypedMap();

        List<Object> untypedList();

        <T> T typeVariable();

        Map<?, ? extends Optional<String>> wildcards();
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
    void nullReadsAsEmptyForAnOptionalAndAsJavaNullOtherwise() {
        assertEquals(Optional.empty(), serializer.deserialize(NullNode.instance, OPTIONAL_STRING_TYPE));
        assertEquals(Optional.empty(), serializer.deserialize(null, OPTIONAL_STRING_TYPE));
        assertNull(serializer.deserialize(NullNode.instance, String.class));
    }

    /**
     * Every target outside the {@code Optional} family reads a JSON null as Java
     * {@code null}, exactly as before WP1 Task 5 — through both overloads, from a
     * {@code null} node and from a {@link NullNode}. No failure for a primitive,
     * no {@code NullNode} for {@code JsonNode}, no {@code AtomicReference(null)}.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("targetsOutsideTheOptionalFamily")
    void nullReadsAsJavaNullOutsideTheOptionalFamily(String label, Type target) {
        assertEquals(List.of("Java null", "Java null", "Java null", "Java null"), readsOfNull(target), label);
    }

    /** The {@code Optional} family reads a JSON null as its empty value, through both overloads. */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("theOptionalFamily")
    void nullReadsAsEmptyForTheOptionalFamily(String label, Type target, Object empty) {
        var expected = describe(empty);
        assertEquals(List.of(expected, expected, expected, expected), readsOfNull(target), label);
    }

    static Stream<Arguments> targetsOutsideTheOptionalFamily() {
        return Stream.of(
                Arguments.of("int", int.class),
                Arguments.of("long", long.class),
                Arguments.of("boolean", boolean.class),
                Arguments.of("double", double.class),
                Arguments.of("Integer", Integer.class),
                Arguments.of("JsonNode", JsonNode.class),
                Arguments.of("AtomicReference", AtomicReference.class),
                Arguments.of("record", TestPayload.class),
                Arguments.of("List<String>", returnTypeOf("stringList")),
                Arguments.of("String", String.class),
                Arguments.of("Object", Object.class));
    }

    static Stream<Arguments> theOptionalFamily() {
        return Stream.of(
                Arguments.of("Optional<String>", OPTIONAL_STRING_TYPE, Optional.empty()),
                Arguments.of("OptionalInt", OptionalInt.class, OptionalInt.empty()),
                Arguments.of("OptionalLong", OptionalLong.class, OptionalLong.empty()),
                Arguments.of("OptionalDouble", OptionalDouble.class, OptionalDouble.empty()));
    }

    /**
     * A JSON null read as {@code target}: {@code (Class, null)}, {@code (Class, NullNode)},
     * {@code (Type, null)}, {@code (Type, NullNode)} — each the value read, or the failure's cause.
     */
    private List<String> readsOfNull(Type target) {
        var raw = (Class<?>) (target instanceof ParameterizedType p ? p.getRawType() : target);
        var reads = new ArrayList<String>();
        for (JsonNode node : Arrays.asList(null, NullNode.instance)) {
            reads.add(outcome(() -> serializer.deserialize(node, raw)));
        }
        for (JsonNode node : Arrays.asList(null, NullNode.instance)) {
            reads.add(outcome(() -> serializer.deserialize(node, target)));
        }
        return reads;
    }

    private static String outcome(Supplier<@Nullable Object> read) {
        try {
            return describe(read.get());
        } catch (SerializationException e) {
            return "throws " + e.getCause().getClass().getName();
        }
    }

    private static String describe(@Nullable Object value) {
        return value == null ? "Java null" : value.getClass().getSimpleName() + " " + value;
    }

    /**
     * Host mappers whose decimal settings decide what a JDBC store's
     * {@code readTree} builds (a {@code DoubleNode} or a {@code DecimalNode})
     * and how an untyped target reads it.
     */
    private static final Map<String, ObjectMapper> HOST_MAPPERS = Map.ofEntries(
            Map.entry("default", MAPPER),
            Map.entry("JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS",
                    JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()),
            Map.entry("DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS",
                    JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()),
            // Write features that change how an existing tree renders (the internal writer turns them off) ...
            Map.entry("write: WRITE_BIGDECIMAL_AS_PLAIN (decimal nodes)", JsonMapper.builder()
                    .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()),
            Map.entry("write: WRITE_NUMBERS_AS_STRINGS",
                    JsonMapper.builder().enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS).build()),
            Map.entry("write: WRITE_PROPERTIES_SORTED",
                    JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build()),
            Map.entry("write: WRITE_NULL_PROPERTIES off",
                    JsonMapper.builder().disable(JsonNodeFeature.WRITE_NULL_PROPERTIES).build()),
            Map.entry("write: QUOTE_PROPERTY_NAMES off",
                    JsonMapper.builder().disable(JsonWriteFeature.QUOTE_PROPERTY_NAMES).build()),
            Map.entry("write: WRAP_ROOT_VALUE",
                    JsonMapper.builder().enable(SerializationFeature.WRAP_ROOT_VALUE).build()),
            Map.entry("write: WRITE_EMPTY_JSON_ARRAYS off",
                    JsonMapper.builder().disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS).build()),
            Map.entry("write: all of the above", JsonMapper.builder()
                    .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
                    .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
                    .disable(JsonNodeFeature.WRITE_NULL_PROPERTIES)
                    .disable(JsonWriteFeature.QUOTE_PROPERTY_NAMES)
                    .enable(SerializationFeature.WRAP_ROOT_VALUE)
                    .disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS).build()),
            // ... and write features confirmed value-neutral for a rendered tree (left as the host has them).
            Map.entry("neutral: ESCAPE_NON_ASCII",
                    JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()),
            Map.entry("neutral: ESCAPE_FORWARD_SLASHES",
                    JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES).build()),
            Map.entry("neutral: COMBINE_UNICODE_SURROGATES_IN_UTF8",
                    JsonMapper.builder().enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build()),
            Map.entry("neutral: WRITE_HEX_UPPER_CASE off",
                    JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build()),
            Map.entry("neutral: WRITE_NAN_AS_STRINGS off",
                    JsonMapper.builder().disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS).build()),
            Map.entry("neutral: USE_FAST_DOUBLE_WRITER",
                    JsonMapper.builder().enable(StreamWriteFeature.USE_FAST_DOUBLE_WRITER).build()),
            Map.entry("neutral: INDENT_OUTPUT",
                    JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()),
            Map.entry("neutral: ORDER_MAP_ENTRIES_BY_KEYS",
                    JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()),
            Map.entry("neutral: WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED",
                    JsonMapper.builder().enable(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED).build()),
            Map.entry("neutral: inclusion NON_EMPTY", JsonMapper.builder()
                    .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_EMPTY)
                            .withContentInclusion(JsonInclude.Include.NON_EMPTY)).build()),
            Map.entry("neutral: Base64 MIME",
                    JsonMapper.builder().defaultBase64Variant(Base64Variants.MIME).build()));

    /**
     * Anti-goal 2 — history replays as before: for every node a JDBC store
     * parses from a payload column, under each host mapper, the text path
     * returns what the tree path returned, value and class.
     */
    @ParameterizedTest(name = "[{index}] {0}: {1} as {2}")
    @MethodSource("jdbcReadCorpusUnderEveryHostMapper")
    void textPathEqualsTreePathForEveryNodeTheJdbcStoreReads(String mapperName, String json, Type target) {
        var mapper = HOST_MAPPERS.get(mapperName);
        var node = mapper.readTree(json);                     // what today's JDBC store hands the engine
        var before = mapper.treeToValue(node, mapper.getTypeFactory().constructType(target));
        var after = new PayloadSerializer(mapper).deserialize(node, target);
        assertEquals(contentOf(before), contentOf(after));
        assertEquals(before == null ? null : before.getClass(), after == null ? null : after.getClass());
    }

    static Stream<Arguments> jdbcReadCorpusUnderEveryHostMapper() {
        return HOST_MAPPERS.keySet().stream().sorted().flatMap(mapperName -> jdbcReadCorpus()
                .map(row -> Arguments.of(mapperName, row.get()[0], row.get()[1])));
    }

    /**
     * A host mapper with {@code JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS} on
     * (and the {@code DeserializationFeature} off) parses a stored decimal into
     * a {@code DecimalNode}; the tree path read it as a {@code BigDecimal} for
     * an untyped target, scale included, and so must the text path.
     */
    @Test
    void aJsonNodeBigDecimalHostMapperReadsAnUntypedDecimalAsTheBigDecimalItStored() {
        var host = HOST_MAPPERS.get("JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS");
        var hostSerializer = new PayloadSerializer(host);
        var node = host.readTree("{\"amount\":10.50}");
        assertEquals("DecimalNode", node.get("amount").getClass().getSimpleName());
        var asMap = ((Map<?, ?>) hostSerializer.deserialize(node, returnTypeOf("untypedMap"))).get("amount");
        var asObject = ((Map<?, ?>) hostSerializer.deserialize(node, Object.class)).get("amount");
        assertEquals(List.of("BigDecimal 10.50", "BigDecimal 10.50"), List.of(describe(asMap), describe(asObject)));
    }

    /**
     * C1 (WP1 Task 6 relies on it): the rule keys on the host mapper's
     * {@code JsonNodeFeature}, not on the node — under the default mapper an
     * untyped target reads a {@code DecimalNode} as a {@code Double}.
     */
    @Test
    void theDefaultMapperReadsADecimalNodeAsADoubleForAnUntypedTarget() {
        var node = MAPPER.createObjectNode().put("amount", new BigDecimal("10.50"));
        assertEquals("DecimalNode", node.get("amount").getClass().getSimpleName());
        var asMap = ((Map<?, ?>) serializer.deserialize(node, returnTypeOf("untypedMap"))).get("amount");
        var asObject = ((Map<?, ?>) serializer.deserialize(node, Object.class)).get("amount");
        assertEquals(List.of("Double 10.5", "Double 10.5"), List.of(describe(asMap), describe(asObject)));
    }

    // ── Host WRITE features never change what a stored tree reads as ───

    /** {@code text} read as {@code target} under {@code host}: what the serializer returns, described. */
    private static String readUnder(ObjectMapper host, String text, Type target) {
        return deepDescribe(new PayloadSerializer(host).deserialize(host.readTree(text), target));
    }

    /** {@link #describe} that also names the class of each map value and list element, in order. */
    private static String deepDescribe(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(", ", map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + deepDescribe(e.getValue())).toList()) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(PayloadSerializerTest::deepDescribe).toList()) + "]";
        }
        return value instanceof BigDecimal d ? "BigDecimal " + d + " (scale " + d.scale() + ")" : describe(value);
    }

    @Test
    void writePropertiesSorted_keepsTheStoredKeyOrder() {
        var host = JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
        assertEquals("{b=Integer 1, a=Integer 2}", readUnder(host, "{\"b\":1,\"a\":2}", Object.class));
    }

    @Test
    void writeNumbersAsStrings_keepsNumbersNumbers() {
        var host = JsonMapper.builder().enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS).build();
        assertEquals("{n=Double 10.5, i=Integer 7}", readUnder(host, "{\"n\":10.5,\"i\":7}", Object.class));
    }

    @Test
    void writeBigDecimalAsPlain_keepsAStrippedDecimalsScale() {
        // A host that strips trailing zeros from decimal nodes holds 100.0 as 1E+2 (scale -2).
        var host = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        assertEquals(List.of("BigDecimal 1E+2 (scale -2)", "BigDecimal 1E+2 (scale -2)"),
                List.of(readUnder(host, "100.0", BigDecimal.class), readUnder(host, "100.0", Object.class)));
    }

    @Test
    void writeNullPropertiesOff_keepsANullProperty() {
        var host = JsonMapper.builder().disable(JsonNodeFeature.WRITE_NULL_PROPERTIES).build();
        assertEquals("{a=Java null, z=Integer 1}", readUnder(host, "{\"a\":null,\"z\":1}", Object.class));
    }

    @Test
    void writeEmptyJsonArraysOff_keepsAnEmptyArrayProperty() {
        var host = JsonMapper.builder().disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS).build();
        assertEquals("{e=[], z=Integer 1}", readUnder(host, "{\"e\":[],\"z\":1}", Object.class));
    }

    @Test
    void quotePropertyNamesOff_stillReads() {
        var host = JsonMapper.builder().disable(JsonWriteFeature.QUOTE_PROPERTY_NAMES).build();
        String read;
        try {
            read = readUnder(host, "{\"a\":1}", Object.class);
        } catch (SerializationException e) {
            read = "throws " + e.getCause().getClass().getName();
        }
        assertEquals("{a=Integer 1}", read);
    }

    @Test
    void wrapRootValue_doesNotWrapTheTree() {
        var host = JsonMapper.builder().enable(SerializationFeature.WRAP_ROOT_VALUE).build();
        assertEquals("{a=Integer 1}", readUnder(host, "{\"a\":1}", Object.class));
    }

    /**
     * Remaining difference (found, not fixed — read side, not a write feature):
     * a host with {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES} and
     * decimal nodes holds {@code 2.0} as {@code DecimalNode(2)}, which renders as
     * the integral text {@code 2}; an untyped target reads it as an
     * {@code Integer} where the tree path gave {@code BigDecimal 2}. A typed
     * {@code BigDecimal} is unaffected.
     */
    @Test
    void remainingDifference_aStrippedIntegralDecimalReadsAsAnIntegerForAnUntypedTarget() {
        var host = JsonMapper.builder().enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        var node = host.readTree("[2.0]");
        assertEquals("[BigDecimal 2 (scale 0)]", deepDescribe(host.treeToValue(node, Object.class)));  // tree path
        assertEquals(List.of("[Integer 2]", "BigDecimal 2 (scale 0)"),
                List.of(readUnder(host, "[2.0]", Object.class), readUnder(host, "2.0", BigDecimal.class)));
    }

    /**
     * Accepted, in-memory only: a {@code NaN} double node (a live value; a JDBC
     * store never parses one, since {@code jsonb} cannot hold it and the host's
     * default {@code WRITE_NAN_AS_STRINGS} stores the string) renders as the
     * string {@code "NaN"}, which an untyped target reads as a {@code String} —
     * what the store will hand back on replay. A typed {@code double} reads NaN.
     */
    @Test
    void inMemoryNaN_readsAsTheStringAStoreKeeps() {
        var node = MAPPER.createObjectNode().put("v", Double.NaN);
        assertEquals("{v=Double NaN}", deepDescribe(MAPPER.treeToValue(node, Object.class)));          // tree path
        assertEquals(List.of("{v=String NaN}", "Double NaN"), List.of(
                deepDescribe(serializer.deserialize(node, Object.class)),
                describe(serializer.deserialize(node.get("v"), double.class))));
    }

    /**
     * Accepted difference (release note): under the default mapper a stored
     * number exactly halfway between two floats, read into a {@code float},
     * was rounded twice by the tree path (text to {@code double}, then to
     * {@code float}) and is now rounded once (text to {@code float}). Inside a
     * record the text parse is Jackson's own, so the old value cannot be kept
     * there, and the rule is the same at every position.
     */
    @Test
    void acceptedDifference_aHalfwayFloatIsRoundedOnceFromItsText() {
        var node = MAPPER.readTree("1.0000000596046448");
        assertEquals("DoubleNode", node.getClass().getSimpleName());
        assertEquals(1.0f, MAPPER.treeToValue(node, float.class));                            // the tree path
        var record = MAPPER.readTree("{\"name\":\"x\",\"value\":1,\"ratio\":1.0000000596046448}");
        assertEquals(List.of("Float 1.0000001", "Float 1.0000001", "Float 1.0000001"), List.of(
                describe(serializer.deserialize(node, float.class)),
                describe(serializer.deserialize(node, Float.class)),
                describe(serializer.deserialize(record, Ratio.class).ratio())));
    }

    record Ratio(String name, int value, float ratio) {}

    /**
     * Whether a JSON null reads as empty is decided on the raw class Jackson
     * resolves for the target, whatever shape of {@link Type} names it.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("typeShapes")
    void nullReadsByTheResolvedRawClassForEveryTypeShape(String label, Type target, String expected) {
        assertEquals(List.of(expected, expected), List.of(
                outcome(() -> serializer.deserialize(null, target)),
                outcome(() -> serializer.deserialize(NullNode.instance, target))), label);
    }

    static Stream<Arguments> typeShapes() {
        var wildcards = (ParameterizedType) returnTypeOf("wildcards");
        return Stream.of(
                Arguments.of("JavaType Optional<String>",
                        MAPPER.getTypeFactory().constructType(OPTIONAL_STRING_TYPE), "Optional Optional.empty"),
                Arguments.of("JavaType String", MAPPER.getTypeFactory().constructType(String.class), "Java null"),
                Arguments.of("ParameterizedType Optional<String>", OPTIONAL_STRING_TYPE, "Optional Optional.empty"),
                Arguments.of("ParameterizedType List<String>", returnTypeOf("stringList"), "Java null"),
                Arguments.of("type variable T", returnTypeOf("typeVariable"), "Java null"),
                Arguments.of("wildcard ?", wildcards.getActualTypeArguments()[0], "Java null"),
                // Jackson resolves a bounded wildcard to its bound, as it does for a non-null node.
                Arguments.of("wildcard ? extends Optional<String>", wildcards.getActualTypeArguments()[1],
                        "Optional Optional.empty"));
    }

    /** A {@code byte[]} compared by content; anything else by its own {@code equals}. */
    private static Object contentOf(Object value) {
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        if (value instanceof Map<?, ?> map) {                 // order-sensitive: entry order is part of the value
            return map.entrySet().stream().map(e -> List.of(String.valueOf(e.getKey()),
                    Objects.requireNonNullElse(contentOf(e.getValue()), "null"))).toList();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> Objects.requireNonNullElse(contentOf(v), "null")).toList();
        }
        return value;
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
                Arguments.of("10.50", Number.class),
                Arguments.of("""
                        {"s":"\u00e9/\u2028\ud83d\ude00","a":null,"e":[],"o":{},"z":1,"b":[null]}""", Object.class),
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
