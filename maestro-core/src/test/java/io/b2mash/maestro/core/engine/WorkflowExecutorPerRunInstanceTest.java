package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1: the executor builds one workflow object per run from the registration's
 * factory, so instance fields — and therefore {@code @QueryMethod} answers —
 * never bleed between concurrent runs of the same workflow type, and a
 * re-adopted run never inherits the object of a previous one.
 */
@DisplayName("One workflow object per run")
class WorkflowExecutorPerRunInstanceTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;
    private WorkflowExecutor second;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
        if (second != null) second.shutdown();
    }

    @Test
    void eachStartInvokesTheFactoryAndQueriesAnswerFromThatRunsObject() throws Exception {
        var created = new AtomicInteger();
        Supplier<Object> factory = () -> { created.incrementAndGet(); return new ProbeWorkflow(); };
        var method = ProbeWorkflow.class.getMethod("run", String.class);
        executor.registerQueries("probe", ProbeWorkflow.class);

        executor.startWorkflow("p-a", "probe", "default", "A", factory, method);
        executor.startWorkflow("p-b", "probe", "default", "B", factory, method);
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 2);

        assertEquals(2, created.get(), "one workflow object per run");
        assertEquals("A", executor.queryWorkflow("p-a", "step", null, String.class));
        assertEquals("B", executor.queryWorkflow("p-b", "step", null, String.class));
    }

    @Test
    void resumeInvokesTheFactoryAgainAndDiscardsTheOldObject() throws Exception {
        var created = new AtomicInteger();
        var first = new ProbeWorkflow();
        var replacement = new ProbeWorkflow();
        Supplier<Object> factory = () ->
                created.incrementAndGet() == 1 ? first : replacement;
        var method = ProbeWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("probe", ProbeWorkflow.class);
        executor.startWorkflow("p-r", "probe", "default", "R", factory, method);
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 1);
        assertEquals("R", first.step);

        // Node stops mid-park: the park is abandoned and the row stays WAITING_SIGNAL.
        executor.shutdown();
        executor = null;
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("p-r").orElseThrow().status());

        second = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        second.registerQueries("probe", ProbeWorkflow.class);
        assertTrue(second.resumeWorkflow(store.getInstance("p-r").orElseThrow(), factory, method));
        await().atMost(5, SECONDS).until(() -> second.parkedCount() == 1);

        assertEquals(2, created.get(), "the re-adoption builds a fresh object");
        assertNotSame(first, replacement);
        assertEquals("R", replacement.step, "the replayed run wrote to the new object");
        assertEquals("R", second.queryWorkflow("p-r", "step", null, String.class));
    }

    @Test
    void aThrowingFactorySurfacesToTheCallerAndLeavesNoRunBehind() throws Exception {
        var method = ProbeWorkflow.class.getMethod("run", String.class);
        Supplier<Object> factory = () -> { throw new IllegalStateException("no bean"); };

        var thrown = assertThrows(IllegalStateException.class,
                () -> executor.startWorkflow("p-x", "probe", "default", "X", factory, method));

        assertEquals("no bean", thrown.getMessage());
        assertEquals(false, executor.isRunning("p-x"), "no running-workflow record is left behind");
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** Records its input in an instance field, then parks on a signal. */
    @DurableWorkflow(name = "probe")
    public static class ProbeWorkflow {

        volatile String step = "init";

        /**
         * @param label the value the run writes into this object's state
         * @return the signal payload
         */
        @WorkflowMethod
        public String run(String label) {
            step = label;
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(1));
        }

        /** @return this object's recorded label */
        @QueryMethod
        public String step() {
            return step;
        }
    }
}
