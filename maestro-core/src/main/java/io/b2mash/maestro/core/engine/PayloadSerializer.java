package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
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
 *   <li>Both {@code deserialize} overloads read a {@code null} node or a
 *       {@link NullNode} as the mapper's null value for the target type:
 *       {@code null} for an ordinary reference (and for {@code void}/{@code Void}),
 *       {@code Optional.empty()} for {@code Optional} (likewise
 *       {@code OptionalInt}/{@code OptionalLong}/{@code OptionalDouble}), and
 *       {@link NullNode} for {@link JsonNode}. A primitive target fails with
 *       {@link SerializationException} while the mapper has
 *       {@code DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES} on (Jackson 3's
 *       default), and reads as {@code 0}/{@code false} while it is off.</li>
 * </ul>
 *
 * <h2>Read through JSON text</h2>
 * <p>Both {@code deserialize} overloads render the node to JSON text and parse
 * that text with the same mapper, exactly as a JDBC store parses a payload
 * column. A value workflow code receives is therefore what a text store returns
 * for it, whichever store holds the history: an untyped number reads as the
 * smallest of {@code Integer}, {@code Long} and {@code BigInteger} that holds it,
 * or as {@code Double} — never as the {@code Float}, or the {@code Long} holding
 * a small value, that an in-memory node may carry; and a {@link JsonNode} target
 * receives a fresh tree, never the stored node itself, so workflow code cannot
 * mutate recorded history.
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
     * @return the deserialized value; for a {@code null} node or {@link NullNode},
     *         the target type's null value (class Javadoc)
     * @throws SerializationException if deserialization fails
     */
    public <T> @Nullable T deserialize(@Nullable JsonNode node, Class<T> type) {
        try {
            return objectMapper.readerFor(type).readValue(textOf(node));
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
     * @return the deserialized value; for a {@code null} node or {@link NullNode},
     *         the target type's null value (class Javadoc)
     * @throws SerializationException if deserialization fails
     */
    public @Nullable Object deserialize(@Nullable JsonNode node, Type type) {
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            return objectMapper.readerFor(javaType).readValue(textOf(node));
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize JsonNode to " + type.getTypeName(), e);
        }
    }

    /** The node's JSON text as this mapper writes it; a {@code null} node is JSON {@code null}. */
    private byte[] textOf(@Nullable JsonNode node) {
        return objectMapper.writeValueAsBytes(node == null ? NullNode.instance : node);
    }
}
