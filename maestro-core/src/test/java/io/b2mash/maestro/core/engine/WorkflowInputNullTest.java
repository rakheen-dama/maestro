package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The workflow input reads the same on the live start and after recovery
 * (WP1 Task 5, fix round 1). On Postgres a {@code NullNode} input — what
 * {@code Optional.empty()} serializes to — is stored as SQL {@code NULL} and
 * recovers as a Java {@code null}, while the live start still holds the
 * {@code NullNode}; both must reach the serializer, so an {@code Optional}
 * parameter is {@code Optional.empty()} in both runs and every other parameter
 * type is Java {@code null} in both, as before Task 5.
 *
 * <p>{@link SqlNullStore} writes a {@code NullNode} input as {@code null}, as
 * {@code PostgresWorkflowStore} does.
 */
class WorkflowInputNullTest {

    private static final String SERVICE = "input-null-svc";
    private static final Duration BOUND = Duration.ofSeconds(10);

    private SqlNullStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observerA;
    private RecordingEngineObserver observerB;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        store = new SqlNullStore();
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

    record Pair(String left, String right) {}

    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of("Optional<String> = Optional.empty()", OptionalInput.class, Optional.empty(),
                        Optional.empty()),
                Arguments.of("String = null", StringInput.class, null, null),
                Arguments.of("Integer = null", IntegerInput.class, null, null),
                Arguments.of("record = null", RecordInput.class, null, null));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("inputs")
    @Timeout(30)
    void theInputReadsTheSameLiveAndAfterRecovery(String label, Class<? extends Recording> type,
                                                  @Nullable Object input, @Nullable Object expected)
            throws Exception {
        var id = "input-" + type.getSimpleName();
        var seen = Collections.synchronizedList(new ArrayList<@Nullable Object>());
        var method = runMethodOf(type);
        Function<List<@Nullable Object>, Object> factory = s -> newWorkflow(type, s);

        nodeA.startWorkflow(id, type.getSimpleName(), "default", input, () -> factory.apply(seen), method);
        await().atMost(BOUND).until(() -> !seen.isEmpty() || !observerA.standDowns().isEmpty());
        assertNull(store.getInstance(id).orElseThrow().input(), "the store holds SQL NULL, as Postgres does");

        // Node A stops while parked; node B adopts the run and re-reads the stored input.
        nodeA.shutdown();
        nodeA = null;
        nodeB = WorkflowExecutor.builder(store, serializer, SERVICE).observer(observerB).build();
        nodeB.recoverWorkflows(Map.of(type.getSimpleName(), new WorkflowRegistration(
                type.getSimpleName(), "default", () -> factory.apply(seen), method)));
        await().atMost(BOUND).until(() -> seen.size() >= 2 || !observerB.standDowns().isEmpty());

        assertEquals(Arrays.asList(expected, expected), new ArrayList<>(seen), label + " [live, recovered]");
        assertEquals(List.of(), observerA.standDowns(), "live stand-downs");
        assertEquals(List.of(), observerB.standDowns(), "recovery stand-downs");

        nodeB.deliverSignal(id, "go", "go");
        await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance(id).orElseThrow().status());
    }

    private static Method runMethodOf(Class<?> type) {
        return Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("run")).findFirst().orElseThrow();
    }

    private static Object newWorkflow(Class<? extends Recording> type, List<@Nullable Object> seen) {
        try {
            var workflow = type.getDeclaredConstructor().newInstance();
            workflow.seen = seen;
            return workflow;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records its input, then parks until "go". */
    public abstract static class Recording {
        @Nullable List<@Nullable Object> seen;

        String record(@Nullable Object input) {
            seen.add(input);
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
            return "done";
        }
    }

    @DurableWorkflow(name = "OptionalInput")
    public static class OptionalInput extends Recording {
        @WorkflowMethod
        public String run(Optional<String> in) {
            return record(in);
        }
    }

    @DurableWorkflow(name = "StringInput")
    public static class StringInput extends Recording {
        @WorkflowMethod
        public String run(String in) {
            return record(in);
        }
    }

    @DurableWorkflow(name = "IntegerInput")
    public static class IntegerInput extends Recording {
        @WorkflowMethod
        public String run(Integer in) {
            return record(in);
        }
    }

    @DurableWorkflow(name = "RecordInput")
    public static class RecordInput extends Recording {
        @WorkflowMethod
        public String run(Pair in) {
            return record(in);
        }
    }

    /** Writes a {@code NullNode} input as {@code null}, as {@code PostgresWorkflowStore} writes SQL {@code NULL}. */
    static final class SqlNullStore extends VersionedInMemoryStore {
        private static WorkflowInstance sqlNull(WorkflowInstance instance) {
            return instance.input() != null && instance.input().isNull()
                    ? instance.toBuilder().input(null).build()
                    : instance;
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            return super.createInstance(sqlNull(instance));
        }

        @Override
        public synchronized void updateInstance(WorkflowInstance instance) {
            super.updateInstance(sqlNull(instance));
        }

        @Override
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            return super.completeInstance(sqlNull(terminal), terminalEvent);
        }
    }
}
