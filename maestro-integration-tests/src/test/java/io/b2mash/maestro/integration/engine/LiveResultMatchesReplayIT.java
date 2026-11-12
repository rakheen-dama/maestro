package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP1 Task 7 on real PostgreSQL: a live activity call returns what a replay of
 * that call returns <em>on this store</em>. {@code jsonb} reorders object keys
 * (shortest first) and rewrites exponent numbers ({@code 1.0E7} becomes
 * {@code 10000000}); before Task 7 the live read-back read the node the engine
 * sent, so a {@code Map} result's key order and an untyped exponent number
 * differed between the live run and a replay. Now the live read-back reads the
 * node the {@code INSERT … RETURNING payload} handed back.
 *
 * <p>Each row: node A runs the probe live, records the value in {@link Seen},
 * passes its {@code toString()} to a second activity, and parks; node A
 * closes. Node B recovers the run — replaying both activities, recording the
 * replayed value, and re-checking the second activity's argument fingerprint
 * — then the signal completes it. The live and replayed values must agree in
 * fingerprint, class and comparable form, and the run must not stand down.
 */
@Tag("integration")
@DisplayName("A live activity result equals its replay on Postgres")
class LiveResultMatchesReplayIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "live-result-replay";
    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @BeforeEach
    void clearSeen() {
        Seen.clear();
    }

    @ParameterizedTest
    @EnumSource(ProbeCase.class)
    void liveValueIsTheReplayedValue(ProbeCase c) throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("live-replay-" + c.name().toLowerCase());

        // ── Node A: the probe and the sink run live; the run parks on "go".
        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(parkedA, null)) {
            handle = nodeA.start(workflowId, ProbeWorkflow.class, c.name());
            assertTrue(parkedA.await(30, TimeUnit.SECONDS), "node A must reach the await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }

        // ── Node B: replays both activities (the sink's argument fingerprint
        // included), records the replayed value, parks, and the signal completes it.
        var observer = new RecordingObserver();
        var parkedB = new CountDownLatch(1);
        String outcome;
        try (var nodeB = node(parkedB, observer)) {
            assertEquals(1, nodeB.recover());
            var deadline = System.nanoTime() + BOUND.toNanos();
            while (parkedB.getCount() > 0 && observer.standDowns().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            if (!observer.standDowns().isEmpty()) {
                outcome = handle.status() + " (stood down: " + observer.standDowns() + ")";
            } else {
                nodeB.deliverSignal(workflowId, ProbeWorkflow.SIGNAL, "go");
                outcome = handle.awaitTerminal(BOUND).name();
            }
        }

        var seen = Seen.of(workflowId);                       // [live value, replayed value]
        assertEquals(2, seen.size(), "seen=" + seen + " outcome=" + outcome);
        var live = seen.get(0);
        var replayed = seen.get(1);
        assertAll(c.name(),
                () -> assertEquals("COMPLETED", outcome),
                () -> assertEquals(ArgumentFingerprint.of(serializer, new Object[]{replayed}),
                        ArgumentFingerprint.of(serializer, new Object[]{live}), "fingerprint"),
                () -> assertEquals(replayed == null ? null : replayed.getClass(),
                        live == null ? null : live.getClass(), "class"),
                () -> assertEquals(c.comparable(replayed), c.comparable(live), "comparable"));
    }

    private MaestroEngineHarness node(CountDownLatch parked, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(Probes.class, new ProbesImpl());
        node.registerActivities(Sink.class, new SinkImpl());
        node.registerWorkflow(ProbeWorkflow.class, () -> new ProbeWorkflow(parked));
        return node;
    }

    // ── fixture ───────────────────────────────────────────────────────────

    public record Tags(Set<String> tags) {}

    public record Slot(OffsetDateTime at) {}

    public record Quote(BigDecimal amount) {}

    /** One typed method per row: the declared return type is what replay reads back as. */
    @Activity
    public interface Probes {
        Map<String, Integer> mapKeyOrder();

        Map<String, Object> untypedExponent();

        Map<String, Object> mapFloat();

        Quote decimalNineteen();

        Quote decimalTenFifty();

        Slot offsetPlusTwo();

        Tags treeSetRecord();

        Optional<String> optionalEmpty();

        @Nullable String nullResult();
    }

    public static final class ProbesImpl implements Probes {
        @Override
        public Map<String, Integer> mapKeyOrder() {
            var map = new LinkedHashMap<String, Integer>();
            map.put("aa", 1);
            map.put("b", 2);
            return map;
        }

        @Override
        public Map<String, Object> untypedExponent() {
            return Map.of("x", 1.0E7);
        }

        @Override
        public Map<String, Object> mapFloat() {
            return Map.of("f", 0.1f);
        }

        @Override
        public Quote decimalNineteen() {
            return new Quote(new BigDecimal("1.123456789012345678"));
        }

        @Override
        public Quote decimalTenFifty() {
            return new Quote(new BigDecimal("10.50"));
        }

        @Override
        public Slot offsetPlusTwo() {
            return new Slot(OffsetDateTime.parse("2026-11-12T10:00:00+02:00"));
        }

        @Override
        public Tags treeSetRecord() {
            return new Tags(new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo")));
        }

        @Override
        public Optional<String> optionalEmpty() {
            return Optional.empty();
        }

        @Override
        public @Nullable String nullResult() {
            return null;
        }
    }

    /** The rows, each bound to its typed method and to what "equal" means for it. */
    enum ProbeCase {
        MAP_KEY_ORDER(Probes::mapKeyOrder, v -> List.copyOf(((Map<?, ?>) v).keySet())),
        UNTYPED_EXPONENT(Probes::untypedExponent, v -> classAndValue(((Map<?, ?>) v).get("x"))),
        MAP_FLOAT(Probes::mapFloat, v -> v),
        DECIMAL_19(Probes::decimalNineteen, v -> v),
        DECIMAL_10_50(Probes::decimalTenFifty, v -> v),
        OFFSET_PLUS_TWO(Probes::offsetPlusTwo, v -> v),
        TREESET_RECORD(Probes::treeSetRecord, v -> List.copyOf(((Tags) v).tags())),
        OPTIONAL_EMPTY(Probes::optionalEmpty, v -> v),
        NULL_RESULT(Probes::nullResult, v -> v);

        private final Function<Probes, @Nullable Object> call;
        private final Function<Object, Object> comparable;

        ProbeCase(Function<Probes, @Nullable Object> call, Function<Object, Object> comparable) {
            this.call = call;
            this.comparable = comparable;
        }

        @Nullable Object call(Probes probes) {
            return call.apply(probes);
        }

        @Nullable Object comparable(@Nullable Object value) {
            return value == null ? null : comparable.apply(value);
        }

        private static Object classAndValue(Object v) {
            return v.getClass().getSimpleName() + " " + v;
        }
    }

    @Activity
    public interface Sink {
        String take(String value);
    }

    public static final class SinkImpl implements Sink {
        @Override
        public String take(String value) {
            return "took";
        }
    }

    @DurableWorkflow(name = "LiveResultProbeWorkflow")
    public static class ProbeWorkflow {

        static final String SIGNAL = "go";

        @ActivityStub(startToCloseTimeout = "PT10S")
        Probes probes;

        @ActivityStub(startToCloseTimeout = "PT10S")
        Sink sink;

        private final CountDownLatch parked;

        public ProbeWorkflow(CountDownLatch parked) {
            this.parked = parked;
        }

        @WorkflowMethod
        public String run(String caseName) {
            var v = ProbeCase.valueOf(caseName).call(probes);
            Seen.record(WorkflowContext.current().workflowId(), v);
            // The text of the value — key order and number form included —
            // becomes the next activity's argument, so a live value that
            // differs from its replay changes the recorded fingerprint.
            sink.take(String.valueOf(v));
            parked.countDown();
            WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofSeconds(60));
            return "done";
        }
    }

    /** What workflow code saw, per workflow id, in arrival order (live run first, then the replay). */
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
