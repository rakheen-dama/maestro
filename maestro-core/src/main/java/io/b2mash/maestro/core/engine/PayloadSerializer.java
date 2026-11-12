package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

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
 *       {@link NullNode} whose target's raw class is {@link Optional},
 *       {@link OptionalInt}, {@link OptionalLong} or {@link OptionalDouble} as
 *       that type's empty value, obtained through the mapper's own null handling
 *       (the text path), so a host mapper's configuration is respected.
 *       {@code null} is never a legitimate {@code Optional}: a caller that calls
 *       {@code isPresent()} on it fails.</li>
 *   <li>For every other target — primitives, wrappers, {@link JsonNode},
 *       {@code AtomicReference}, records, collections, {@code Object},
 *       {@code void}/{@code Void}, everything — a {@code null} node or a
 *       {@link NullNode} reads as Java {@code null}, without consulting the
 *       mapper.</li>
 *   <li>A {@code null} nested inside a non-null document is the mapper's
 *       business, as it always was.</li>
 * </ul>
 *
 * <h2>Read through JSON text</h2>
 * <p>Both {@code deserialize} overloads render a non-null node (and the JSON
 * null an {@code Optional}-family target reads) to JSON text and parse that
 * text with the same mapper, exactly as a JDBC store parses a payload
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
     *         the empty value of an {@code Optional}-family target, otherwise
     *         {@code null} (class Javadoc)
     * @throws SerializationException if deserialization fails
     */
    public <T> @Nullable T deserialize(@Nullable JsonNode node, Class<T> type) {
        if (isAbsent(node) && !readsNullAsEmpty(type)) {
            return null;
        }
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
     *         the empty value of an {@code Optional}-family target, otherwise
     *         {@code null} (class Javadoc)
     * @throws SerializationException if deserialization fails
     */
    public @Nullable Object deserialize(@Nullable JsonNode node, Type type) {
        if (isAbsent(node) && !readsNullAsEmpty(type)) {
            return null;
        }
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            return objectMapper.readerFor(javaType).readValue(textOf(node));
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize JsonNode to " + type.getTypeName(), e);
        }
    }

    private static boolean isAbsent(@Nullable JsonNode node) {
        return node == null || node.isNull();
    }

    /**
     * Whether a JSON null reads as the target's empty value rather than Java
     * {@code null}: the {@code Optional} family, by raw class.
     */
    private static boolean readsNullAsEmpty(Type type) {
        var raw = type instanceof ParameterizedType p ? p.getRawType() : type;
        return raw == Optional.class || raw == OptionalInt.class
                || raw == OptionalLong.class || raw == OptionalDouble.class;
    }

    /** The node's JSON text as this mapper writes it; a {@code null} node is JSON {@code null}. */
    private byte[] textOf(@Nullable JsonNode node) {
        return objectMapper.writeValueAsBytes(node == null ? NullNode.instance : node);
    }
}
