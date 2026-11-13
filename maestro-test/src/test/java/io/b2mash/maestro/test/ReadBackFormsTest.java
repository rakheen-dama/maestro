package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
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

/**
 * The forms {@code docs/pitfalls.md} rule 12 names: what workflow code gets
 * from a live activity call is the stored result read back into the method's
 * declared return type — on the <em>first</em> run, not only after a restart —
 * and not the object the activity returned. Each test runs a workflow
 * straight through the kit (no crash) and asserts the value it saw, so each
 * would read differently if a live call returned the activity's own object.
 *
 * <p>Two exceptions return the activity's own object on the first run: a
 * read-back of another class than the object the activity returned (a
 * subtype declared as its supertype, a record declared as {@code Object}; the
 * type valve), and a method declared to return a {@code Future} or
 * {@code CompletionStage}, which is never read back live. A replay of either
 * still reads the stored payload as the declared type.
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
     * Jackson writes a {@code CompletableFuture}'s bean properties, never its
     * value. A live call does not read that back: it returns the activity's
     * own future, so {@code join()} completes on the first run.
     */
    @Test
    void aCompletableFutureResultIsTheActivitysFuture_andJoinCompletesOnTheFirstRun() throws Exception {
        var handle = env.startWorkflow(CompletableFutureWorkflow.class, "x");
        assertEquals("CompletableFuture done=true joined=v", handle.getResult(String.class, BOUND));
        assertEquals("{\"cancelled\":false,\"completedExceptionally\":false,\"done\":true,\"numberOfDependents\":0}",
                String.valueOf(handle.getEvents().stream()
                        .filter(e -> "Forms.completableFuture".equals(e.stepName()))
                        .findFirst().orElseThrow().payload()));
    }

    /**
     * {@code Future} and {@code CompletionStage} are never read back live (nor
     * could they be): the live run carries on with the activity's own object ...
     */
    @Test
    void aFutureOrCompletionStageResult_runsLiveWithTheActivitysObject() throws Exception {
        assertEquals("Future done=true | CompletionStage done=true | stepped",
                env.startWorkflow(FutureInterfacesWorkflow.class, "x").getResult(String.class, BOUND));
    }

    /** ... and a recovery that replays either stands the run down. */
    @Test
    void aFutureOrCompletionStageResult_standsDownOnRecovery() throws Exception {
        var crash = env.crashAfter(3);   // future, stage and step completed; the node dies before the run ends
        var handle = env.startWorkflow(FutureInterfacesWorkflow.class, "x");
        crash.awaitCrash(BOUND);
        crash.recover();

        var standDown = handle.awaitStandDown(BOUND);
        assertEquals(StandDownReason.UNKNOWN_EVENT_PAYLOAD, standDown.reason());
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus());
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

        CompletableFuture<String> completableFuture();

        Future<String> future();

        CompletionStage<String> stage();

        String step();
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

        @Override public CompletableFuture<String> completableFuture() { return CompletableFuture.completedFuture("v"); }

        @Override public Future<String> future() { return CompletableFuture.completedFuture("v"); }

        @Override public CompletionStage<String> stage() { return CompletableFuture.completedFuture("v"); }

        @Override public String step() { return "stepped"; }
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

    @DurableWorkflow(name = "read-back-completable-future")
    public static class CompletableFutureWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var future = forms.completableFuture();
            return future.getClass().getSimpleName() + " done=" + future.isDone() + " joined=" + future.join();
        }
    }

    @DurableWorkflow(name = "read-back-future-interfaces")
    public static class FutureInterfacesWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Forms forms;

        @WorkflowMethod
        public String run(String in) {
            var future = forms.future();
            var stage = forms.stage();
            return "Future done=" + future.isDone() + " | CompletionStage done="
                    + stage.toCompletableFuture().isDone() + " | " + forms.step();
        }
    }
}
