package io.b2mash.maestro.core.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
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
import org.slf4j.event.Level;
import tools.jackson.databind.DatabindException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── D9: a result that cannot be read back never fails the run ─────

    @Test
    void unreadableResult_completesLiveWithTheRawValueAndIsReported() throws Exception {
        // Decider.decideBare() declares BareDecision (sealed interface, no type info) and returns BareApproved("bob");
        // the workflow returns decision.getClass().getSimpleName()
        var workflow = new DecideBare(proxy(Decider.class, new BareDecider()));
        var id = "rb-unreadable";

        start(id, workflow, DecideBare.class.getMethod("run", String.class), "bob");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus(id));
        assertEquals("BareApproved", result(id));                      // the raw object reached workflow code
        assertEquals(1, observer.unreadableResults().size());
        assertEquals("Decider.decideBare", observer.unreadableResults().getFirst().info().activityName());
        assertEquals("tools.jackson.databind.exc.InvalidDefinitionException",
                observer.unreadableResults().getFirst().exceptionType());
        var names = observer.callbackNames();
        assertTrue(names.indexOf("activityResultUnreadable") < names.indexOf("activityCompleted"), names.toString());
        // The outcome is durable as written, and nothing recorded a failure.
        assertEquals(serializer.serialize(new BareApproved("bob")), payloadOf(id, "Decider.decideBare"));
        assertEquals(List.of(), observer.failed());
        assertEquals(List.of(), observer.activityFailed());
    }

    @Test
    void unreadableResultWarning_namesStepTypeAndCauseClass_neverAPayloadValue() {
        var cause = jacksonFailureQuoting("4111-1111-1111-1111");   // a real DatabindException whose message quotes the value
        var line = ActivityInvocationHandler.unreadableResultWarning("wf-1", "Pay.card", 3, BareDecision.class, cause);
        assertTrue(line.contains("wf-1") && line.contains("Pay.card") && line.contains("sequence 3")
                && line.contains(BareDecision.class.getTypeName()) && line.contains(cause.getClass().getName()), line);
        assertFalse(line.contains("4111"), line);
    }

    @Test
    void unreadableResult_warnsOncePerMethodAndNeverLogsThePayload() throws Exception {
        try (var logs = CapturingSlf4jProvider.watch(ActivityInvocationHandler.class)) {
            var workflow = new IssueTwice(proxy(Cards.class, new MaskedCards()));
            var id = "rb-unreadable-log";

            start(id, workflow, IssueTwice.class.getMethod("run", String.class));
            assertEquals(WorkflowStatus.COMPLETED, awaitStatus(id));

            // Positive controls: the marker is in the stored payload, and
            // Jackson's own message for this failure quotes it.
            var instanceId = store.getInstance(id).orElseThrow().id();
            var issued = store.getEvents(instanceId).stream()
                    .filter(e -> "Cards.issue".equals(e.stepName())).toList();
            assertEquals(2, issued.size());
            assertTrue(issued.getFirst().payload().toString().contains(CARD_MARKER), issued.toString());
            var jackson = assertThrows(SerializationException.class,
                    () -> serializer.deserialize(issued.getFirst().payload(), Card.class));
            assertTrue(jackson.getCause().getMessage().contains(CARD_MARKER), jackson.getCause().getMessage());

            var lines = logs.records().stream().filter(r -> r.message().contains(id)).toList();
            assertEquals(List.of(Level.WARN, Level.DEBUG),
                    lines.stream().map(CapturingSlf4jProvider.CapturedLog::level).toList(), lines.toString());
            var warn = lines.getFirst().message();
            assertTrue(warn.contains("Cards.issue") && warn.contains("sequence 1")
                    && warn.contains(Card.class.getTypeName())
                    && warn.contains("tools.jackson.databind.exc.InvalidFormatException"), warn);
            for (var line : lines) {
                assertFalse(rendered(line).contains(CARD_MARKER), rendered(line));
                assertFalse(rendered(line).contains("4111"), rendered(line));
            }
        }
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
        // The faulted step's result cannot be read back: a read-back reached on
        // the fault path would fire activityResultUnreadable, not pass silently.
        var decider = new BareDecider();
        var workflow = new DecideBare(proxy(Decider.class, decider));
        var id = "rb-outage";

        start(id, workflow, DecideBare.class.getMethod("run", String.class), "bob");
        await().atMost(BOUND).until(() -> !executor.isRunning(id));

        assertEquals(AbandonReason.STORE_UNAVAILABLE, onlyAbandonCall(observer, id).reason());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance(id).orElseThrow().status());
        assertEquals(0, observer.unreadableResults().size());
        assertEquals(1, decider.calls.get());
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
        return start(id, workflow, method, "in");
    }

    private UUID start(String id, Object workflow, Method method, String input) {
        return executor.startWorkflow(id, workflow.getClass().getSimpleName(), "default", input,
                () -> workflow, method);
    }

    private @Nullable String result(String id) {
        return serializer.deserialize(store.getInstance(id).orElseThrow().output(), String.class);
    }

    /** A real Jackson failure whose own message quotes {@code value}. */
    private static DatabindException jacksonFailureQuoting(String value) {
        var failure = assertThrows(DatabindException.class, () -> JsonMapper.builder().build()
                .readValue("{\"digits\":\"" + value + "\"}", CardDigits.class));
        assertTrue(failure.getMessage().contains(value), failure.getMessage());
        return failure;
    }

    /** A captured line as an appender would print it: message, then the throwable chain's messages. */
    private static String rendered(CapturingSlf4jProvider.CapturedLog log) {
        var out = new StringBuilder(log.message());
        for (Throwable t = log.throwable(); t != null; t = t.getCause()) {
            out.append(" | ").append(t);
        }
        return out.toString();
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

    /** Serialisable, not deserialisable: a sealed interface without type information. */
    public sealed interface BareDecision permits BareApproved {}

    public record BareApproved(String by) implements BareDecision {}

    public interface Decider {
        BareDecision decideBare(String applicant);
    }

    public static final class BareDecider implements Decider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public BareDecision decideBare(String applicant) {
            calls.incrementAndGet();
            return new BareApproved(applicant);
        }
    }

    public static class DecideBare {
        private final Decider decider;

        public DecideBare(Decider decider) {
            this.decider = decider;
        }

        public String run(String applicant) {
            var decision = decider.decideBare(applicant);
            return decision.getClass().getSimpleName();
        }
    }

    public record CardDigits(long digits) {}

    static final String CARD_MARKER = "4111-1111-1111-1111";

    /**
     * Written as a string, read only from a number: Jackson's failure to read
     * it back quotes the stored value.
     */
    public static final class Card {
        private final String number;

        public Card(String number) {
            this.number = number;
        }

        @JsonProperty("number")
        public String number() {
            return number;
        }

        @JsonCreator
        public static Card fromDigits(@JsonProperty("number") long digits) {
            return new Card(Long.toString(digits));
        }
    }

    public interface Cards {
        Card issue();
    }

    public static final class MaskedCards implements Cards {
        @Override
        public Card issue() {
            return new Card(CARD_MARKER);
        }
    }

    /** Calls the same unreadable step twice: one WARN, then DEBUG. */
    public static class IssueTwice {
        private final Cards cards;

        public IssueTwice(Cards cards) {
            this.cards = cards;
        }

        public String run(String in) {
            return cards.issue().number() + "/" + cards.issue().number();
        }
    }

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

        @Override
        public Slot reserve() {
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
