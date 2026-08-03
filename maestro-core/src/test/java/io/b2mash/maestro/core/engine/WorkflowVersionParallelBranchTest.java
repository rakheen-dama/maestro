package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.saga.CompensationStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that a {@code version()} call inside a parallel branch allocates from
 * that branch's partitioned sequence space, not the main line's (design §5.3,
 * spec §C1's parallel-branch allocation requirement).
 *
 * <p>A marker written into the wrong space would collide with — or be shadowed
 * by — the parent's next step, so replay would read someone else's event at the
 * version slot. The assertion is therefore on the marker's exact recorded
 * sequence number, not merely on the value returned.
 */
@DisplayName("workflow.version() inside a parallel branch")
class WorkflowVersionParallelBranchTest {

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
                .workflowId("version-branch-wf")
                .runId(UUID.randomUUID())
                .workflowType("VersionBranchWorkflow")
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
    @DisplayName("the marker lands in the branch's own sequence block (p*1000 + (i+1)*1000)")
    void versionInsideBranch_allocatesFromTheBranchSpace() {
        var results = new AtomicReference<List<Integer>>();

        run(ops -> {
            // The fork itself consumes sequence 1 (parent seq p = 1), so branch 0
            // allocates from 1*1000 + 1*1000 = 2000 and branch 1 from 3000.
            List<Callable<Integer>> tasks = List.of(
                    () -> WorkflowContext.current().version("branch-change", -1, 2),
                    () -> WorkflowContext.current().version("other-change", -1, 4));
            results.set(ops.parallel(tasks));
        });

        var branch0 = markerFor("branch-change");
        var branch1 = markerFor("other-change");

        assertAll(
                () -> assertEquals(List.of(2, 4), results.get(),
                        "each branch resolves its own changeId live"),
                () -> assertEquals(2001, branch0.sequenceNumber(),
                        "branch 0's marker belongs to block [2000..2999]: " + eventLog()),
                () -> assertEquals(3001, branch1.sequenceNumber(),
                        "branch 1's marker belongs to block [3000..3999]: " + eventLog()),
                () -> assertEquals(2, branch0.payload().path("version").intValue()),
                () -> assertEquals(4, branch1.payload().path("version").intValue()));
    }

    @Test
    @DisplayName("replay of a branch resolves from the branch's marker, not the code's new max")
    void replayInsideBranch_returnsRecordedVersion() {
        run(ops -> ops.parallel(List.of(
                () -> WorkflowContext.current().version("branch-change", -1, 2),
                () -> WorkflowContext.current().version("other-change", -1, 4))));

        var replayed = new AtomicReference<List<Integer>>();
        run(ops -> replayed.set(ops.parallel(List.of(
                () -> WorkflowContext.current().version("branch-change", -1, 9),
                () -> WorkflowContext.current().version("other-change", -1, 9)))));

        assertAll(
                () -> assertEquals(List.of(2, 4), replayed.get(),
                        "branches replay their recorded versions"),
                () -> assertEquals(2, store.getEvents(instance.id()).stream()
                                .filter(e -> e.eventType() == EventType.VERSION_MARKER).count(),
                        "no new markers on replay: " + eventLog()));
    }

    // ── harness ────────────────────────────────────────────────────────

    private void run(java.util.function.Consumer<DefaultWorkflowOperations> body) {
        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        var ops = new DefaultWorkflowOperations(store, null, null, serializer, parkingLot,
                new CompensationStack(), signalManager);
        var ctx = new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                0, true, ops);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> body.accept(ops));
    }

    private WorkflowEvent markerFor(String changeId) {
        return store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.VERSION_MARKER)
                .filter(e -> ("$maestro:version:" + changeId).equals(e.stepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no VERSION_MARKER for '" + changeId + "'" + eventLog()));
    }

    private String eventLog() {
        return "\nevent log: " + store.getEvents(instance.id()).stream()
                .map(e -> "%d:%s:%s:%s".formatted(e.sequenceNumber(), e.eventType(),
                        e.stepName(), e.payload()))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n           " + b);
    }
}
