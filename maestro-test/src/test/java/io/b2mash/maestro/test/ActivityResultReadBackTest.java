package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A live activity call returns the value a replay of that call returns: the
 * read-back of the stored payload, not the object the activity returned (WP1
 * D7, audit B 3.8). Before the fix, a result type that does not round-trip
 * identically through Jackson — an {@code OffsetDateTime} with a non-UTC
 * offset, an unordered {@code Set}, a subtype under a declared supertype —
 * made a later activity's argument fingerprint differ between the live run and
 * the replay, so an unchanged workflow stood down as
 * {@code nondeterministic_history} after any crash.
 *
 * <p>Ports {@code AuditB38ReplayFingerprintTest} (the two end-to-end repros,
 * with a positive {@code COMPLETED} assertion in place of the repro's absence
 * assertion) and {@code AuditB38FingerprintProbeTest} (its 18 cases with the
 * kit's default mapper, each run live, crashed and recovered).
 */
class ActivityResultReadBackTest {

    /** The kit's default mapper — {@link TestWorkflowEnvironment#create()} builds the same. */
    private static final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        Seen.clear();
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Booking.class, new BookingImpl());
        env.registerActivities(Probes.class, new ProbesImpl());
        env.registerActivities(Sink.class, new SinkImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    // ── The repro, live only ─────────────────────────────────────────────

    @Test
    void theLiveRunSeesTheStoredFormOfAnOffsetDateTime() throws Exception {
        var handle = env.startWorkflow(SlotWorkflow.class, "order-0");
        assertEquals("confirmed slot-7 at 2026-11-12T08:00Z done",
                handle.getResult(String.class, Duration.ofSeconds(10)));
    }

    // ── The two repros (AuditB38ReplayFingerprintTest) ──────────────────

    @Test
    void offsetDateTimeResult_survivesRecovery() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(SlotWorkflow.class, "order-1");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        Object status;
        try {
            status = handle.awaitTerminal(Duration.ofSeconds(5));
        } catch (TimeoutException e) {
            status = handle.getStatus() + " (not terminal after 5s)";
        }
        assertEquals(WorkflowStatus.COMPLETED, status, "standDowns=" + handle.standDowns());
    }

    @Test
    void sortedSetResult_survivesRecovery() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(TagsWorkflow.class, "order-2");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        Object status;
        try {
            status = handle.awaitTerminal(Duration.ofSeconds(5));
        } catch (TimeoutException e) {
            status = handle.getStatus() + " (not terminal after 5s)";
        }
        assertEquals(WorkflowStatus.COMPLETED, status, "standDowns=" + handle.standDowns());
    }

    // ── The probe (AuditB38FingerprintProbeTest), live versus replayed ──

    @ParameterizedTest
    @EnumSource(ProbeCase.class)
    void liveValueIsTheReplayedValue(ProbeCase c) throws Exception {
        var id = "probe-" + c.name();
        // Two completions: the probe's result and the sink that took it as
        // its argument. The node dies after the sink's event, so recovery
        // replays both — the probe's value is seen once live, once replayed.
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(id, ProbeWorkflow.class, c.name());
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        var status = settle(handle);

        var seen = Seen.of(id);                                     // [live value, replayed value]
        assertEquals(2, seen.size(), "seen=" + seen + " status=" + status);
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{seen.get(1)}),
                ArgumentFingerprint.of(serializer, new Object[]{seen.get(0)}), c.name());
        assertEquals(seen.get(1) == null ? null : seen.get(1).getClass(),
                seen.get(0) == null ? null : seen.get(0).getClass(), c.name());
        assertEquals(c.comparable(seen.get(1)), c.comparable(seen.get(0)), c.name());
        assertEquals("COMPLETED", status, c.name());
    }

    /**
     * The terminal status, or the active status with the run's stand-downs
     * once one has been reported — whichever comes first, within ten seconds.
     */
    private static String settle(TestWorkflowHandle handle) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var status = handle.getStatus();
            if (status.isTerminal()) {
                return status.name();
            }
            if (!handle.standDowns().isEmpty()) {
                return status + " (stood down: " + handle.standDowns() + ")";
            }
            Thread.sleep(20);
        }
        return handle.getStatus() + " (neither terminal nor stood down after 10s)";
    }

    // ── Fixtures: the repro ──────────────────────────────────────────────

    public record DeliverySlot(String id, OffsetDateTime start) { }

    public record Allowed(Set<String> skus) { }

    @Activity
    public interface Booking {
        DeliverySlot reserveSlot(String orderId);

        String confirm(DeliverySlot slot);

        Allowed allowedSkus(String orderId);

        String pick(Allowed allowed);

        String finish(String x);
    }

    public static final class BookingImpl implements Booking {
        @Override
        public DeliverySlot reserveSlot(String orderId) {
            // e.g. a courier API answering in the warehouse's local offset
            return new DeliverySlot("slot-7", OffsetDateTime.parse("2026-11-12T10:00:00+02:00"));
        }

        @Override
        public String confirm(DeliverySlot slot) {
            return "confirmed " + slot.id() + " at " + slot.start();
        }

        @Override
        public Allowed allowedSkus(String orderId) {
            return new Allowed(new TreeSet<>(List.of("sku-delta", "sku-alpha", "sku-charlie", "sku-bravo")));
        }

        @Override
        public String pick(Allowed allowed) {
            return "picked " + allowed.skus().size();
        }

        @Override
        public String finish(String x) {
            return x + " done";
        }
    }

    @DurableWorkflow(name = "slot-workflow")
    public static class SlotWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Booking booking;

        @WorkflowMethod
        public String run(String orderId) {
            var slot = booking.reserveSlot(orderId);
            var confirmation = booking.confirm(slot);
            return booking.finish(confirmation);
        }
    }

    @DurableWorkflow(name = "tags-workflow")
    public static class TagsWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Booking booking;

        @WorkflowMethod
        public String run(String orderId) {
            var allowed = booking.allowedSkus(orderId);
            var picked = booking.pick(allowed);
            return booking.finish(picked);
        }
    }

    // ── Fixtures: the probe ──────────────────────────────────────────────

    public record Tags(Set<String> tags) { }
    public record Slot(OffsetDateTime at) { }
    public record ZSlot(ZonedDateTime at) { }
    public record Quote(BigDecimal amount) { }
    public record At(Instant at) { }
    public static class Base { public String a = "a"; }
    public static class Sub extends Base { public String b = "b"; }

    /** One typed method per probe case: the declared return type is what replay reads back as. */
    @Activity
    public interface Probes {
        Tags setOfRecord();
        Tags treeSetRecord();
        Tags linkedHashSetRecord();
        Set<String> treeSetTopLevel();
        Slot offsetPlusTwo();
        ZSlot zonedBerlin();
        Slot offsetUtc();
        Quote decimalTwoPlaces();
        Quote decimalEighteenPlaces();
        Quote decimalTwentyDigits();
        At instantNanos();
        Base baseSub();
        Map<String, Object> mapLongFive();
        Map<String, Object> mapLongTwoPowSixty();
        Map<String, Object> mapFloat();
        Map<String, Object> mapDecimalTwoPlaces();
        Map<String, Object> mapDecimalEighteenPlaces();
        List<Object> listFloat();
    }

    public static final class ProbesImpl implements Probes {
        @Override public Tags setOfRecord() { return new Tags(Set.of("delta", "alpha", "charlie", "bravo", "echo")); }
        @Override public Tags treeSetRecord() {
            return new Tags(new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo")));
        }
        @Override public Tags linkedHashSetRecord() {
            return new Tags(new LinkedHashSet<>(List.of("zeta", "alpha", "mid")));
        }
        @Override public Set<String> treeSetTopLevel() {
            return new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo"));
        }
        @Override public Slot offsetPlusTwo() { return new Slot(OffsetDateTime.parse("2026-11-12T10:00:00+02:00")); }
        @Override public ZSlot zonedBerlin() {
            return new ZSlot(ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneId.of("Europe/Berlin")));
        }
        @Override public Slot offsetUtc() { return new Slot(OffsetDateTime.parse("2026-11-12T10:00:00Z")); }
        @Override public Quote decimalTwoPlaces() { return new Quote(new BigDecimal("10.50")); }
        @Override public Quote decimalEighteenPlaces() { return new Quote(new BigDecimal("1.123456789012345678")); }
        @Override public Quote decimalTwentyDigits() { return new Quote(new BigDecimal("12345678901234567.89")); }
        @Override public At instantNanos() { return new At(Instant.parse("2026-11-12T10:00:00.123456789Z")); }
        @Override public Base baseSub() { return new Sub(); }
        @Override public Map<String, Object> mapLongFive() { return Map.of("n", 5L); }
        @Override public Map<String, Object> mapLongTwoPowSixty() { return Map.of("n", 1L << 60); }
        @Override public Map<String, Object> mapFloat() { return Map.of("f", 0.1f); }
        @Override public Map<String, Object> mapDecimalTwoPlaces() { return Map.of("d", new BigDecimal("10.50")); }
        @Override public Map<String, Object> mapDecimalEighteenPlaces() {
            return Map.of("d", new BigDecimal("1.123456789012345678"));
        }
        @Override public List<Object> listFloat() { return List.of(0.1f); }
    }

    /** The probe's 18 cases (default mapper), each bound to its typed method. */
    enum ProbeCase {
        SET_OF_RECORD(Probes::setOfRecord, ProbeCase::tagsInOrder),
        TREESET_RECORD(Probes::treeSetRecord, ProbeCase::tagsInOrder),
        LINKEDHASHSET_RECORD(Probes::linkedHashSetRecord, ProbeCase::tagsInOrder),
        TREESET_TOP_LEVEL(Probes::treeSetTopLevel, ProbeCase::setInOrder),
        OFFSET_PLUS_TWO(Probes::offsetPlusTwo, v -> v),
        ZONED_BERLIN(Probes::zonedBerlin, v -> v),
        OFFSET_UTC(Probes::offsetUtc, v -> v),
        DECIMAL_2DP(Probes::decimalTwoPlaces, v -> v),
        DECIMAL_18DP(Probes::decimalEighteenPlaces, v -> v),
        DECIMAL_20_DIGITS(Probes::decimalTwentyDigits, v -> v),
        INSTANT_NANOS(Probes::instantNanos, v -> v),
        BASE_SUB(Probes::baseSub, ProbeCase::baseFields),
        MAP_LONG_5(Probes::mapLongFive, v -> v),
        MAP_LONG_2_60(Probes::mapLongTwoPowSixty, v -> v),
        MAP_FLOAT(Probes::mapFloat, v -> v),
        MAP_DECIMAL_2DP(Probes::mapDecimalTwoPlaces, v -> v),
        MAP_DECIMAL_18DP(Probes::mapDecimalEighteenPlaces, v -> v),
        LIST_FLOAT(Probes::listFloat, v -> v);

        private final Function<Probes, Object> call;
        private final Function<Object, Object> comparable;

        ProbeCase(Function<Probes, Object> call, Function<Object, Object> comparable) {
            this.call = call;
            this.comparable = comparable;
        }

        Object call(Probes probes) {
            return call.apply(probes);
        }

        /** What "equal" means for this case: a {@code Set} compares in iteration order. */
        @Nullable Object comparable(@Nullable Object value) {
            return value == null ? null : comparable.apply(value);
        }

        private static Object tagsInOrder(Object value) {
            return List.copyOf(((Tags) value).tags());
        }

        private static Object setInOrder(Object value) {
            return List.copyOf((Set<?>) value);
        }

        /** {@code Base} has no {@code equals}: its class and public fields, as {@code Sub} would add one. */
        private static Object baseFields(Object value) {
            var fields = new ArrayList<Object>();
            fields.add(value.getClass().getSimpleName());
            fields.add(((Base) value).a);
            if (value instanceof Sub sub) {
                fields.add(sub.b);
            }
            return fields;
        }
    }

    @Activity
    public interface Sink {
        String take(Object value);
    }

    public static final class SinkImpl implements Sink {
        @Override
        public String take(Object value) {
            return "took";
        }
    }

    @DurableWorkflow(name = "probe-workflow")
    public static class ProbeWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Probes probes;

        @ActivityStub(startToCloseTimeout = "PT10S")
        private Sink sink;

        @WorkflowMethod
        public String run(String caseName) {
            var v = ProbeCase.valueOf(caseName).call(probes);
            Seen.record(WorkflowContext.current().workflowId(), v);
            return sink.take(v);
        }
    }

    /** What workflow code saw, per workflow id, in arrival order (live run first, then each replay). */
    static final class Seen {
        private static final ConcurrentHashMap<String, List<@Nullable Object>> SEEN = new ConcurrentHashMap<>();

        static void record(String workflowId, @Nullable Object value) {
            SEEN.computeIfAbsent(workflowId, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        static List<@Nullable Object> of(String workflowId) {
            var values = SEEN.get(workflowId);
            return values == null ? List.of() : new ArrayList<>(values);
        }

        static void clear() {
            SEEN.clear();
        }
    }
}
