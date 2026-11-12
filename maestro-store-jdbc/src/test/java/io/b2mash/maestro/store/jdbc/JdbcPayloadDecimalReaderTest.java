package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tree {@link AbstractJdbcWorkflowStore} parses a payload column into
 * (WP1 Task 6, C1), for each kind of host mapper, without a database: the
 * column text is handed to {@link AbstractJdbcWorkflowStore#getJsonValue} as
 * a text column returns it.
 *
 * <p>Postgres coverage of the same reader is {@code PostgresPayloadFidelityTest};
 * this class pins what {@code jsonb} cannot show — how each host mapper's
 * features shape the tree, and text {@code jsonb} never returns
 * ({@code 1E+400}, {@code -0.0}).
 */
@DisplayName("AbstractJdbcWorkflowStore parses payload decimals exactly")
class JdbcPayloadDecimalReaderTest {

    private static final String DECIMALS = "[2.0,10.50,1.123456789012345678]";
    private static final String EXACT = "DecimalNode 2.0, DecimalNode 10.50, DecimalNode 1.123456789012345678";

    static Stream<Arguments> hostMappers() {
        return Stream.of(
                // a host whose own trees hold doubles: the store now builds exact DecimalNodes
                Arguments.of("default", JsonMapper.builder().build(), EXACT),
                Arguments.of("STRIP_TRAILING_BIGDECIMAL_ZEROES on (decimals off)",
                        JsonMapper.builder().enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build(), EXACT),
                Arguments.of("JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS explicitly off",
                        JsonMapper.builder().disable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build(), EXACT),
                // a host whose own trees already hold decimals: its own tree, unchanged
                Arguments.of("JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS on",
                        JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build(), EXACT),
                Arguments.of("DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS on",
                        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build(), EXACT),
                Arguments.of("JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS + STRIP_TRAILING_BIGDECIMAL_ZEROES on",
                        JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build(),
                        "DecimalNode 2, DecimalNode 10.5, DecimalNode 1.123456789012345678"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostMappers")
    @DisplayName("each host mapper: the node a payload decimal is parsed into")
    void theStoreParsesDecimalsExactly(String host, ObjectMapper mapper, String expected) throws SQLException {
        assertEquals(expected, describeElements(read(mapper, DECIMALS)), host);
    }

    /**
     * C1 under a host that strips trailing zeros but keeps doubles: the exact
     * tree must not strip {@code 2.0} to the integral {@code 2}, which an
     * untyped target would read as an {@code Integer}.
     */
    @Test
    @DisplayName("a stripping host still reads an untyped 2.0 as Double 2.0")
    void aStrippingHostStillReadsAnUntypedDecimalAsADouble() throws SQLException {
        var host = JsonMapper.builder().enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        var untyped = ((Map<?, ?>) new PayloadSerializer(host).deserialize(read(host, "{\"d\":2.0}"), Object.class))
                .get("d");
        assertEquals("Double 2.0", describe(untyped));
    }

    /**
     * A decimal beyond {@code double} range in a text column: the tree used
     * to hold {@code DoubleNode(Infinity)}, which the serializer's text path
     * renders as the string {@code "Infinity"}; the exact tree holds
     * {@code 1E+400}, which an untyped target under the default mapper reads
     * as {@code Double} infinity, as the tree path did before WP1 Task 5.
     * ({@code jsonb} never returns this text; see {@code PostgresPayloadFidelityTest}.)
     */
    @Test
    @DisplayName("an untyped 1E+400 reads as Double infinity under the default mapper")
    void anUntypedDecimalBeyondDoubleRangeReadsAsInfinity() throws SQLException {
        var mapper = JsonMapper.builder().build();
        var untyped = ((Map<?, ?>) new PayloadSerializer(mapper).deserialize(read(mapper, "{\"x\":1E+400}"),
                Object.class)).get("x");
        assertEquals("Double Infinity", describe(untyped));
    }

    /**
     * Accepted difference, text stores only: {@code BigDecimal} has no negative
     * zero, so a column holding {@code -0.0} now reads as {@code 0.0} for an
     * untyped target. Postgres {@code jsonb} already stores {@code -0.0} as
     * {@code 0.0}, so the Postgres store returned {@code 0.0} before as well.
     */
    @Test
    @DisplayName("accepted, text stores only: -0.0 reads as Double 0.0")
    void aNegativeZeroLosesItsSignInATextStore() throws SQLException {
        var mapper = JsonMapper.builder().build();
        var untyped = ((Map<?, ?>) new PayloadSerializer(mapper).deserialize(read(mapper, "{\"n\":-0.0}"),
                Object.class)).get("n");
        assertEquals("Double 0.0", describe(untyped));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** {@code columnText} as the store parses a payload column holding it. */
    private static @Nullable JsonNode read(ObjectMapper mapper, String columnText) throws SQLException {
        return new TextColumnStore(mapper).getJsonValue(textColumn(columnText), "payload");
    }

    private static String describeElements(JsonNode array) {
        var parts = new ArrayList<String>();
        array.forEach(n -> parts.add(n.getClass().getSimpleName() + " " + n));
        return String.join(", ", parts);
    }

    private static String describe(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }

    /** A result set whose every {@code getString} returns {@code text}; nothing else is used. */
    private static ResultSet textColumn(String text) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getString")) {
                        return text;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The abstract store with nothing overridden but what it must; no SQL runs. */
    private static final class TextColumnStore extends AbstractJdbcWorkflowStore {
        TextColumnStore(ObjectMapper mapper) {
            super(neverUsed(), JdbcStoreConfiguration.withDefaults(mapper));
        }

        @Override
        protected String getDueTimersSql() {
            throw new UnsupportedOperationException("not exercised");
        }

        private static DataSource neverUsed() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
