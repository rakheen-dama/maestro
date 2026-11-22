package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.spring.config.MaestroProperties.ReplayProperties.MapperCheck;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Proves at context refresh that the host's mapper reads back what it writes
 * for the values the engine itself records (Issue 48;
 * {@code maestro.replay.mapper-check} = {@code warn} (default) | {@code fail}
 * | {@code off}).
 *
 * <p>Every payload — {@code currentTime()}, activity results, signals, the
 * workflow input — is written with the host's {@code ObjectMapper} and read
 * back with it, on the first run as on every replay. A mapper that writes a
 * value one way and reads it another changes what every workflow computes,
 * silently: the asymmetric epoch-millisecond configuration reads a 2026
 * {@code currentTime()} back as the year 58834. This check round-trips a small
 * fixed set of values the way a recorded payload travels — {@link
 * PayloadSerializer#serialize}, the JSON text a JDBC store writes and parses
 * back ({@link #asStored}), then {@link PayloadSerializer#deserialize} into the
 * declared type — and reports each one that does not come back as written:
 * one {@code WARN} naming all of them under {@code warn}, an
 * {@link IllegalStateException} that fails context refresh under {@code fail}.
 *
 * <p><b>No false positives by design.</b> The probes are fixed constants (never
 * host data), and each is compared at the precision the engine relies on, not
 * by bit-for-bit equality: a time may come back truncated to the millisecond
 * (a symmetric epoch-millisecond mapper — {@code CurrentTimeReadBackTest}'s
 * "the host configuration that truncates" — is deterministic: the live run
 * reads back the same value a replay does) and an {@code OffsetDateTime} may
 * come back in another offset for the same instant (a non-UTC default time
 * zone). A probe whose write or read throws is reported, never thrown, except
 * as the {@code fail} mode's one startup failure.
 *
 * <p><b>Thread safety:</b> Spring calls {@link #afterSingletonsInstantiated()}
 * once, on the refreshing thread; the instance holds no mutable state.
 */
public class MapperReadBackStartupCheck implements SmartInitializingSingleton {

    private static final Logger logger = LoggerFactory.getLogger(MapperReadBackStartupCheck.class);

    private static final Instant INSTANT = Instant.parse("2026-11-12T10:00:00.123456789Z");

    private static final String TIMESTAMP_HINT = "write and read timestamps alike — with "
            + "DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS on and WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS off "
            + "(epoch milliseconds), also disable READ_DATE_TIMESTAMPS_AS_NANOSECONDS, or leave "
            + "WRITE_DATES_AS_TIMESTAMPS off (Jackson 3's default)";

    /**
     * The probe set the issue names. Each earns its place by a misconfiguration
     * this codebase has met:
     * <ul>
     *   <li>an {@code Instant} with nanoseconds — the value {@code currentTime()}
     *       records; catches the asymmetric epoch-millisecond mapper (write
     *       millis, read integers as seconds: the year 58834,
     *       {@code CurrentTimeReadBackTest});</li>
     *   <li>an {@code OffsetDateTime} in UTC — the same timestamp features on
     *       the type activity results most often carry ({@code docs/pitfalls.md}
     *       rule 12), compared by instant;</li>
     *   <li>{@code BigDecimal("10.50")} — money; catches a mapper whose trees
     *       strip trailing zeros ({@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES}
     *       with decimal trees, which the JDBC store's reader then keeps:
     *       {@code AbstractJdbcWorkflowStore.payloadReader}), compared with
     *       {@code equals}, scale included;</li>
     *   <li>{@code Optional.empty()} — must not come back {@code null}, which a
     *       workflow's {@code isPresent()} would throw on; Jackson 3 and the
     *       serializer's own null rule read it as empty, so only a custom
     *       {@code Optional} (de)serializer trips this.</li>
     * </ul>
     */
    private static final List<Probe> PROBES = List.of(
            new Probe("java.time.Instant " + INSTANT, INSTANT, Instant.class,
                    (written, read) -> read instanceof Instant i && sameTime(INSTANT, i), TIMESTAMP_HINT),
            new Probe("java.time.OffsetDateTime " + INSTANT.atOffset(ZoneOffset.UTC),
                    INSTANT.atOffset(ZoneOffset.UTC), OffsetDateTime.class,
                    (written, read) -> read instanceof OffsetDateTime o && sameTime(INSTANT, o.toInstant()),
                    TIMESTAMP_HINT),
            new Probe("java.math.BigDecimal 10.50", new BigDecimal("10.50"), BigDecimal.class,
                    Objects::equals, "keep a decimal's scale — leave JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES off"),
            new Probe("java.util.Optional.empty()", Optional.empty(), new TypeReference<Optional<String>>() { }.getType(),
                    Objects::equals, "read a JSON null or an absent value as Optional.empty(), never null"));

    private final ObjectMapper objectMapper;
    private final PayloadSerializer serializer;
    private final MapperCheck mode;

    /**
     * @param objectMapper the mapper a JDBC store writes and parses payload text with
     * @param serializer   the engine's payload serializer
     * @param mode         {@code maestro.replay.mapper-check}
     */
    public MapperReadBackStartupCheck(ObjectMapper objectMapper, PayloadSerializer serializer, MapperCheck mode) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * @throws IllegalStateException under {@code fail}, when a probe does not
     *                               come back as written — fails context refresh
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (mode == MapperCheck.OFF) {
            return;
        }
        var mismatches = mismatches();
        if (mismatches.isEmpty()) {
            return;
        }
        var message = ("The configured ObjectMapper does not read back what it writes for %d value(s) the engine "
                + "records: %s. Every workflow payload is read back through this mapper, on the first run and on "
                + "every replay, so workflows would compute with these values (docs/pitfalls.md, rule 12). Fix the "
                + "mapper, or set maestro.replay.mapper-check=%s.")
                .formatted(mismatches.size(), String.join("; ", mismatches),
                        mode == MapperCheck.FAIL ? "warn to start anyway" : "fail to refuse to start");
        if (mode == MapperCheck.FAIL) {
            throw new IllegalStateException(message);
        }
        logger.warn("{}", message);
    }

    /** Each probe that does not come back as written, described with its fix. Package-visible for tests. */
    List<String> mismatches() {
        var mismatches = new ArrayList<String>();
        for (var probe : PROBES) {
            Object read;
            try {
                read = serializer.deserialize(asStored(serializer.serialize(probe.value())), probe.type());
            } catch (RuntimeException e) {
                mismatches.add("%s cannot be read back (%s: %s) — %s"
                        .formatted(probe.label(), e.getClass().getSimpleName(), e.getMessage(), probe.hint()));
                continue;
            }
            if (!probe.same().test(probe.value(), read)) {
                mismatches.add("%s reads back as %s — %s".formatted(probe.label(), read, probe.hint()));
            }
        }
        return mismatches;
    }

    /**
     * The node as a JDBC store hands it back: written as JSON text with the
     * mapper and parsed with the store's payload reader — the mapper's own when
     * its trees hold decimals, else the mapper with exact decimals
     * ({@code AbstractJdbcWorkflowStore.payloadReader}, mirrored here because
     * the starter does not depend on the JDBC store).
     */
    private JsonNode asStored(JsonNode node) {
        ObjectReader reader = objectMapper.reader();
        if (!reader.readTree("0.10").isBigDecimal()) {
            reader = reader.with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
        }
        return reader.readTree(objectMapper.writeValueAsString(node));
    }

    /** The same instant, or the same instant truncated to the millisecond (a symmetric epoch-millis mapper). */
    private static boolean sameTime(Instant written, Instant read) {
        return read.equals(written) || read.equals(written.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * One probe: a fixed value, the type it is read back as, what counts as
     * coming back the same, and the fix to name.
     */
    private record Probe(String label, Object value, Type type,
                         BiPredicate<Object, @Nullable Object> same, String hint) { }
}
