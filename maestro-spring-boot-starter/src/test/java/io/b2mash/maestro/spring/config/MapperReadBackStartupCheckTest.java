package io.b2mash.maestro.spring.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 48: at context refresh the starter round-trips a small fixed set of
 * values through the {@link PayloadSerializer} built on the host's mapper and
 * reports each one that does not come back as written —
 * {@code maestro.replay.mapper-check} = {@code warn} (default) | {@code fail} |
 * {@code off}. A customised mapper that reads back what it writes, at the
 * precision the engine relies on, starts silently.
 */
@DisplayName("The mapper read-back startup check (Issue 48)")
class MapperReadBackStartupCheckTest {

    private static final String CHECK_LOGGER = MapperReadBackStartupCheck.class.getName();

    /**
     * Writes an {@code Instant} as epoch milliseconds but reads integer
     * timestamps as seconds (Jackson's default): a time in 2026 reads back as
     * the year 58834 ({@code CurrentTimeReadBackTest.ASYMMETRIC_MILLIS_MAPPER}).
     */
    private static ObjectMapper asymmetricMillis() {
        return JsonMapper.builder()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();
    }

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger checkLog;
    private Level previousLevel;

    @BeforeEach
    void captureTheChecksLog() {
        checkLog = (Logger) LoggerFactory.getLogger(CHECK_LOGGER);
        previousLevel = checkLog.getLevel();
        checkLog.setLevel(Level.DEBUG);
        appender.start();
        checkLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        checkLog.detachAppender(appender);
        checkLog.setLevel(previousLevel);
    }

    @Test
    @DisplayName("the default mapper starts silently")
    void theDefaultMapperStartsSilently() {
        runner(ObjectMapper::new).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    @DisplayName("by default, the asymmetric millisecond mapper starts with one WARN naming Instant")
    void theAsymmetricMillisMapperWarnsByDefault() {
        runner(MapperReadBackStartupCheckTest::asymmetricMillis).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().getFirst())
                    .contains("java.time.Instant")
                    .contains("2026-11-12T10:00:00.123456789Z")
                    .contains("maestro.replay.mapper-check");
        });
    }

    @Test
    @DisplayName("under fail, the asymmetric millisecond mapper fails startup naming Instant")
    void theAsymmetricMillisMapperFailsStartupUnderFail() {
        runner(MapperReadBackStartupCheckTest::asymmetricMillis)
                .withPropertyValues("maestro.replay.mapper-check=fail")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("java.time.Instant")
                            .hasMessageContaining("READ_DATE_TIMESTAMPS_AS_NANOSECONDS")
                            .hasMessageContaining("maestro.replay.mapper-check=warn");
                });
    }

    @Test
    @DisplayName("off skips the probe")
    void offSkipsTheProbe() {
        runner(MapperReadBackStartupCheckTest::asymmetricMillis)
                .withPropertyValues("maestro.replay.mapper-check=off")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings()).isEmpty();
                });
    }

    @Test
    @DisplayName("under fail, a mapper that strips a BigDecimal's trailing zeros fails startup naming BigDecimal only")
    void aScaleDroppingMapperIsNamed() {
        runner(() -> JsonMapper.builder()
                .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build())
                .withPropertyValues("maestro.replay.mapper-check=fail")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("java.math.BigDecimal 10.50")
                            .hasMessageNotContaining("java.time.Instant");
                });
    }

    // ── No false positives: customised mappers that read back what they write ──

    @Test
    @DisplayName("under fail, a symmetric epoch-millisecond mapper starts (sub-millisecond truncation is accepted)")
    void aSymmetricMillisMapperStarts() {
        runner(() -> JsonMapper.builder()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build())
                .withPropertyValues("maestro.replay.mapper-check=fail")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings()).isEmpty();
                });
    }

    @Test
    @DisplayName("under fail, a mapper with a non-UTC default time zone starts (same instant, another offset)")
    void aNonUtcTimeZoneMapperStarts() {
        runner(() -> JsonMapper.builder()
                .defaultTimeZone(TimeZone.getTimeZone("Europe/Berlin"))
                .build())
                .withPropertyValues("maestro.replay.mapper-check=fail")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings()).isEmpty();
                });
    }

    @Test
    @DisplayName("under fail, common customisations start: NON_NULL inclusion, timestamps as decimal seconds")
    void commonCustomisationsStart() {
        for (Supplier<ObjectMapper> mapper : List.<Supplier<ObjectMapper>>of(
                () -> JsonMapper.builder()
                        .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                        .build(),
                () -> JsonMapper.builder().enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build(),
                () -> JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build())) {
            runner(mapper)
                    .withPropertyValues("maestro.replay.mapper-check=fail")
                    .run(ctx -> assertThat(ctx).hasNotFailed());
        }
        assertThat(warnings()).isEmpty();
    }

    private static ApplicationContextRunner runner(Supplier<ObjectMapper> mapper) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
                .withBean(ObjectMapper.class, mapper)
                .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .withBean(DistributedLock.class, InMemoryDistributedLock::new)
                .withPropertyValues("maestro.service-name=mapper-check");
    }

    private List<String> warnings() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }
}
