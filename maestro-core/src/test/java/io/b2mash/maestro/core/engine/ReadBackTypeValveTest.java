package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-back's type valve (final fix wave C1) and its {@code Future} skip
 * (C2), end to end: what workflow code receives from a live activity call,
 * whether the run reported it, and what a replay of the call returns after
 * the node stops and another node adopts the run.
 *
 * <p>The rule: a live result whose read-back succeeds but is not an instance
 * of the class of the object the activity returned is handed to workflow code as
 * the activity's own object (a read-back of the same kind of container —
 * both a {@code List}, both a {@code Set}, both a {@code Queue}, both a
 * {@code Map} — is not a class difference); the observer's
 * {@link EngineObserver#activityResultUnreadable} fires with
 * {@link EngineObserver#READ_BACK_TYPE_MISMATCH}, and a {@code WARN} names
 * the two classes. A replay still reads the stored payload as the declared
 * type, so for every valved row the live and replayed values differ. A method
 * declared to return a {@code Future} or {@code CompletionStage} is never read
 * back live: the activity's own object, no callback.
 *
 * <p>Every assertion is on a collected value: what the workflow saw (live,
 * then replayed), the observers' lists, the instance row, the log records.
 */
class ReadBackTypeValveTest {

    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final String SERVICE = "valve-svc";
    private static final String SECRET = "SECRET-7f3a";

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observerA;
    private RecordingEngineObserver observerB;
    private @Nullable WorkflowExecutor nodeA;
    private @Nullable WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        Seen.clear();
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observerA = new RecordingEngineObserver();
        observerB = new RecordingEngineObserver();
        nodeA = WorkflowExecutor.builder(store, serializer, SERVICE).observer(observerA).build();
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.shutdown();
        if (nodeB != null) nodeB.shutdown();
    }

    // ── The edge-case matrix ─────────────────────────────────────────────

    static Stream<Arguments> shapes() {
        return Stream.of(
                // method,                live (what workflow code got),        reported, replayed / outcome
                row("primitiveLong",      "Long 5",                              false, "Long 5"),
                row("boxedLong",          "Long 5",                              false, "Long 5"),
                row("numberLong",         "Long 5",                              true,  "Integer 5"),
                row("objectLong",         "Long 5",                              true,  "Integer 5"),
                row("objectInteger",      "Integer 5",                           false, "Integer 5"),
                row("objectDecimal",      "BigDecimal 10.50",                    true,  "Double 10.5"),
                row("stringArray",        "String[] [a, b]",                     false, "String[] [a, b]"),
                row("objectArray",        "Object[] [a, b]",                     false, "Object[] [a, b]"),
                row("stringArrayAsObject", "String[] [a, b]",                    true,  "ArrayList [a, b]"),
                row("color",              "Color.RED",                           false, "Color.RED"),
                row("shade",              "Shade.DARK (constant body)",          false, "Shade.DARK (constant body)"),
                row("pair",               "Pair Pair[left=l, right=r]",          false, "Pair Pair[left=l, right=r]"),
                row("optional",           "Optional Optional[x]",                false, "Optional Optional[x]"),
                row("optionalNull",       "Optional Optional.empty",             false, "Optional Optional.empty"),
                row("objectNode",         "ObjectNode {\"a\":1}",                false, "ObjectNode {\"a\":1}"),
                row("longNode",           "LongNode 5",                          true,  "IntNode 5"),
                row("nullNode",           "null",                                false, "null"),
                row("immutableList",      "ArrayList [a, b]",                    false, "ArrayList [a, b]"),
                row("treeMap",            "LinkedHashMap {a=1, b=2}",            false, "LinkedHashMap {a=1, b=2}"),
                row("immutableListAsObject", "ArrayList [a, b]",                 false, "ArrayList [a, b]"),
                // Same kind of container only (follow-up): a Set read back as a List is valved.
                row("setAsObject",        "TreeSet [a, b]",                      true,  "ArrayList [a, b]"),
                row("setAsCollection",    "TreeSet [a, b]",                      true,  "ArrayList [a, b]"),
                row("treeSetAsSet",       "HashSet [a, b]",                      false, "HashSet [a, b]"),
                row("treeSetAsSortedSet", "TreeSet [a, b]",                      false, "TreeSet [a, b]"),
                row("arrayDequeAsQueue",  "LinkedList [a, b]",                   false, "LinkedList [a, b]"),
                row("untypedMap",         "LinkedHashMap {n=Integer 5}",         false, "LinkedHashMap {n=Integer 5}"),
                row("pojoAsObject",       "Pair Pair[left=l, right=r]",          true,  "LinkedHashMap {left=l, right=r}"),
                row("typeVariable",       "Pair Pair[left=l, right=r]",          true,  "LinkedHashMap {left=l, right=r}"),
                row("subtype",            "Sub a=a b=b",                         true,  "Base a=a"),
                row("exception",          "IllegalStateException boom",          true,  "RuntimeException boom"),
                row("completableFuture",  "CompletableFuture done=true value=v", false, "CompletableFuture done=false"),
                row("future",             "CompletableFuture done=true value=v", false, "stood down: UNKNOWN_EVENT_PAYLOAD"),
                row("stage",              "CompletableFuture done=true value=v", false, "stood down: UNKNOWN_EVENT_PAYLOAD"));
    }

    private static Arguments row(String method, String live, boolean reported, String replayed) {
        return Arguments.of(method, live, reported, replayed);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("shapes")
    @Timeout(30)
    void liveValue_report_andReplayedValue(String method, String live, boolean reported, String replayed)
            throws Exception {
        var id = "valve-" + method;
        var run = ShapeWorkflow.class.getMethod("run", String.class);
        var a = nodeA;
        a.startWorkflow(id, "ShapeWorkflow", "default", method, () -> new ShapeWorkflow(shapes(observerA)), run);
        await().atMost(BOUND).until(() -> Seen.of(id).size() == 1
                && store.getInstance(id).map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

        // Node A stops while the run is parked; node B adopts it and replays the call.
        a.shutdown();
        nodeA = null;
        var b = WorkflowExecutor.builder(store, serializer, SERVICE).observer(observerB).build();
        nodeB = b;
        b.recoverWorkflows(Map.of("ShapeWorkflow", new WorkflowRegistration(
                "ShapeWorkflow", "default", () -> new ShapeWorkflow(shapes(observerB)), run)));
        await().atMost(BOUND).until(() -> Seen.of(id).size() == 2 || !observerB.standDowns().isEmpty());

        String outcome;
        if (observerB.standDowns().isEmpty()) {
            b.deliverSignal(id, "go", "go");
            await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));
            outcome = store.getInstance(id).orElseThrow().status().name();
        } else {
            outcome = "stood down: " + observerB.standDownReasons(id).getFirst();
        }

        var seen = Seen.of(id);
        assertAll(method,
                () -> assertEquals(live, seen.getFirst(), "live"),
                () -> assertEquals(reported ? List.of(EngineObserver.READ_BACK_TYPE_MISMATCH) : List.of(),
                        observerA.unreadableResults().stream()
                                .map(RecordingEngineObserver.UnreadableResult::exceptionType).toList(),
                        "reported live"),
                () -> assertEquals(replayed.startsWith("stood down") ? replayed : "COMPLETED", outcome, "outcome"),
                () -> assertEquals(replayed.startsWith("stood down") ? 1 : 2, seen.size(), "seen " + seen),
                () -> {
                    if (seen.size() == 2) {
                        assertEquals(replayed, seen.get(1), "replayed");
                    }
                },
                () -> assertEquals(List.of(), observerB.unreadableResults(), "never reported on replay"),
                () -> assertEquals(List.of(), observerA.failed(), "nothing failed"));
    }

    // ── The predicate, including the cases no activity here produces ────

    static Stream<Arguments> classPairs() {
        return Stream.of(
                Arguments.of(new ArrayList<>(), new LinkedHashMap<>(), true),      // a Collection read back as a Map
                Arguments.of(new HashMap<>(), new ArrayList<>(), true),            // a Map read back as a Collection
                Arguments.of(List.of("a"), new ArrayList<>(List.of("a")), false),  // both List
                Arguments.of(new LinkedList<>(), new ArrayList<>(), false),        // both List
                Arguments.of(new TreeSet<>(), new ArrayList<>(), true),            // a Set read back as a List
                Arguments.of(new HashSet<>(), new ArrayList<>(), true),            // a Set read back as a List
                Arguments.of(new ArrayList<>(), new HashSet<>(), true),            // a List read back as a Set
                Arguments.of(new TreeSet<>(), new HashSet<>(), false),             // both Set
                Arguments.of(new ArrayDeque<>(), new LinkedList<>(), false),       // both Queue
                Arguments.of(new TreeMap<>(), new LinkedHashMap<>(), false),       // both Map
                Arguments.of(5L, 5, true),
                Arguments.of(5L, 5L, false),
                Arguments.of(new Sub(), new Base(), true),
                Arguments.of(new Base(), new Sub(), false),                        // a subclass of raw's class
                Arguments.of(new String[]{"a"}, new ArrayList<>(List.of("a")), true),
                Arguments.of(LongNode.valueOf(5), JsonNodeFactory.instance.numberNode(5), true));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}: {2}")
    @MethodSource("classPairs")
    void readsBackAsAnotherClass(Object raw, Object readBack, boolean valve) {
        assertEquals(valve, ActivityInvocationHandler.readsBackAsAnotherClass(raw, readBack));
    }

    @Test
    void readsBackAsAnotherClass_isFalseWhenEitherSideIsNull() {
        assertAll(
                () -> assertFalse(ActivityInvocationHandler.readsBackAsAnotherClass(null, Optional.empty())),
                () -> assertFalse(ActivityInvocationHandler.readsBackAsAnotherClass(NullNode.instance, null)),
                () -> assertFalse(ActivityInvocationHandler.readsBackAsAnotherClass(null, null)));
    }

    // ── The WARN and the callback, on the first run ──────────────────────

    @Test
    @Timeout(30)
    void typeMismatch_warnsOncePerMethodWithClassNamesOnly_andIsReportedBeforeCompletion() throws Exception {
        try (var logs = CapturingSlf4jProvider.watch(ActivityInvocationHandler.class)) {
            var id = "valve-warn";
            var a = nodeA;
            a.startWorkflow(id, "SubtypeTwice", "default", "in",
                    () -> new SubtypeTwice(shapes(observerA)), SubtypeTwice.class.getMethod("run", String.class));
            await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));

            assertEquals(WorkflowStatus.COMPLETED, store.getInstance(id).orElseThrow().status());
            assertEquals("SecretSub/SecretSub", serializer.deserialize(store.getInstance(id).orElseThrow().output(), String.class));
            assertEquals(2, observerA.unreadableResults().size());
            var names = observerA.callbackNames();
            assertTrue(names.indexOf("activityResultUnreadable") < names.indexOf("activityCompleted"), names.toString());
            // Positive control: the payload carries the marker the log must not.
            assertTrue(store.getEvents(store.getInstance(id).orElseThrow().id()).stream()
                    .anyMatch(e -> String.valueOf(e.payload()).contains(SECRET)));

            var lines = logs.records().stream().filter(r -> r.message().contains(id)).toList();
            assertEquals(List.of(Level.WARN, Level.DEBUG),
                    lines.stream().map(CapturingSlf4jProvider.CapturedLog::level).toList(), lines.toString());
            var warn = lines.getFirst().message();
            assertEquals(ActivityInvocationHandler.typeMismatchWarning(id, "Shapes.secretSubtype", 1,
                    Base.class, Base.class, SecretSub.class), warn);
            assertTrue(warn.contains("reads back from the store as " + Base.class.getName()
                    + " rather than " + SecretSub.class.getName()), warn);
            assertFalse(warn.toLowerCase(java.util.Locale.ROOT).contains("stand"), warn);
            for (var line : lines) {
                assertFalse(line.message().contains(SECRET), line.message());
                assertEquals(null, line.throwable());
            }
        }
    }

    // ── Future and CompletionStage: returned as they are ─────────────────

    @Test
    @Timeout(30)
    void aFutureResultIsTheActivitysOwnObject_andJoinCompletesOnTheFirstRun() throws Exception {
        var id = "valve-join";
        var impl = new ShapesImpl();
        LAST_IMPL.set(impl);
        var a = nodeA;
        a.startWorkflow(id, "JoinWorkflow", "default", "in",
                () -> new JoinWorkflow(proxy(impl, observerA)), JoinWorkflow.class.getMethod("run", String.class));

        assertEquals(WorkflowStatus.COMPLETED, settle(id));
        assertEquals("joined v same=true", serializer.deserialize(
                store.getInstance(id).orElseThrow().output(), String.class));
        assertEquals(List.of(), observerA.unreadableResults());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** The instance's status once terminal, or as it stands after {@link #BOUND}. */
    private WorkflowStatus settle(String id) throws InterruptedException {
        var deadline = System.nanoTime() + BOUND.toNanos();
        while (System.nanoTime() < deadline) {
            var status = store.getInstance(id).orElseThrow().status();
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(20);
        }
        return store.getInstance(id).orElseThrow().status();
    }

    private Shapes shapes(EngineObserver observer) {
        return proxy(new ShapesImpl(), observer);
    }

    private Shapes proxy(ShapesImpl impl, EngineObserver observer) {
        return new ActivityProxyFactory().createProxy(Shapes.class, impl, store, null, RetryPolicy.noRetry(),
                Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer, TaskQueueGovernor.UNBOUNDED,
                LifecycleEventSink.NOOP, true);
    }

    /** A value as the matrix names it: class, then a rendering that shows what matters for the row. */
    static String describe(@Nullable Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof CompletableFuture<?> f) {
            return "CompletableFuture done=" + f.isDone() + (f.isDone() ? " value=" + f.join() : "");
        }
        if (v instanceof Object[] array) {
            return v.getClass().getSimpleName() + " " + Arrays.toString(array);
        }
        if (v instanceof Enum<?> e) {
            return e.getDeclaringClass().getSimpleName() + "." + e.name()
                    + (e.getClass() != e.getDeclaringClass() ? " (constant body)" : "");
        }
        if (v instanceof Throwable t) {
            return v.getClass().getSimpleName() + " " + t.getMessage();
        }
        if (v instanceof Map<?, ?> map && map.containsKey("n")) {
            var n = map.get("n");
            return v.getClass().getSimpleName() + " {n=" + n.getClass().getSimpleName() + " " + n + "}";
        }
        return v.getClass().getSimpleName() + " " + v;
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public record Pair(String left, String right) { }

    public static class Base {
        public String a = "a";

        @Override
        public String toString() {
            return "a=" + a;
        }
    }

    public static class Sub extends Base {
        public String b = "b";

        @Override
        public String toString() {
            return super.toString() + " b=" + b;
        }
    }

    /** A subtype whose field holds a value the log must never show. */
    public static class SecretSub extends Base {
        public String secret = SECRET;
    }

    public enum Color { RED, GREEN }

    public enum Shade {
        DARK {
            @Override
            String hex() {
                return "#000";
            }
        };

        abstract String hex();
    }

    public interface Shapes {
        long primitiveLong();
        Long boxedLong();
        Number numberLong();
        Object objectLong();
        Object objectInteger();
        Object objectDecimal();
        String[] stringArray();
        Object[] objectArray();
        Object stringArrayAsObject();
        Color color();
        Shade shade();
        Pair pair();
        Optional<String> optional();
        Optional<String> optionalNull();
        JsonNode objectNode();
        JsonNode longNode();
        JsonNode nullNode();
        List<String> immutableList();
        Object immutableListAsObject();
        Collection<String> setAsCollection();
        Set<String> treeSetAsSet();
        SortedSet<String> treeSetAsSortedSet();
        Queue<String> arrayDequeAsQueue();
        Map<String, Integer> treeMap();
        Object setAsObject();
        Map<String, Object> untypedMap();
        Object pojoAsObject();
        <T> T typeVariable();
        Base subtype();
        Base secretSubtype();
        RuntimeException exception();
        CompletableFuture<String> completableFuture();
        Future<String> future();
        CompletionStage<String> stage();
    }

    public static final class ShapesImpl implements Shapes {
        volatile @Nullable CompletableFuture<String> lastFuture;

        @Override public long primitiveLong() { return 5L; }
        @Override public Long boxedLong() { return 5L; }
        @Override public Number numberLong() { return 5L; }
        @Override public Object objectLong() { return 5L; }
        @Override public Object objectInteger() { return 5; }
        @Override public Object objectDecimal() { return new BigDecimal("10.50"); }
        @Override public String[] stringArray() { return new String[]{"a", "b"}; }
        @Override public Object[] objectArray() { return new Object[]{"a", "b"}; }
        @Override public Object stringArrayAsObject() { return new String[]{"a", "b"}; }
        @Override public Color color() { return Color.RED; }
        @Override public Shade shade() { return Shade.DARK; }
        @Override public Pair pair() { return new Pair("l", "r"); }
        @Override public Optional<String> optional() { return Optional.of("x"); }
        @SuppressWarnings("NullAway")
        @Override public Optional<String> optionalNull() { return null; }
        @Override public JsonNode objectNode() { return JsonNodeFactory.instance.objectNode().put("a", 1); }
        @Override public JsonNode longNode() { return LongNode.valueOf(5); }
        @Override public JsonNode nullNode() { return NullNode.instance; }
        @Override public List<String> immutableList() { return List.of("a", "b"); }
        @Override public Object immutableListAsObject() { return List.of("a", "b"); }
        @Override public Collection<String> setAsCollection() { return new TreeSet<>(List.of("b", "a")); }
        @Override public Set<String> treeSetAsSet() { return new TreeSet<>(List.of("b", "a")); }
        @Override public SortedSet<String> treeSetAsSortedSet() { return new TreeSet<>(List.of("b", "a")); }
        @Override public Queue<String> arrayDequeAsQueue() { return new ArrayDeque<>(List.of("a", "b")); }
        @Override public Map<String, Integer> treeMap() { return new TreeMap<>(Map.of("b", 2, "a", 1)); }
        @Override public Object setAsObject() { return new TreeSet<>(List.of("b", "a")); }
        @Override public Map<String, Object> untypedMap() { return Map.of("n", 5L); }
        @Override public Object pojoAsObject() { return new Pair("l", "r"); }
        @SuppressWarnings("unchecked")
        @Override public <T> T typeVariable() { return (T) new Pair("l", "r"); }
        @Override public Base subtype() { return new Sub(); }
        @Override public Base secretSubtype() { return new SecretSub(); }
        @Override public RuntimeException exception() { return new IllegalStateException("boom"); }

        @Override public CompletableFuture<String> completableFuture() {
            var f = CompletableFuture.completedFuture("v");
            lastFuture = f;
            return f;
        }

        @Override public Future<String> future() { return CompletableFuture.completedFuture("v"); }
        @Override public CompletionStage<String> stage() { return CompletableFuture.completedFuture("v"); }
    }

    /** Calls the named method, records what it got, parks on "go", then returns what it got. */
    public static class ShapeWorkflow {
        private final Shapes shapes;

        ShapeWorkflow(Shapes shapes) {
            this.shapes = shapes;
        }

        public String run(String method) throws Throwable {
            Object value;
            try {
                value = Shapes.class.getMethod(method).invoke(shapes);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            var described = describe(value);
            Seen.record(WorkflowContext.current().workflowId(), described);
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(5));
            return described;
        }
    }

    /** Calls the secret subtype twice: one WARN, then DEBUG. */
    public static class SubtypeTwice {
        private final Shapes shapes;

        SubtypeTwice(Shapes shapes) {
            this.shapes = shapes;
        }

        public String run(String in) {
            return shapes.secretSubtype().getClass().getSimpleName()
                    + "/" + shapes.secretSubtype().getClass().getSimpleName();
        }
    }

    /** Joins a {@code CompletableFuture} result on the first run. */
    public static class JoinWorkflow {
        private final Shapes shapes;

        JoinWorkflow(Shapes shapes) {
            this.shapes = shapes;
        }

        public String run(String in) {
            var future = shapes.completableFuture();
            var impl = LAST_IMPL.get();
            return "joined " + future.join() + " same=" + (impl != null && future == impl.lastFuture);
        }
    }

    /** The impl {@link #aFutureResultIsTheActivitysOwnObject_andJoinCompletesOnTheFirstRun} uses. */
    static final java.util.concurrent.atomic.AtomicReference<@Nullable ShapesImpl> LAST_IMPL =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** What workflow code saw, per workflow id, in arrival order (live run first, then each replay). */
    static final class Seen {
        private static final ConcurrentHashMap<String, List<String>> SEEN = new ConcurrentHashMap<>();

        static void record(String workflowId, String value) {
            SEEN.computeIfAbsent(workflowId, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        static List<String> of(String workflowId) {
            var values = SEEN.get(workflowId);
            return values == null ? List.of() : new ArrayList<>(values);
        }

        static void clear() {
            SEEN.clear();
        }
    }
}
