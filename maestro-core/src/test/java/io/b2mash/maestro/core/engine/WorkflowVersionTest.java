package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.UnsupportedWorkflowVersionException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the semantics of {@code workflow.version()} — memoized change-branching
 * (design §5).
 *
 * <p>Every assertion here is about the <em>persisted event log</em>: which
 * event exists, at which sequence number, carrying which payload. A version
 * decision that is not durable is not a decision at all — on the next replay
 * the workflow would take whatever branch the current code says, which is
 * precisely the failure mode this API exists to prevent.
 *
 * <p>The tests drive {@link DefaultWorkflowOperations} directly inside a bound
 * {@link WorkflowContext}, which is what a workflow method does. A "run" is one
 * operations instance (the engine builds a fresh one per launch — see
 * {@code WorkflowExecutor.launchWorkflow}), so a second operations instance
 * over the same store is exactly a recovery replay.
 */
@DisplayName("workflow.version() — memoized change-branching")
class WorkflowVersionTest {

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
                .workflowId("version-wf")
                .runId(UUID.randomUUID())
                .workflowType("VersionWorkflow")
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

    // ── (a) live call ──────────────────────────────────────────────────

    @Test
    @DisplayName("a live call returns maxSupported and records a VERSION_MARKER at the consumed sequence")
    void liveCall_recordsMarkerAtConsumedSequence() {
        var resolved = inRun(ops -> ops.version("shipping-v2", -1, 3));

        assertEquals(3, resolved, "a live call returns the code's maxSupported");

        var markers = eventsOfType(EventType.VERSION_MARKER);
        assertEquals(1, markers.size(), "exactly one marker: " + eventLog());
        var marker = markers.getFirst();
        assertAll(
                () -> assertEquals(1, marker.sequenceNumber(),
                        "the marker consumes the workflow's first sequence slot"),
                () -> assertEquals("$maestro:version:shipping-v2", marker.stepName()),
                () -> assertEquals("shipping-v2", marker.payload().path("changeId").stringValue()),
                () -> assertEquals(3, marker.payload().path("version").intValue()));
    }

    // ── (b) replay under a changed maxSupported ────────────────────────

    @Test
    @DisplayName("replay returns the recorded version even when the code's maxSupported has moved on")
    void replay_returnsRecordedVersion_notTheCodesCurrentMax() {
        var original = inRun(ops -> ops.version("shipping-v2", -1, 3));
        assertEquals(3, original);

        // The code is redeployed declaring a higher max; the instance replays.
        var replayed = inRun(ops -> ops.version("shipping-v2", -1, 5));

        assertEquals(3, replayed,
                "replay must return the RECORDED version, never the new maxSupported");
        assertEquals(1, eventsOfType(EventType.VERSION_MARKER).size(),
                "replay must not append a second marker: " + eventLog());
        assertEquals(3, eventsOfType(EventType.VERSION_MARKER).getFirst()
                        .payload().path("version").intValue(),
                "the durable record is unchanged by the replay");
    }

    // ── (c) pre-marker history: peek, do not consume ───────────────────

    @Test
    @DisplayName("a history written before the change replays byte-identically and yields DEFAULT_VERSION")
    void preMarkerHistory_yieldsDefaultVersion_withoutConsumingTheSlot() {
        // The original code: two memoized side-effects, no version() call.
        var originalIds = new ArrayList<String>();
        inRun(ops -> {
            originalIds.add(ops.randomUUID());
            originalIds.add(ops.randomUUID());
            return null;
        });
        var before = snapshot();
        assertEquals(2, before.size(), "the pre-change history: " + eventLog());

        // The new code inserts version() between the two steps.
        var resolved = new AtomicReference<Integer>();
        var replayedIds = new ArrayList<String>();
        inRun(ops -> {
            replayedIds.add(ops.randomUUID());
            resolved.set(ops.version("shipping-v2", -1, 4));
            replayedIds.add(ops.randomUUID());
            return null;
        });

        assertAll(
                () -> assertEquals(WorkflowContext.DEFAULT_VERSION, resolved.get(),
                        "a history that predates the change resolves to DEFAULT_VERSION"),
                () -> assertEquals(originalIds, replayedIds,
                        "the slot must NOT be consumed — consuming it shifts every "
                                + "later replay lookup by one"),
                () -> assertEquals(before, snapshot(),
                        "the old history must replay byte-identically: " + eventLog()));
    }

    // ── (d) min-guard ──────────────────────────────────────────────────

    @Test
    @DisplayName("a recorded version below minSupported raises the typed error naming changeId and both bounds")
    void recordedBelowMin_raisesTypedError() {
        inRun(ops -> ops.version("shipping-v2", -1, 1));

        var error = assertThrows(UnsupportedWorkflowVersionException.class,
                () -> inRun(ops -> ops.version("shipping-v2", 2, 4)));

        assertAll(
                () -> assertEquals("version-wf", error.workflowId()),
                () -> assertEquals("shipping-v2", error.changeId()),
                () -> assertEquals(1, error.recordedVersion()),
                () -> assertEquals(2, error.minSupported()),
                () -> assertEquals(4, error.maxSupported()),
                () -> assertTrue(error.getMessage().contains("shipping-v2"),
                        "message must name the changeId, got: " + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("version 1"),
                        "message must name the recorded version, got: " + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("[2..4]"),
                        "message must name the supported range, got: " + error.getMessage()));
    }

    @Test
    @DisplayName("a pre-change history fails the guard when the code no longer carries the old branch")
    void defaultVersionBelowMin_raisesTypedError() {
        // A pre-change history: two steps, no marker. The second step's event is
        // what the inserted version() call finds at its peeked slot.
        inRun(ops -> {
            ops.randomUUID();
            return ops.randomUUID();
        });

        var error = assertThrows(UnsupportedWorkflowVersionException.class,
                () -> inRun(ops -> {
                    ops.randomUUID();
                    return ops.version("shipping-v2", 1, 2);
                }));

        assertEquals(WorkflowContext.DEFAULT_VERSION, error.recordedVersion());
    }

    @Test
    @DisplayName("the min-guard failure is a MaestroException — catchable, retryable, not a control-flow Error")
    void minGuardFailure_isAMaestroException() {
        inRun(ops -> ops.version("shipping-v2", -1, 1));

        var error = assertThrows(UnsupportedWorkflowVersionException.class,
                () -> inRun(ops -> ops.version("shipping-v2", 2, 4)));

        assertTrue(error instanceof io.b2mash.maestro.core.exception.MaestroException,
                "the min-guard failure is a deterministic workflow failure, not a "
                        + "control-flow signal");
    }

    // ── (e) repeated calls in one run ──────────────────────────────────

    @Test
    @DisplayName("repeated calls with the same changeId in one run return the same value and write once")
    void repeatedCalls_sameChangeId_oneRun_resolveOnce() {
        var sequenceAfterFirst = new AtomicReference<Integer>();
        var second = new AtomicReference<Integer>();

        var first = inRun(ops -> {
            var v1 = ops.version("shipping-v2", -1, 3);
            sequenceAfterFirst.set(WorkflowContext.current().currentSequence());
            second.set(ops.version("shipping-v2", -1, 3));
            assertEquals(sequenceAfterFirst.get(), WorkflowContext.current().currentSequence(),
                    "a cached resolution must not consume a sequence number");
            return v1;
        });

        assertAll(
                () -> assertEquals(3, first),
                () -> assertEquals(3, second.get()),
                () -> assertEquals(1, eventsOfType(EventType.VERSION_MARKER).size(),
                        "one changeId, one marker: " + eventLog()));
    }

    @Test
    @DisplayName("a replayed run resolves a repeated changeId from the record, not from the new max")
    void repeatedCalls_onReplay_bothReturnRecordedVersion() {
        inRun(ops -> {
            ops.version("shipping-v2", -1, 2);
            return ops.version("shipping-v2", -1, 2);
        });

        var second = new AtomicReference<Integer>();
        var first = inRun(ops -> {
            var v1 = ops.version("shipping-v2", -1, 7);
            second.set(ops.version("shipping-v2", -1, 7));
            return v1;
        });

        assertAll(
                () -> assertEquals(2, first),
                () -> assertEquals(2, second.get()),
                () -> assertEquals(1, eventsOfType(EventType.VERSION_MARKER).size(),
                        "still exactly one marker: " + eventLog()));
    }

    @Test
    @DisplayName("distinct changeIds resolve independently, each with its own marker")
    void distinctChangeIds_resolveIndependently() {
        var b = new AtomicReference<Integer>();
        var a = inRun(ops -> {
            var first = ops.version("change-a", -1, 1);
            b.set(ops.version("change-b", -1, 2));
            return first;
        });

        var markers = eventsOfType(EventType.VERSION_MARKER);
        assertAll(
                () -> assertEquals(1, a),
                () -> assertEquals(2, b.get()),
                () -> assertEquals(2, markers.size(), eventLog()),
                () -> assertEquals("$maestro:version:change-a", markers.get(0).stepName()),
                () -> assertEquals(1, markers.get(0).sequenceNumber()),
                () -> assertEquals("$maestro:version:change-b", markers.get(1).stepName()),
                () -> assertEquals(2, markers.get(1).sequenceNumber()));
    }

    // ── argument validation ────────────────────────────────────────────

    @Test
    @DisplayName("invalid arguments are a coding error, not a workflow outcome")
    void invalidArguments_throwIllegalArgumentException() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> inRun(ops -> ops.version("  ", -1, 1))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> inRun(ops -> ops.version("c", 3, 1))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> inRun(ops -> ops.version("c", -2, -1))));
    }

    // ── harness ────────────────────────────────────────────────────────

    /**
     * Runs {@code body} as one local run: a fresh operations instance (the
     * engine builds one per launch, so its per-run cache lives exactly one run)
     * bound to a fresh context starting at sequence 0.
     */
    private <T> T inRun(java.util.function.Function<DefaultWorkflowOperations, T> body) {
        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        var ops = new DefaultWorkflowOperations(store, null, null, serializer, parkingLot,
                new CompensationStack(), signalManager);
        var ctx = new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                0, true, ops);
        return callInScope(ctx, () -> body.apply(ops));
    }

    private static <T> T callInScope(WorkflowContext ctx, Supplier<T> body) {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<RuntimeException>();
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            try {
                result.set(body.get());
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private List<WorkflowEvent> eventsOfType(EventType type) {
        return store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == type)
                .toList();
    }

    /** A comparable fingerprint of the durable log: sequence, type, step, payload. */
    private List<String> snapshot() {
        return store.getEvents(instance.id()).stream()
                .map(e -> "%d:%s:%s:%s".formatted(e.sequenceNumber(), e.eventType(),
                        e.stepName(), e.payload()))
                .toList();
    }

    private String eventLog() {
        return "\nevent log: " + String.join("\n           ", snapshot());
    }
}
