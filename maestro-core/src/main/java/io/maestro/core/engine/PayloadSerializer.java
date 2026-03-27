package io.maestro.core.engine;

import io.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import java.lang.reflect.Type;

/**
 * Serializes and deserializes activity method arguments and return values
 * to/from Jackson {@link JsonNode} for storage in the memoization log.
 *
 * <p>Wraps Jackson 3 ({@code tools.jackson}) operations and translates
 * all exceptions to {@link SerializationException}.
 *
 * <h2>Null and Void Handling</h2>
 * <ul>
 *   <li>{@link #serialize(Object)} with {@code null} returns {@link NullNode}.</li>
 *   <li>{@link #deserialize(JsonNode, Class)} with {@code null} or {@code NullNode}
 *       returns {@code null}.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Jackson 3's {@link ObjectMapper} is thread-safe.
 * This class holds no mutable state and is safe for concurrent use.
 */
public final class PayloadSerializer {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the Jackson 3 ObjectMapper to use
     */
    public PayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes a value to a {@link JsonNode}.
     *
     * @param value the value to serialize, may be {@code null}
     * @return a JsonNode representation; {@link NullNode} if value is null
     * @throws SerializationException if serialization fails
     */
    public JsonNode serialize(@Nullable Object value) {
        if (value == null) {
            return NullNode.instance;
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to serialize value of type " + value.getClass().getName(), e);
        }
    }

    /**
     * Deserializes a {@link JsonNode} to the specified class.
     *
     * @param <T>  the target type
     * @param node the JsonNode to deserialize, may be {@code null}
     * @param type the target class
     * @return the deserialized value, or {@code null} if the node is null/NullNode
     * @throws SerializationException if deserialization fails
     */
    public <T> @Nullable T deserialize(@Nullable JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize JsonNode to " + type.getName(), e);
        }
    }

    /**
     * Deserializes a {@link JsonNode} using a generic {@link Type}.
     *
     * <p>This is necessary for activity methods that return parameterized
     * types like {@code List<Order>}. Uses {@code Method.getGenericReturnType()}
     * which preserves type parameters, unlike {@code Method.getReturnType()}.
     *
     * @param node the JsonNode to deserialize, may be {@code null}
     * @param type the generic return type (from {@code Method.getGenericReturnType()})
     * @return the deserialized value, or {@code null} if the node is null/NullNode
     * @throws SerializationException if deserialization fails
     */
    public @Nullable Object deserialize(@Nullable JsonNode node, Type type) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            return objectMapper.treeToValue(node, javaType);
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize JsonNode to " + type.getTypeName(), e);
        }
    }
}
