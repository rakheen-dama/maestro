package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a payload looks like after a round trip through a real {@code jsonb}
 * column and {@link PostgresWorkflowStore}'s reader — the question an
 * in-memory text round trip cannot settle (audit B § 3.8).
 *
 * <p>Most tests write one {@code ACTIVITY_COMPLETED} event through
 * {@link PostgresWorkflowStore#appendEvent(WorkflowEvent)} and read it back
 * through {@link PostgresWorkflowStore#getEventBySequence(UUID, int)}, exactly
 * as replay does; {@link #everyPayloadColumnReadsATypedBigDecimalExactly}
 * covers the other payload columns.
 */
@DisplayName("A payload read back from a real jsonb column")
class PostgresPayloadFidelityTest extends PostgresTestSupport {

    /** A typed activity result carrying a decimal. */
    record Quote(BigDecimal amount) {}

    /** A typed result with a {@code double} field. */
    record Doubles(double d) {}

    /** A typed result with a {@code float} field. */
    record Floats(float f) {}

    /** A typed result with a {@link Number} field (Jackson's untyped-number rule). */
    record Num(Number n) {}

    /** A typed result with integer fields of each width. */
    record Ints(Integer i, Long l, BigInteger b) {}

    /** A typed result with an {@link Instant} read from a numeric timestamp. */
    record At(Instant at) {}

    private PayloadSerializer serializer;

    @BeforeEach
    void createSerializer() {
        serializer = new PayloadSerializer(objectMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678901234567890.123456789", "1.123456789012345678", "10.50"})
    @DisplayName("a typed BigDecimal reads back exactly, scale included")
    void aTypedBigDecimalReadsBackExactly(String digits) {
        var read = roundTrip(serializer.serialize(new Quote(new BigDecimal(digits))));
        assertEquals(new BigDecimal(digits), serializer.deserialize(read, Quote.class).amount());   // equals is scale-sensitive
    }

    @Test
    @DisplayName("an untyped decimal reads back as a Double (Object, Map<String, Object>, List<Object>)")
    void anUntypedDecimalReadsBackAsADouble() {          // C1 guard: must stay green through Task 6
        var read = roundTrip(serializer.serialize(Map.of("d", new BigDecimal("1.123456789012345678"))));

        var d = ((Map<?, ?>) serializer.deserialize(read, Object.class)).get("d");
        assertEquals(Double.class, d.getClass());
        assertEquals(1.1234567890123457d, d);

        var mapType = new TypeReference<Map<String, Object>>() {}.getType();
        var fromMap = ((Map<?, ?>) serializer.deserialize(read, mapType)).get("d");
        assertEquals(Double.class, fromMap.getClass());
        assertEquals(1.1234567890123457d, fromMap);

        var readList = roundTrip(serializer.serialize(List.of(new BigDecimal("1.123456789012345678"))));
        var listType = new TypeReference<List<Object>>() {}.getType();
        var fromList = ((List<?>) serializer.deserialize(readList, listType)).get(0);
        assertEquals(Double.class, fromList.getClass());
        assertEquals(1.1234567890123457d, fromList);
    }

    @Test
    @DisplayName("jsonb reorders object keys shortest first")
    void jsonbReordersObjectKeysShortestFirst() {         // characterization: justifies Task 7
        var read = roundTrip(objectMapper.createObjectNode().put("aa", 1).put("b", 2));
        assertNotEquals(List.of("aa", "b"), propertyNames(read), "jsonb kept insertion order");
        assertEquals(List.of("b", "aa"), propertyNames(read));
    }

    @Test
    @DisplayName("jsonb rewrites a whole exponent number as an integer")
    void jsonbRewritesAWholeExponentNumberAsAnInteger() {  // characterization: justifies Task 7
        var read = roundTrip(serializer.serialize(Map.of("x", 1.0E7)));
        assertTrue(read.get("x").isIntegralNumber(), read.toString());
        assertEquals("{\"x\":10000000}", read.toString());   // jsonb hands back the integer text; exact parsing keeps it an int
        assertEquals(Integer.class, ((Map<?, ?>) serializer.deserialize(read, Object.class)).get("x").getClass());
    }

    // ── C1 (WP1 Task 6): exact decimals for typed targets, nothing else moves ──

    /**
     * C1, on real Postgres: a value written as an activity result, read back by
     * the store and deserialized as replay does, into each kind of target. Typed
     * decimal targets get the stored digits and scale; every untyped position
     * still gets a {@code Double}; {@code double}, {@code float}, {@link Number},
     * integer and {@link JsonNode} targets read as before.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("c1Rows")
    @DisplayName("C1 matrix: what each target reads from a jsonb decimal")
    void c1Matrix(String label, Object written, Type target, Function<Object, Object> leaf, String expected) {
        var read = roundTrip(serializer.serialize(written));
        assertEquals(expected, describe(leaf.apply(serializer.deserialize(read, target))), label);
    }

    static Stream<Arguments> c1Rows() {
        var long20 = new BigDecimal("12345678901234567890.123456789");
        var scale2 = new BigDecimal("10.50");
        var dp18 = new BigDecimal("1.123456789012345678");
        Function<Object, Object> self = v -> v;
        Function<Object, Object> amount = v -> ((Quote) v).amount();
        Function<Object, Object> first = v -> ((List<?>) v).get(0);
        Function<Object, Object> keyA = v -> ((Map<?, ?>) v).get("a");
        Function<Object, Object> keyD = v -> ((Map<?, ?>) v).get("d");
        Type bigDecimalList = new TypeReference<List<BigDecimal>>() {}.getType();
        Type bigDecimalMap = new TypeReference<Map<String, BigDecimal>>() {}.getType();
        Type objectMap = new TypeReference<Map<String, Object>>() {}.getType();
        Type objectList = new TypeReference<List<Object>>() {}.getType();
        return Stream.of(
                // typed BigDecimal: exact, scale included
                Arguments.of("BigDecimal, top level, 29 digits", long20, BigDecimal.class, self,
                        "BigDecimal 12345678901234567890.123456789 (scale 9)"),
                Arguments.of("BigDecimal, top level, 10.50", scale2, BigDecimal.class, self,
                        "BigDecimal 10.50 (scale 2)"),
                Arguments.of("BigDecimal, record field, 29 digits", new Quote(long20), Quote.class, amount,
                        "BigDecimal 12345678901234567890.123456789 (scale 9)"),
                Arguments.of("BigDecimal, record field, 10.50", new Quote(scale2), Quote.class, amount,
                        "BigDecimal 10.50 (scale 2)"),
                Arguments.of("BigDecimal, list element, 29 digits", List.of(long20), bigDecimalList, first,
                        "BigDecimal 12345678901234567890.123456789 (scale 9)"),
                Arguments.of("BigDecimal, list element, 10.50", List.of(scale2), bigDecimalList, first,
                        "BigDecimal 10.50 (scale 2)"),
                Arguments.of("BigDecimal, typed map value, 29 digits", Map.of("a", long20), bigDecimalMap, keyA,
                        "BigDecimal 12345678901234567890.123456789 (scale 9)"),
                Arguments.of("BigDecimal, typed map value, 10.50", Map.of("a", scale2), bigDecimalMap, keyA,
                        "BigDecimal 10.50 (scale 2)"),
                // untyped: Double, as before (an in-flight workflow casting to Double keeps replaying)
                Arguments.of("Object", Map.of("d", dp18), Object.class, keyD, "Double 1.1234567890123457"),
                Arguments.of("Map<String, Object>", Map.of("d", dp18), objectMap, keyD, "Double 1.1234567890123457"),
                Arguments.of("List<Object>", List.of(dp18), objectList, first, "Double 1.1234567890123457"),
                Arguments.of("Object, 10.50", Map.of("d", scale2), Object.class, keyD, "Double 10.5"),
                Arguments.of("Number field", Map.of("n", scale2), Num.class, leaf(v -> ((Num) v).n()), "Double 10.5"),
                // double / float fields: the value parsed from the stored text
                Arguments.of("double field", Map.of("d", dp18), Doubles.class, leaf(v -> ((Doubles) v).d()),
                        "Double " + Double.parseDouble("1.123456789012345678")),
                Arguments.of("float field", Map.of("f", new BigDecimal("0.1")), Floats.class, leaf(v -> ((Floats) v).f()),
                        "Float 0.1"),
                // integers, untyped and typed
                Arguments.of("untyped int", Map.of("d", 5), Object.class, keyD, "Integer 5"),
                Arguments.of("untyped long", Map.of("d", 1L << 60), Object.class, keyD, "Long 1152921504606846976"),
                Arguments.of("untyped big integer", Map.of("d", BigInteger.TWO.pow(70)), Object.class, keyD,
                        "BigInteger 1180591620717411303424"),
                Arguments.of("typed Integer, Long, BigInteger",
                        new Ints(5, 1L << 60, BigInteger.TWO.pow(70)), Ints.class, self,
                        "Ints Ints[i=5, l=1152921504606846976, b=1180591620717411303424]"),
                // a JsonNode target: a DoubleNode, as before (the host reader re-parses the text)
                Arguments.of("JsonNode", Map.of("d", dp18), JsonNode.class, leaf(v -> ((JsonNode) v).get("d")),
                        "DoubleNode 1.1234567890123457"),
                Arguments.of("JsonNode, 10.50", Map.of("d", scale2), JsonNode.class, leaf(v -> ((JsonNode) v).get("d")),
                        "DoubleNode 10.5"),
                // java.time from a numeric timestamp asks for a decimal, so it becomes exact too (plan D-6)
                Arguments.of("Instant from a numeric timestamp", Map.of("at", new BigDecimal("1762941600.123456789")),
                        At.class, leaf(v -> ((At) v).at()), "Instant " + Instant.ofEpochSecond(1762941600L, 123456789L)));
    }

    /** Gives a row's leaf extractor its type inside {@code Arguments.of}. */
    private static Function<Object, Object> leaf(Function<Object, Object> extractor) {
        return extractor;
    }

    /**
     * A host mapper that already reads untyped decimals as {@code BigDecimal}
     * (either feature) keeps doing so, exactly, through a store built on it.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"JsonNodeFeature", "DeserializationFeature"})
    @DisplayName("a host mapper with USE_BIG_DECIMAL_FOR_FLOATS gets BigDecimal untyped too")
    void aHostMapperWithBigDecimalForFloatsGetsBigDecimalUntypedToo(String feature) {
        ObjectMapper host = feature.equals("JsonNodeFeature")
                ? JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
                : JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        var hostStore = new PostgresWorkflowStore(dataSource, host);
        var hostSerializer = new PayloadSerializer(host);

        var read = roundTrip(hostStore, hostSerializer.serialize(Map.of("d", new BigDecimal("1.123456789012345678"))));

        var untyped = ((Map<?, ?>) hostSerializer.deserialize(read, Object.class)).get("d");
        assertEquals("BigDecimal 1.123456789012345678 (scale 18)", describe(untyped));
    }

    /** The payload columns the store parses (each through its own row mapper). */
    enum PayloadColumn { EVENT_PAYLOAD, INSTANCE_INPUT, INSTANCE_OUTPUT, SIGNAL_PAYLOAD }

    static Stream<Arguments> everyPayloadColumn() {
        return Stream.of(PayloadColumn.values()).flatMap(column -> Stream.of("1.123456789012345678", "10.50")
                .map(digits -> Arguments.of(column, digits)));
    }

    /**
     * Every column a payload is read from — event {@code payload}
     * ({@code getEvents}), instance {@code input} and {@code output}
     * ({@code getInstance}) and signal {@code payload}
     * ({@code getUnconsumedSignals}) — hands a typed {@code BigDecimal} back exactly.
     */
    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("everyPayloadColumn")
    @DisplayName("every payload column reads a typed BigDecimal exactly")
    void everyPayloadColumnReadsATypedBigDecimalExactly(PayloadColumn column, String digits) {
        var written = serializer.serialize(new Quote(new BigDecimal(digits)));
        var workflowId = "payload-column-" + UUID.randomUUID();
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JsonNode read = switch (column) {
            case EVENT_PAYLOAD -> {
                var instance = createInstance(store, workflowId);
                store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                        EventType.ACTIVITY_COMPLETED, "Quotes.quote", written, now));
                yield store.getEvents(instance.id()).getFirst().payload();
            }
            case INSTANCE_INPUT -> {
                store.createInstance(newInstance(workflowId).toBuilder().input(written).build());
                yield store.getInstance(workflowId).orElseThrow().input();
            }
            case INSTANCE_OUTPUT -> {
                var instance = createInstance(store, workflowId);
                store.updateInstance(instance.toBuilder().status(WorkflowStatus.COMPLETED).output(written)
                        .completedAt(now).updatedAt(now).version(instance.version() + 1).build());
                yield store.getInstance(workflowId).orElseThrow().output();
            }
            case SIGNAL_PAYLOAD -> {
                var instance = createInstance(store, workflowId);
                store.saveSignal(new WorkflowSignal(UUID.randomUUID(), instance.id(), workflowId, "quote",
                        written, false, now, null));
                yield store.getUnconsumedSignals(workflowId, "quote").getFirst().payload();
            }
        };
        assertEquals(describe(new BigDecimal(digits)),
                describe(serializer.deserialize(read, Quote.class).amount()), column.name());
    }

    /**
     * Plan D-3's one exception, pinned: a {@code SIGNAL_RECEIVED} memo copies
     * the signal row's payload as the store read it, so the copy now carries
     * the exact decimal where it used to carry the double-rounded one.
     */
    @Test
    @DisplayName("a SIGNAL_RECEIVED memo copies the signal's exact decimal")
    void signalReceivedCopiesTheExactDecimal() throws SQLException {
        var workflowId = "signal-copy-" + UUID.randomUUID();
        var instance = createInstance(store, workflowId);
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.saveSignal(new WorkflowSignal(UUID.randomUUID(), instance.id(), workflowId, "price",
                serializer.serialize(Map.of("d", new BigDecimal("1.123456789012345678"))), false, now, null));
        var signal = store.getUnconsumedSignals(workflowId, "price").getFirst();

        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.SIGNAL_RECEIVED, "price", signal.payload(), now));

        var stored = payloadTexts(instance.id()).getFirst();
        assertTrue(stored.contains("1.123456789012345678"), stored);
    }

    /**
     * What is written does not change: the {@code jsonb} text Postgres stores
     * for these payloads, recorded at {@code 03b17c0} (before Task 6), is the
     * text it stores now.
     */
    @Test
    @DisplayName("the stored jsonb text is the text stored before Task 6")
    void theStoredJsonbTextIsUnchanged() throws SQLException {
        var instance = createInstance(store, "stored-text-" + UUID.randomUUID());
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var payloads = List.of(
                serializer.serialize(new Quote(new BigDecimal("12345678901234567890.123456789"))),
                serializer.serialize(new Quote(new BigDecimal("10.50"))),
                serializer.serialize(List.of(new BigDecimal("1.123456789012345678"), new BigDecimal("1E+2"))),
                serializer.serialize(new Doubles(0.1)),
                serializer.serialize(new Floats(0.1f)),
                serializer.serialize(Map.of("x", 1.0E7)),
                serializer.serialize(new Ints(5, 1L << 60, BigInteger.TWO.pow(70))),
                serializer.serialize(Map.of("n", -0.0d)),
                serializer.serialize(Map.of("s", "10.50")));
        for (int i = 0; i < payloads.size(); i++) {
            store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), i + 1,
                    EventType.ACTIVITY_COMPLETED, "Step.s" + i, payloads.get(i), now));
        }
        assertEquals(List.of(
                "{\"amount\": 12345678901234567890.123456789}",
                "{\"amount\": 10.50}",
                "[1.123456789012345678, 100]",
                "{\"d\": 0.1}",
                "{\"f\": 0.1}",
                "{\"x\": 10000000}",
                "{\"b\": 1180591620717411303424, \"i\": 5, \"l\": 1152921504606846976}",
                "{\"n\": 0.0}",
                "{\"s\": \"10.50\"}"), payloadTexts(instance.id()));
    }

    /**
     * {@code jsonb} accepts {@code 1E+400}, beyond {@code double} range, and
     * hands it back as the 401-digit integer it equals — never as exponent
     * text — so on Postgres it reads as a {@code BigInteger} untyped and as a
     * scale-0 {@code BigDecimal} typed, before and after Task 6. (A text store
     * that hands back {@code 1E+400} itself is pinned in
     * {@code JdbcPayloadDecimalReaderTest}.)
     */
    @Test
    @DisplayName("jsonb hands 1E+400 back as a 401-digit integer")
    void aDecimalBeyondDoubleRangeComesBackFromJsonbAsAWholeNumber() {
        var read = roundTrip(serializer.serialize(Map.of("x", new BigDecimal("1E+400"))));
        var whole = BigInteger.TEN.pow(400);
        assertEquals(whole.toString(), read.get("x").toString());
        assertEquals("BigInteger " + whole, describe(((Map<?, ?>) serializer.deserialize(read, Object.class)).get("x")));
        assertEquals("BigDecimal " + whole + " (scale 0)",
                describe(serializer.deserialize(read.get("x"), BigDecimal.class)));
    }

    /**
     * {@code jsonb} has no negative zero: {@code -0.0} is stored as {@code 0.0},
     * so an untyped target read {@code 0.0} from Postgres before Task 6 and
     * still does — the decimal tree's loss of the sign cannot show here.
     */
    @Test
    @DisplayName("jsonb stores -0.0 as 0.0, so an untyped read is Double 0.0 before and after")
    void jsonbHasNoNegativeZero() {
        var read = roundTrip(serializer.serialize(Map.of("n", -0.0d)));
        assertEquals("Double 0.0", describe(((Map<?, ?>) serializer.deserialize(read, Object.class)).get("n")));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Appends {@code payload} as an {@code ACTIVITY_COMPLETED} event of a fresh
     * instance at the next sequence (1) and returns the payload as the store
     * reads it back.
     */
    private JsonNode roundTrip(JsonNode payload) {
        return roundTrip(store, payload);
    }

    private static JsonNode roundTrip(PostgresWorkflowStore through, JsonNode payload) {
        var instance = createInstance(through, "payload-fidelity-" + UUID.randomUUID());
        int seq = instance.eventSequence() + 1;
        through.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), seq,
                EventType.ACTIVITY_COMPLETED, "Quotes.quote", payload,
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return through.getEventBySequence(instance.id(), seq).orElseThrow().payload();
    }

    private static WorkflowInstance createInstance(PostgresWorkflowStore through, String workflowId) {
        return through.createInstance(newInstance(workflowId));
    }

    private static WorkflowInstance newInstance(String workflowId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }

    /** The stored {@code jsonb} text of every event of {@code instanceId}, in sequence order. */
    private List<String> payloadTexts(UUID instanceId) throws SQLException {
        var texts = new ArrayList<String>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT payload::text FROM maestro_workflow_event "
                     + "WHERE workflow_instance_id = ? ORDER BY sequence_number")) {
            ps.setObject(1, instanceId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    texts.add(rs.getString(1));
                }
            }
        }
        return texts;
    }

    /** A value with its class; a {@code BigDecimal} with its scale, since {@code equals} is scale-sensitive. */
    private static String describe(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal d) {
            return "BigDecimal " + d + " (scale " + d.scale() + ")";
        }
        return value.getClass().getSimpleName() + " " + value;
    }

    private static List<String> propertyNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
