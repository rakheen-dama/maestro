package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.UnreadableWorkflowInputException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 44: the workflow input is read with the workflow method's generic
 * parameter type, so a {@code List<Pair>} parameter receives {@code Pair}s —
 * on the first run and after a crash and recovery. Before the fix it was read
 * with the erased class and a {@code List<Pair>} arrived as a list of
 * {@code LinkedHashMap}s; the first {@code pairs.get(0).a()} threw
 * {@code ClassCastException} and the run FAILED.
 *
 * <p>Declarations the generic type does not narrow — a raw {@code List},
 * {@code Object}, a type variable — read exactly as before: Jackson's natural
 * form ({@code LinkedHashMap}s for objects). Like an activity result declared
 * as a supertype, such an input is accepted, never rejected at start.
 *
 * <p>An input this build cannot read back into the declared type is rejected
 * at start with {@link UnreadableWorkflowInputException}, persisting nothing,
 * rather than becoming a run that stands down at sequence 0 for ever.
 */
class WorkflowInputTypeTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    /** One entry per run: what workflow code observed about its input. */
    static final List<Object> SEEN = new CopyOnWriteArrayList<>();

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        SEEN.clear();
        env = TestWorkflowEnvironment.create();
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    public record Pair(String a, int b) {}

    @Test
    void aListOfRecordsParameterReadsRecords_onTheFirstRunAndAfterRecover() throws Exception {
        var handle = env.startWorkflow(ListOfPairsWorkflow.class, List.of(new Pair("x", 1), new Pair("y", 2)));

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("x1y2", result);
        assertEquals(List.of("x1y2", "x1y2"), copy(), "first run, then recovered run");
    }

    @Test
    void aMapOfStringToListOfRecordsParameterReadsRecords_onTheFirstRunAndAfterRecover() throws Exception {
        var input = new LinkedHashMap<String, List<Pair>>();
        input.put("left", List.of(new Pair("x", 1)));
        input.put("right", List.of(new Pair("y", 2), new Pair("z", 3)));
        var handle = env.startWorkflow(MapOfPairsWorkflow.class, input);

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("left=x1;right=y2z3;", result);
        assertEquals(List.of("left=x1;right=y2z3;", "left=x1;right=y2z3;"), copy());
    }

    @Test
    void anObjectParameterReadsJacksonsNaturalForm_asBefore_andIsNotRejected() throws Exception {
        var handle = env.startWorkflow(ObjectInputWorkflow.class, new Pair("x", 1));

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("LinkedHashMap", result);
        assertEquals(List.of("LinkedHashMap", "LinkedHashMap"), copy());
    }

    @Test
    void aTypeVariableParameterReadsItsBound_asBefore_andIsNotRejected() throws Exception {
        var handle = env.startWorkflow(TypeVariableInputWorkflow.class, List.of(new Pair("x", 1)));

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("ArrayList[LinkedHashMap]", result);
        assertEquals(List.of("ArrayList[LinkedHashMap]", "ArrayList[LinkedHashMap]"), copy());
    }

    @Test
    void aRawListParameterStillReadsMaps() throws Exception {
        var handle = env.startWorkflow(RawListWorkflow.class, List.of(new Pair("x", 1)));

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("LinkedHashMap", result);
        assertEquals(List.of("LinkedHashMap", "LinkedHashMap"), copy());
    }

    @Test
    void aNoArgumentWorkflowRunsAndRecovers() throws Exception {
        var handle = env.startWorkflow(NoArgumentWorkflow.class, null);

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("none", result);
        assertEquals(List.of("none", "none"), copy());
    }

    @Test
    void anUntypedMapInputIsTheSameOnTheFirstRunAndAfterRecover() throws Exception {
        // Issue 43's kit twin: the first run launches from the input the store
        // returned, so it reads exactly what the recovered run reads.
        var input = new LinkedHashMap<String, Object>();
        input.put("aa", 1);
        input.put("b", 2);
        input.put("n", 1.0E7);
        var handle = env.startWorkflow(UntypedMapWorkflow.class, input);

        var result = parkCrashRecoverAndFinish(handle);

        assertEquals("[aa, b, n] n=Double", result, "the kit keeps the order the map had");
        assertEquals(List.of(result, result), copy());
    }

    @Test
    void anInputThatCannotBeReadBackIsRejectedAtStart_nothingPersisted() {
        var thrown = assertThrows(UnreadableWorkflowInputException.class,
                () -> env.startWorkflow("opaque-1", OpaqueInputWorkflow.class, new Square(7)));

        assertEquals("opaque-1", thrown.workflowId());
        assertEquals("opaque-input", thrown.workflowType());
        assertEquals(Shape.class.getTypeName(), thrown.parameterType());
        assertTrue(thrown.getMessage().contains("opaque-input")
                        && thrown.getMessage().contains(Shape.class.getTypeName()),
                thrown.getMessage());
        assertTrue(env.getStore().getInstance("opaque-1").isEmpty(), "no instance row");
        assertTrue(SEEN.isEmpty(), "workflow code never ran");

        // The id is not burnt: the same start with a readable input succeeds.
        var handle = env.startWorkflow("opaque-1", ListOfPairsWorkflow.class, List.of(new Pair("q", 9)));
        assertSame(WorkflowStatus.WAITING_SIGNAL, awaitParked(handle));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Waits for the first run to park on "go", crashes, recovers, waits for the replay, releases it. */
    private String parkCrashRecoverAndFinish(TestWorkflowHandle handle) throws Exception {
        assertEquals(WorkflowStatus.WAITING_SIGNAL, awaitParked(handle),
                () -> "the first run parks on 'go': " + handle.getFailure());
        assertEquals(1, SEEN.size());
        env.crashNow().recover();
        await().atMost(BOUND).until(() -> SEEN.size() == 2 || handle.getStatus().isTerminal());
        handle.signal("go", "go");
        return handle.getResult(String.class, BOUND);
    }

    private static WorkflowStatus awaitParked(TestWorkflowHandle handle) {
        await().atMost(BOUND).until(() -> handle.getStatus() != WorkflowStatus.RUNNING);
        return handle.getStatus();
    }

    private static List<Object> copy() {
        return new ArrayList<>(SEEN);
    }

    /** Records what the run saw, then parks on "go" so a crash can land after it. */
    private static String seeAndPark(String seen) {
        SEEN.add(seen);
        WorkflowContext.current().awaitSignal("go", String.class, Duration.ofDays(1));
        return seen;
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @DurableWorkflow(name = "list-of-pairs")
    public static class ListOfPairsWorkflow {
        @WorkflowMethod
        public String run(List<Pair> pairs) {
            var out = new StringBuilder();
            for (Pair pair : pairs) {
                out.append(pair.a()).append(pair.b());
            }
            return seeAndPark(out.toString());
        }
    }

    @DurableWorkflow(name = "map-of-pairs")
    public static class MapOfPairsWorkflow {
        @WorkflowMethod
        public String run(Map<String, List<Pair>> groups) {
            var out = new StringBuilder();
            for (var entry : groups.entrySet()) {
                out.append(entry.getKey()).append('=');
                for (Pair pair : entry.getValue()) {
                    out.append(pair.a()).append(pair.b());
                }
                out.append(';');
            }
            return seeAndPark(out.toString());
        }
    }

    @DurableWorkflow(name = "untyped-map-input")
    public static class UntypedMapWorkflow {
        @WorkflowMethod
        public String run(Map<String, Object> input) {
            return seeAndPark(input.keySet() + " n=" + input.get("n").getClass().getSimpleName());
        }
    }

    @DurableWorkflow(name = "object-input")
    public static class ObjectInputWorkflow {
        @WorkflowMethod
        public String run(Object input) {
            return seeAndPark(input.getClass().getSimpleName());
        }
    }

    @DurableWorkflow(name = "type-variable-input")
    public static class TypeVariableInputWorkflow {
        @WorkflowMethod
        public <T> String run(T input) {
            var list = (List<?>) input;
            return seeAndPark(input.getClass().getSimpleName() + "[" + list.getFirst().getClass().getSimpleName() + "]");
        }
    }

    @DurableWorkflow(name = "raw-list-input")
    public static class RawListWorkflow {
        @WorkflowMethod
        @SuppressWarnings("rawtypes")
        public String run(List input) {
            return seeAndPark(input.getFirst().getClass().getSimpleName());
        }
    }

    @DurableWorkflow(name = "no-argument")
    public static class NoArgumentWorkflow {
        @WorkflowMethod
        public String run() {
            return seeAndPark("none");
        }
    }

    /** An interface with no type information: it serializes from an implementation but cannot be read back. */
    public interface Shape {
        int side();
    }

    public record Square(int side) implements Shape {}

    @DurableWorkflow(name = "opaque-input")
    public static class OpaqueInputWorkflow {
        @WorkflowMethod
        public String run(Shape input) {
            return seeAndPark("shape " + input.side());
        }
    }
}
