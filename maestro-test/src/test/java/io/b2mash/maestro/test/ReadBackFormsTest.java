package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.UnsupportedActivityReturnTypeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forms {@code docs/pitfalls.md} rule 12 names: what workflow code gets
 * from a live activity call is the stored result read back into the method's
 * declared return type — on the <em>first</em> run, not only after a restart —
 * and not the object the activity returned. Each test runs a workflow
 * straight through the kit (no crash) and asserts the value it saw, so each
 * would read differently if a live call returned the activity's own object.
 *
 * <p>One exception returns the activity's own object on the first run: a
 * read-back of another class than the object the activity returned (a
 * subtype declared as its supertype, a record declared as {@code Object}; the
 * type valve). A replay still reads the stored payload as the declared type.
 * A method declared to return a {@code Future} or {@code CompletionStage} is
 * refused before the workflow starts (Issue 49).
 *
 * <p>The kit's default mapper is Jackson 3's default, as the starter's is.
 * Map key order on Postgres ({@code jsonb}: shorter keys first) is not
 * shown here: the kit stores trees, not {@code jsonb}
 * ({@code LiveResultMatchesReplayIT} shows it on Postgres).
 */
class ReadBackFormsTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        Returned.LAST.set(null);
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Forms.class, new FormsImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void anOffsetOrZonedTimeArrivesInUtc() throws Exception {
        assertEquals("2026-11-12T08:00Z | 2026-11-12T09:00Z zone=Z",
                env.startWorkflow(TimesWorkflow.class, "x").getResult(String.class, BOUND));
    }

    @Test
    void aSetArrivesAsAHashSet_aSortedSetAsATreeSet() throws Exception {
        assertEquals("HashSet | TreeSet [alpha, bravo, charlie, delta, echo]",
                env.startWorkflow(SetsWorkflow.class, "x").getResult(String.class, BOUND));
    }

    @Test
    void untypedNumbersInAMapArriveAsJsonReadsThem() throws Exception {
        assertEquals("{decimal=Double 10.5, float=Double 0.1, long2pow60=Long 1152921504606846976, long5=Integer 5}",
                env.startWorkflow(NumbersWorkflow.class, "x").getResult(String.class, BOUND));
    }

    /** The type valve: the read-back is a {@code Base}, not a {@code Sub}, so the live run gets the {@code Sub}. */
    @Test
    void aSubtypeDeclaredAsItsSupertypeArrivesAsTheSubtype_onTheFirstRun() throws Exception {
        assertEquals("Sub a=a instanceof Sub=true",
                env.startWorkflow(SubtypeWorkflow.class, "x").getResult(String.class, BOUND));
    }

    /** The type valve: the read-back is a {@code LinkedHashMap}, so the live run gets the record. */
    @Test
    void aResultDeclaredAsObjectArrivesAsTheActivitysObject_onTheFirstRun() throws Exception {
        assertEquals("Receipt Receipt[id=r-1, amount=10.50]",
                env.startWorkflow(ObjectWorkflow.class, "x").getResult(String.class, BOUND));
    }

    @Test
    void aSortedMapArrivesAsATreeMapInKeyOrder() throws Exception {
        assertEquals("TreeMap [a, bb, c]",
                env.startWorkflow(SortedMapWorkflow.class, "x").getResult(String.class, BOUND));
    }

    @Test
    void theResultIsACopy_notTheActivitysObject() throws Exception {
        assertEquals("equal=true same=false",
                env.startWorkflow(CopyWorkflow.class, "x").getResult(String.class, BOUND));
    }

    /**
     * Issue 49: a {@code Future}, {@code CompletionStage} or
     * {@code CompletableFuture} result cannot survive a restart (its record is
     * the future's bean properties, never its value), so a workflow whose stub
     * declares one is refused at {@code startWorkflow}, naming the interface
     * and every such method, before anything is persisted.
     */
    @Test
    void aStubWithAFutureResult_isRefusedAtStart_andNothingIsPersisted() {
        env.registerActivities(AsyncForms.class, new AsyncFormsImpl());

        var refused = assertThrows(UnsupportedActivityReturnTypeException.class,
                () -> env.startWorkflow("future-refused", FutureWorkflow.class, "x"));
        assertEquals(AsyncForms.class.getName(), refused.activityInterface());
        assertEquals(List.of("completableFuture", "future", "stage"), refused.methods());
        assertTrue(env.getStore().getInstance("future-refused").isEmpty(), "nothing persisted");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public record Slot(OffsetDateTime at) { }

    public record ZSlot(ZonedDateTime at) { }

    public static class Base {
        public String a = "a";
    }

    public static class Sub extends Base {
        public String b = "b";
    }

    public record Receipt(String id, BigDecimal amount) { }

    /** What the activity last returned, so a workflow can compare identity. */
    static final class Returned {
        static final AtomicReference<Object> LAST = new AtomicReference<>();
    }

    @Activity
    public interface Forms {
        Slot offset();

        ZSlot zoned();

        Set<String> set();

        SortedSet<String> sortedSet();

        Map<String, Object> numbers();

        Base subtype();

        Object asObject();

        SortedMap<String, Integer> sortedMap();

        Receipt receipt();
    }

    /** Every future-shaped result Issue 49 refuses, beside an ordinary one. */
    @Activity
    public interface AsyncForms {
        CompletableFuture<String> completableFuture();

        Future<String> future();

        CompletionStage<String> stage();

        String step();
    }

    public static final class AsyncFormsImpl implements AsyncForms {
        @Override public CompletableFuture<String> completableFuture() { return CompletableFuture.completedFuture("v"); }

        @Override public Future<String> future() { return CompletableFuture.completedFuture("v"); }

        @Override public CompletionStage<String> stage() { return CompletableFuture.completedFuture("v"); }

        @Override public String step() { return "stepped"; }
    }

    public static final class FormsImpl implements Forms {
        private static final List<String> TAGS = List.of("delta", "alpha", "charlie", "bravo", "echo");

        @Override public Slot offset() { return new Slot(OffsetDateTime.parse("2026-11-12T10:00:00+02:00")); }

        @Override public ZSlot zoned() {
            return new ZSlot(ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneId.of("Europe/Berlin")));
        }

        @Override public Set<String> set() { return new TreeSet<>(TAGS); }

        @Override public SortedSet<String> sortedSet() { return new TreeSet<>(TAGS); }

        @Override public Map<String, Object> numbers() {
            return Map.of("long5", 5L, "long2pow60", 1L << 60, "float", 0.1f, "decimal", new BigDecimal("10.50"));
        }

        @Override public Base subtype() { return new Sub(); }

        @Override public Object asObject() { return new Receipt("r-1", new BigDecimal("10.50")); }

        @Override public SortedMap<String, Integer> sortedMap() {
            return new TreeMap<>(Map.of("c", 3, "bb", 2, "a", 1));
        }

        @Override public Receipt receipt() {
            var receipt = new Receipt("r-1", new BigDecimal("10.50"));
            Returned.LAST.set(receipt);
            return receipt;
        }
    }

    @DurableWorkflow(name = "read-back-times")
    public static class TimesWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var zoned = forms.zoned().at();
            return forms.offset().at() + " | " + zoned + " zone=" + zoned.getZone();
        }
    }

    @DurableWorkflow(name = "read-back-sets")
    public static class SetsWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var sorted = forms.sortedSet();
            return forms.set().getClass().getSimpleName() + " | " + sorted.getClass().getSimpleName() + " " + sorted;
        }
    }

    @DurableWorkflow(name = "read-back-numbers")
    public static class NumbersWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var described = new TreeMap<String, String>();
            forms.numbers().forEach((k, v) -> described.put(k, v.getClass().getSimpleName() + " " + v));
            return described.toString();
        }
    }

    @DurableWorkflow(name = "read-back-subtype")
    public static class SubtypeWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var base = forms.subtype();
            return base.getClass().getSimpleName() + " a=" + base.a + " instanceof Sub=" + (base instanceof Sub);
        }
    }

    @DurableWorkflow(name = "read-back-object")
    public static class ObjectWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var value = forms.asObject();
            return value.getClass().getSimpleName() + " " + value;
        }
    }

    @DurableWorkflow(name = "read-back-sorted-map")
    public static class SortedMapWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var map = forms.sortedMap();
            return map.getClass().getSimpleName() + " " + List.copyOf(map.keySet());
        }
    }

    @DurableWorkflow(name = "read-back-copy")
    public static class CopyWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var receipt = forms.receipt();
            return "equal=" + receipt.equals(Returned.LAST.get()) + " same=" + (receipt == Returned.LAST.get());
        }
    }

    @DurableWorkflow(name = "read-back-future")
    public static class FutureWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private AsyncForms forms;

        @WorkflowMethod
        public String run(String in) {
            return forms.step();
        }
    }
}
