package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A live activity call returns the read-back of the payload it persisted —
 * the value a replay of the call returns (WP1 D7) — and what the engine
 * writes does not change (D-3). A store fault on the outcome append keeps
 * today's handling and never reaches the read-back.
 *
 * <p>Every assertion is on a collected value: the instance row, the event
 * log, the observer's lists, invocation counters.
 */
class LiveResultReadBackTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    /** The store, with fault injection; {@link #guarded} is what the engine is given. */
    private FaultyStore store;
    private WorkflowStore guarded;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new FaultyStore();
        guarded = StoreFaultGuard.wrap(store, List.of(Duration.ofMillis(1), Duration.ofMillis(1)));
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(guarded, serializer, "svc").observer(observer).build();
    }

    @AfterEach
    void tearDown() {
        store.heal();
        executor.shutdown();
    }

    // ── @Compensate gets the read-back ────────────────────────────────

    @Test
    void compensationReceivesTheReadBackResult() throws Exception {
        var impl = new SlotActs();
        var workflow = new ReserveThenFail(proxy(Acts.class, impl));
        var id = "rb-compensate";

        start(id, workflow, ReserveThenFail.class.getMethod("run", String.class));

        assertEquals(WorkflowStatus.FAILED, awaitStatus(id));
        assertEquals(ZoneOffset.UTC, impl.released.get().start().getOffset());
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{impl.released.get()}),
                argsHashOf(id, "Acts.release"));
    }

    // ── A store fault on the outcome append never reaches the read-back ──

    @Test
    void outcomeAppendOutage_latchesAndNeverReadsBack() throws Exception {
        store.failWhen("appendEvent", args -> ((WorkflowEvent) args[0]).eventType() == EventType.ACTIVITY_COMPLETED);
        var impl = new SlotActs();
        var workflow = new SinkOnce(proxy(Acts.class, impl));
        var id = "rb-outage";

        start(id, workflow, SinkOnce.class.getMethod("run", String.class));
        await().atMost(BOUND).until(() -> !executor.isRunning(id));

        assertEquals(AbandonReason.STORE_UNAVAILABLE, onlyAbandonCall(observer, id).reason());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance(id).orElseThrow().status());
        assertEquals(1, impl.calls.get());
    }

    // ── What is written is unchanged (D-3) ────────────────────────────

    static Stream<Arguments> stableAndUnstableResults() {
        return Stream.of(
                // Round-trip stable: the downstream hash is the one cbf5a2a wrote.
                Arguments.of("record of Strings", ProducePair.class, new Pair("left", "right"), true),
                Arguments.of("List<String>", ProduceList.class, List.of("b", "a", "c"), true),
                Arguments.of("Instant with nanos", ProduceInstant.class,
                        Instant.parse("2026-11-12T10:00:00.123456789Z"), true),
                Arguments.of("BigDecimal 10.50", ProduceDecimal.class, new BigDecimal("10.50"), true),
                Arguments.of("OffsetDateTime UTC", ProduceOffset.class,
                        OffsetDateTime.parse("2026-11-12T10:00:00Z"), true),
                // Not round-trip stable: the downstream hash is what replay computes.
                Arguments.of("OffsetDateTime +02:00", ProduceOffset.class,
                        OffsetDateTime.parse("2026-11-12T10:00:00+02:00"), false),
                Arguments.of("TreeSet record", ProduceTags.class,
                        new Tags(new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo"))), false),
                Arguments.of("Sub declared as Base", ProduceBase.class, new Sub(), false));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("stableAndUnstableResults")
    void liveReadBackLeavesPayloadsAsBefore(String label, Class<? extends Produces> producer,
                                            Object raw, boolean roundTripStable) throws Exception {
        // produce() returns raw; the workflow passes the value to sink(Object); both live
        var produce = producer.getMethod("produce");
        Type declared = produce.getGenericReturnType();
        var proxy = producerProxy(producer, raw);
        var workflow = new ProduceThenSink(proxy, produce);
        var id = "rb-written-" + label.replaceAll("[^A-Za-z0-9]+", "-");

        start(id, workflow, ProduceThenSink.class.getMethod("run", String.class));
        assertEquals(WorkflowStatus.COMPLETED, awaitStatus(id), label);

        assertEquals(serializer.serialize(raw), payloadOf(id, "Acts.produce"), label);   // payload exactly as before
        if (roundTripStable) {
            assertEquals(ArgumentFingerprint.of(serializer, new Object[]{raw}),
                    argsHashOf(id, "Acts.sink"), label);                                  // what cbf5a2a wrote
        } else {
            assertEquals(ArgumentFingerprint.of(serializer,
                            new Object[]{serializer.deserialize(serializer.serialize(raw), declared)}),
                    argsHashOf(id, "Acts.sink"), label);                                  // what replay computes
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private <T> T proxy(Class<T> iface, T impl) {
        return new ActivityProxyFactory().createProxy(iface, impl, guarded, null, RetryPolicy.noRetry(),
                Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer, TaskQueueGovernor.UNBOUNDED,
                LifecycleEventSink.NOOP, true);
    }

    /** A {@code Produces*} proxy whose {@code produce()} returns {@code raw}. */
    private <T extends Produces> T producerProxy(Class<T> producer, Object raw) {
        var impl = java.lang.reflect.Proxy.newProxyInstance(producer.getClassLoader(), new Class<?>[]{producer},
                (p, m, a) -> switch (m.getName()) {
                    case "produce" -> raw;
                    case "sink" -> "sunk";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return proxy(producer, producer.cast(impl));
    }

    private UUID start(String id, Object workflow, Method method) {
        return executor.startWorkflow(id, workflow.getClass().getSimpleName(), "default", "in",
                () -> workflow, method);
    }

    private WorkflowStatus awaitStatus(String id) {
        await().atMost(BOUND).until(() -> !executor.isRunning(id));
        return store.getInstance(id).orElseThrow().status();
    }

    private WorkflowEvent completedEventOf(String id, String step) {
        var instanceId = store.getInstance(id).orElseThrow().id();
        var matches = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED && step.equals(e.stepName()))
                .toList();
        assertEquals(1, matches.size(), "ACTIVITY_COMPLETED events of " + step);
        return matches.getFirst();
    }

    private @Nullable String argsHashOf(String id, String step) {
        return completedEventOf(id, step).argsHash();
    }

    private @Nullable JsonNode payloadOf(String id, String step) {
        return completedEventOf(id, step).payload();
    }

    private static RecordingEngineObserver.AbandonCall onlyAbandonCall(RecordingEngineObserver observer, String id) {
        var calls = observer.abandoned().stream().filter(a -> a.info().workflowId().equals(id)).toList();
        assertEquals(1, calls.size(), "abandon calls for " + id + ": " + calls);
        return calls.getFirst();
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    public record Slot(String id, OffsetDateTime start) {}

    public record Pair(String left, String right) {}

    public record Tags(Set<String> tags) {}

    public static class Base { public String a = "a"; }

    public static class Sub extends Base { public String b = "b"; }

    public interface Acts {
        @Compensate("release")
        Slot reserve();

        void release(Slot slot);

        void fail();

        String sink(Object value);
    }

    public static final class SlotActs implements Acts {
        final AtomicReference<Slot> released = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Slot reserve() {
            calls.incrementAndGet();
            return new Slot("slot-7", OffsetDateTime.parse("2026-11-12T10:00:00+02:00"));
        }

        @Override
        public void release(Slot slot) {
            released.set(slot);
        }

        @Override
        public void fail() {
            throw new IllegalStateException("boom");
        }

        @Override
        public String sink(Object value) {
            calls.incrementAndGet();
            return "sunk";
        }
    }

    /** A producer: {@code produce()} declares the row's type; every producer is the activity "Acts". */
    public interface Produces {
        String sink(Object value);
    }

    @Activity(name = "Acts")
    public interface ProducePair extends Produces { Pair produce(); }

    @Activity(name = "Acts")
    public interface ProduceList extends Produces { List<String> produce(); }

    @Activity(name = "Acts")
    public interface ProduceInstant extends Produces { Instant produce(); }

    @Activity(name = "Acts")
    public interface ProduceDecimal extends Produces { BigDecimal produce(); }

    @Activity(name = "Acts")
    public interface ProduceOffset extends Produces { OffsetDateTime produce(); }

    @Activity(name = "Acts")
    public interface ProduceTags extends Produces { Tags produce(); }

    @Activity(name = "Acts")
    public interface ProduceBase extends Produces { Base produce(); }

    public static class ReserveThenFail {
        private final Acts acts;

        public ReserveThenFail(Acts acts) {
            this.acts = acts;
        }

        @Saga
        public String run(String in) {
            acts.reserve();
            acts.fail();
            return "unreachable";
        }
    }

    public static class SinkOnce {
        private final Acts acts;

        public SinkOnce(Acts acts) {
            this.acts = acts;
        }

        public String run(String in) {
            return acts.sink(in);
        }
    }

    /** Calls the row's typed {@code produce()} through the proxy, then {@code sink(value)}. */
    public static class ProduceThenSink {
        private final Produces acts;
        private final Method produce;

        public ProduceThenSink(Produces acts, Method produce) {
            this.acts = acts;
            this.produce = produce;
        }

        public String run(String in) throws Throwable {
            Object value;
            try {
                value = produce.invoke(acts);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            return acts.sink(value);
        }
    }
}
