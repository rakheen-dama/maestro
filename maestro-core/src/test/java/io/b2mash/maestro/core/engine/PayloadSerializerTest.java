package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PayloadSerializer}.
 *
 * <p>Uses Jackson ({@code com.fasterxml.jackson.databind.ObjectMapper}) with a
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
        assertEquals("workflow-1", node.get("name").textValue());
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
}
