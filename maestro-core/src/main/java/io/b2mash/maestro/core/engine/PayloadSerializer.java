package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.NullNode;

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
 * column. A value read from a node is therefore what a text store returns for
 * that node: an untyped number reads as the smallest of {@code Integer},
 * {@code Long} and {@code BigInteger} that holds it, or as {@code Double} —
 * never as the {@code Float}, or the {@code Long} holding a small value, that an
 * in-memory node may carry; and a {@link JsonNode} target receives a fresh tree,
 * never the node itself, so workflow code cannot mutate recorded history.
 *
 * <p>This does not make every store agree with the value a live run computed:
 * a store may normalise what it keeps. Postgres {@code jsonb}, for one, stores a
 * whole exponent number such as {@code 1.0E10} as {@code 10000000000}, which an
 * untyped target reads back as a {@code Long} where the live node gave a
 * {@code Double}.
 *
 * <p>When the mapper has {@link JsonNodeFeature#USE_BIG_DECIMAL_FOR_FLOATS} on,
 * a store's {@code readTree} builds {@code DecimalNode}s, which an untyped target
 * read as {@code BigDecimal} on the tree path; the text reader then also enables
 * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}, so untyped targets
 * keep getting that {@code BigDecimal}, scale included. The rule keys on the
 * mapper's feature, never on the node: under a mapper without it, an untyped
 * target reads even a {@code DecimalNode} as a {@code Double}.
 *
 * <p>One value differs from the tree path and is accepted: under a mapper
 * without decimal nodes, a number exactly halfway between two {@code float}s,
 * read into a {@code float}, is now rounded once from its text instead of twice
 * (to {@code double}, then to {@code float}); {@code 1.0000000596046448} reads as
 * {@code 1.0000001f}, where it read as {@code 1.0f}.
 *
 * <p><b>Thread safety:</b> Jackson 3's {@link ObjectMapper} is thread-safe.
 * This class holds no mutable state and is safe for concurrent use.
 */
public final class PayloadSerializer {

    private final ObjectMapper objectMapper;

    /** Whether the mapper's {@code readTree} builds {@code DecimalNode}s (class Javadoc). */
    private final boolean decimalNodes;

    /**
     * @param objectMapper the Jackson 3 ObjectMapper to use
     */
    public PayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.decimalNodes = objectMapper.isEnabled(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS);
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
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            if (isAbsent(node) && !readsNullAsEmpty(javaType)) {
                return null;
            }
            return reader(javaType).readValue(textOf(node));
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
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            if (isAbsent(node) && !readsNullAsEmpty(javaType)) {
                return null;
            }
            return reader(javaType).readValue(textOf(node));
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
     * {@code null}: the {@code Optional} family, by the raw class Jackson
     * resolves for the target (so a {@code JavaType}, a parameterized type, a
     * type variable and a wildcard are judged as Jackson reads them).
     */
    private static boolean readsNullAsEmpty(JavaType type) {
        var raw = type.getRawClass();
        return raw == Optional.class || raw == OptionalInt.class
                || raw == OptionalLong.class || raw == OptionalDouble.class;
    }

    /** The text reader for {@code type}: the mapper's own, with exact decimals when its trees carry them. */
    private ObjectReader reader(JavaType type) {
        var reader = objectMapper.readerFor(type);
        return decimalNodes ? reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS) : reader;
    }

    /** The node's JSON text as this mapper writes it; a {@code null} node is JSON {@code null}. */
    private byte[] textOf(@Nullable JsonNode node) {
        return objectMapper.writeValueAsBytes(node == null ? NullNode.instance : node);
    }
}
