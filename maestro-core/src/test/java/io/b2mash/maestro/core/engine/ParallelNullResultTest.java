package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.saga.CompensationStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@code parallel()} branch returning {@code null} — the idiomatic
 * {@code () -> { act.doIt(); return null; }} of a {@code Callable<Void>} —
 * joins with a {@code null} element at its index, on the single-task fast
 * path and on a real fork, live and on replay (Issue 45's sibling: the join
 * used to build the result with {@code List.of}/{@code List.copyOf}, which
 * threw {@code NullPointerException} after every branch had succeeded).
 */
@DisplayName("parallel() with a branch returning null")
class ParallelNullResultTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowInstance instance;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        var now = Instant.now();
        instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("parallel-null-wf")
                .runId(UUID.randomUUID())
                .workflowType("ParallelNullWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
        store.createInstance(instance);
    }

    @Test
    @DisplayName("a single-task fork returns a one-element list holding null")
    void aSingleBranchReturningNull_joinsWithANullElement() {
        var result = new AtomicReference<List<@Nullable Void>>();
        List<Callable<@Nullable Void>> tasks = List.of(() -> null);

        run(ops -> result.set(ops.parallel(tasks)));

        assertEquals(Arrays.asList((Void) null), result.get());
        assertThrows(UnsupportedOperationException.class, () -> result.get().add(null));
    }

    @Test
    @DisplayName("a forked branch returning null leaves null at its index, live and on replay")
    void aForkedBranchReturningNull_joinsWithANullElement_liveAndOnReplay() {
        List<Callable<@Nullable String>> tasks = List.of(
                () -> WorkflowContext.current().randomUUID() != null ? null : "unreachable",
                () -> "b",
                () -> null);

        var live = new AtomicReference<List<@Nullable String>>();
        run(ops -> live.set(ops.parallel(tasks)));
        var replayed = new AtomicReference<List<@Nullable String>>();
        run(ops -> replayed.set(ops.parallel(tasks)));

        assertEquals(Arrays.asList(null, "b", null), live.get(), "live");
        assertEquals(live.get(), replayed.get(), "replay");
        assertThrows(UnsupportedOperationException.class, () -> live.get().set(0, "x"));
    }

    // ── harness ────────────────────────────────────────────────────────

    private void run(Consumer<DefaultWorkflowOperations> body) {
        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        var ops = new DefaultWorkflowOperations(store, null, serializer, parkingLot,
                new CompensationStack(), signalManager);
        var ctx = new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                0, true, ops);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> body.accept(ops));
    }
}
