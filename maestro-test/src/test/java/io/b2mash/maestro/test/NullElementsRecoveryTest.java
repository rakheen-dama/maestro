package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue 45 and its {@code parallel()} sibling: a {@code null} element — a
 * signal sent with a null payload, a branch returning {@code null} — reaches
 * workflow code as a {@code null} element of the returned list, on the first
 * run and identically on the recovered run. Before the fix both lists were
 * built with {@code List.copyOf}/{@code List.of}, which threw
 * {@code NullPointerException} after the signals were consumed (or every
 * branch had succeeded), failing the workflow.
 */
class NullElementsRecoveryTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    /** One entry per run: the list workflow code received. */
    static final List<List<?>> SEEN = new CopyOnWriteArrayList<>();

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        SEEN.clear();
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Steps.class, new StepsImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void aNullAndAValueCollectAsNullThenValue_onTheFirstRunAndAfterRecover() throws Exception {
        var handle = env.startWorkflow(CollectWorkflow.class, null);
        handle.signal("item", null);
        handle.signal("item", "v");

        await().atMost(BOUND).until(() -> SEEN.size() == 1 || handle.getStatus().isTerminal());
        assertEquals(WorkflowStatus.WAITING_SIGNAL, statusOnceParked(handle), "the first run parks on 'go'");
        env.crashNow().recover();
        await().atMost(BOUND).until(() -> SEEN.size() == 2);
        handle.signal("go", "go");

        assertEquals("2", handle.getResult(String.class, BOUND));
        var expected = Arrays.asList(null, "v");
        assertEquals(List.of(expected, expected), copy(SEEN), "first run, then recovered run");
        assertThrows(UnsupportedOperationException.class, () -> SEEN.getFirst().clear(),
                "the list stays unmodifiable");
    }

    @Test
    void aNullPayloadCollectedAsOptionalIsEmpty_onTheFirstRunAndAfterRecover() throws Exception {
        var handle = env.startWorkflow(CollectOptionalWorkflow.class, null);
        handle.signal("item", null);
        handle.signal("item", "v");

        await().atMost(BOUND).until(() -> SEEN.size() == 1 || handle.getStatus().isTerminal());
        assertEquals(WorkflowStatus.WAITING_SIGNAL, statusOnceParked(handle));
        env.crashNow().recover();
        await().atMost(BOUND).until(() -> SEEN.size() == 2);
        handle.signal("go", "go");

        assertEquals("2", handle.getResult(String.class, BOUND));
        var expected = List.of(Optional.empty(), Optional.of("v"));
        assertEquals(List.of(expected, expected), copy(SEEN));
    }

    @Test
    void aBranchReturningNullJoinsWithANullElement_onTheFirstRunAndAfterRecover() throws Exception {
        var handle = env.startWorkflow(ParallelWorkflow.class, null);

        await().atMost(BOUND).until(() -> SEEN.size() == 2 || handle.getStatus().isTerminal());
        assertEquals(WorkflowStatus.WAITING_SIGNAL, statusOnceParked(handle), "the first run parks on 'go'");
        env.crashNow().recover();
        await().atMost(BOUND).until(() -> SEEN.size() == 4);
        handle.signal("go", "go");

        assertEquals("done", handle.getResult(String.class, BOUND));
        var single = Arrays.asList((Object) null);
        var forked = Arrays.asList(null, "b");
        assertEquals(List.of(single, forked, single, forked), copy(SEEN),
                "one-task fork, then two-task fork; first run, then recovered run");
        assertEquals(1, handle.liveExecutions("Steps.touch"), "the void branch's activity replays on recovery");
    }

    private static WorkflowStatus statusOnceParked(TestWorkflowHandle handle) {
        await().atMost(BOUND).until(() -> handle.getStatus() != WorkflowStatus.RUNNING);
        return handle.getStatus();
    }

    private static List<List<?>> copy(List<List<?>> seen) {
        var out = new ArrayList<List<?>>();
        for (var list : seen) {
            out.add(new ArrayList<>(list));
        }
        return out;
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Steps {
        void touch(String what);
    }

    public static final class StepsImpl implements Steps {
        @Override
        public void touch(String what) {
            // side effect only
        }
    }

    @DurableWorkflow(name = "collect-null-payloads")
    public static class CollectWorkflow {
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            List<@Nullable String> items = workflow.collectSignals("item", String.class, 2, Duration.ofDays(1));
            SEEN.add(items);
            workflow.awaitSignal("go", String.class, Duration.ofDays(1));
            return String.valueOf(items.size());
        }
    }

    @DurableWorkflow(name = "collect-null-payloads-optional")
    public static class CollectOptionalWorkflow {
        @WorkflowMethod
        @SuppressWarnings({"unchecked", "rawtypes"})
        public String run() {
            var workflow = WorkflowContext.current();
            List<Optional> items = workflow.collectSignals("item", Optional.class, 2, Duration.ofDays(1));
            SEEN.add(items);
            workflow.awaitSignal("go", String.class, Duration.ofDays(1));
            return String.valueOf(items.size());
        }
    }

    @DurableWorkflow(name = "parallel-null-results")
    public static class ParallelWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            List<Callable<@Nullable Void>> one = List.of(() -> null);
            SEEN.add(workflow.parallel(one));
            List<Callable<@Nullable String>> two = List.of(
                    () -> {
                        steps.touch("a");
                        return null;
                    },
                    () -> "b");
            SEEN.add(workflow.parallel(two));
            workflow.awaitSignal("go", String.class, Duration.ofDays(1));
            return "done";
        }
    }
}
