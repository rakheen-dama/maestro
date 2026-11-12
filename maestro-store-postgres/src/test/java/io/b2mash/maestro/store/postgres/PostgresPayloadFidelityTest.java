package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a payload looks like after a round trip through a real {@code jsonb}
 * column and {@link PostgresWorkflowStore}'s reader — the question an
 * in-memory text round trip cannot settle (audit B § 3.8).
 *
 * <p>Every test writes one {@code ACTIVITY_COMPLETED} event through
 * {@link PostgresWorkflowStore#appendEvent(WorkflowEvent)} and reads it back
 * through {@link PostgresWorkflowStore#getEventBySequence(UUID, int)}, exactly
 * as replay does.
 */
@DisplayName("A payload read back from a real jsonb column")
class PostgresPayloadFidelityTest extends PostgresTestSupport {

    /** A typed activity result carrying a decimal. */
    record Quote(BigDecimal amount) {}

    private PayloadSerializer serializer;

    @BeforeEach
    void createSerializer() {
        serializer = new PayloadSerializer(objectMapper);
    }

    @Disabled("red until Task 6: 3.8 decimal defect proven on Postgres — see commit body")
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
        assertEquals(Integer.class, ((Map<?, ?>) serializer.deserialize(read, Object.class)).get("x").getClass());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Appends {@code payload} as an {@code ACTIVITY_COMPLETED} event of a fresh
     * instance at the next sequence (1) and returns the payload as the store
     * reads it back.
     */
    private JsonNode roundTrip(JsonNode payload) {
        var instance = createInstance("payload-fidelity-" + UUID.randomUUID());
        int seq = instance.eventSequence() + 1;
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), seq,
                EventType.ACTIVITY_COMPLETED, "Quotes.quote", payload,
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return store.getEventBySequence(instance.id(), seq).orElseThrow().payload();
    }

    private WorkflowInstance createInstance(String workflowId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return store.createInstance(WorkflowInstance.builder()
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
                .build());
    }

    private static List<String> propertyNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
