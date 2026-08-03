package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.saga.CompensationStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The three cases {@code DefaultWorkflowOperations.recordedVersion} must tell
 * apart at a {@code version()} peek — carried over from Task 6's review as a
 * binding requirement, because two of them used to collapse into one.
 *
 * <table>
 *   <caption>What the peeked event means</caption>
 *   <tr><th>Peeked event</th><th>Correct outcome</th></tr>
 *   <tr><td>not a {@code VERSION_MARKER}</td>
 *       <td>history predates the change → {@code DEFAULT_VERSION}, slot NOT consumed</td></tr>
 *   <tr><td>another change's marker</td>
 *       <td>same — predates <em>this</em> change</td></tr>
 *   <tr><td><b>this</b> change's marker, payload unreadable</td>
 *       <td><b>stand down</b> ({@code UNKNOWN_EVENT_PAYLOAD})</td></tr>
 *   <tr><td>type unknown to this build</td>
 *       <td><b>stand down</b> ({@code UNKNOWN_EVENT_TYPE}), before any classification</td></tr>
 * </table>
 *
 * <h2>Why the third row is a silent-wrong-branch bug, not a cosmetic one</h2>
 * <p>A marker a newer node reshaped — {@code "version":"3"} as a string rather
 * than a number — used to return {@code null}, which the caller reads as
 * "predates the change". The instance then takes the <b>pre-change</b> branch
 * of a workflow whose durable history says it took the post-change one, and
 * the slot is not consumed, so the stranded marker shifts every later replay
 * lookup by one. That surfaces — if at all — as an unrelated
 * {@code IllegalStateException} at the next activity slot, or never, if the
 * marker was the last event. Standing down instead is the only outcome that
 * cannot corrupt the run.
 */
@DisplayName("version() — a marker this build cannot read stands the run down, it does not guess")
class WorkflowVersionUnreadableMarkerTest {

    private static final String CHANGE_ID = "shipping-v2";
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

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
                .workflowId("version-unreadable-wf")
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

    @Test
    @DisplayName("this change's marker with a non-numeric version stands down as UNKNOWN_EVENT_PAYLOAD")
    void reshapedVersionField_standsDown() {
        plantMarker("{\"changeId\":\"%s\",\"version\":\"3\"}".formatted(CHANGE_ID));

        var thrown = assertThrows(UnknownWorkflowHistoryException.class,
                () -> inRun(ops -> ops.version(CHANGE_ID, -1, 5)),
                "a marker for THIS change whose version payload this build cannot read must "
                        + "never resolve to DEFAULT_VERSION — that silently takes the "
                        + "pre-change branch and strands the marker");
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD,
                        thrown.kind()),
                () -> assertEquals(1, thrown.sequenceNumber()),
                () -> assertEquals(instance.workflowId(), thrown.workflowId()));
    }

    @Test
    @DisplayName("this change's marker with a missing version field stands down too")
    void missingVersionField_standsDown() {
        plantMarker("{\"changeId\":\"%s\"}".formatted(CHANGE_ID));

        var thrown = assertThrows(UnknownWorkflowHistoryException.class,
                () -> inRun(ops -> ops.version(CHANGE_ID, -1, 5)));
        assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, thrown.kind());
    }

    @Test
    @DisplayName("a marker with no payload at all stands down — it is attributable to no change")
    void nullPayloadMarker_standsDown() {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.VERSION_MARKER, "$maestro:version:" + CHANGE_ID, null, Instant.now()));

        var thrown = assertThrows(UnknownWorkflowHistoryException.class,
                () -> inRun(ops -> ops.version(CHANGE_ID, -1, 5)),
                "a payload-less marker cannot be shown to belong to another change, so "
                        + "treating it as 'predates the change' is a guess");
        assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, thrown.kind());
    }

    @Test
    @DisplayName("an event of a type this build does not know stands down BEFORE the predates check")
    void unknownEventTypeAtThePeek_standsDownAsUnknownType() {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.fromStoredName(FUTURE_TYPE), "$maestro:from-the-future", null,
                Instant.now()));

        var thrown = assertThrows(UnknownWorkflowHistoryException.class,
                () -> inRun(ops -> ops.version(CHANGE_ID, -1, 5)),
                "an unknown type at the peek slot must not be classified as "
                        + "'not a marker, so the history predates the change'");
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                        thrown.kind(),
                        "the reason must be the TYPE flavour — an operator reading "
                                + "maestro.standdown{reason} needs to know which one it is"),
                () -> assertEquals(1, thrown.sequenceNumber()));
    }

    // ── the two cases that must KEEP resolving to DEFAULT_VERSION ─────────

    @Test
    @DisplayName("another change's marker still means 'this history predates the change'")
    void anotherChangesMarker_resolvesToDefaultWithoutConsumingTheSlot() {
        plantMarker("{\"changeId\":\"unrelated-change\",\"version\":2}");

        int resolved = inRun(ops -> ops.version(CHANGE_ID, -1, 5));

        assertAll(
                () -> assertEquals(WorkflowContext.DEFAULT_VERSION, resolved,
                        "a readable marker belonging to another change is genuine evidence "
                                + "that THIS change had not been introduced yet"),
                () -> assertEquals(1, store.getEvents(instance.id()).size(),
                        "the slot must not be consumed and no marker appended: "
                                + eventLog()));
    }

    @Test
    @DisplayName("an ordinary event of a known type still means 'this history predates the change'")
    void nonMarkerEvent_resolvesToDefaultWithoutConsumingTheSlot() {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.SIDE_EFFECT, "$maestro:randomUUID",
                serializer.serialize("a-uuid"), Instant.now()));

        int resolved = inRun(ops -> ops.version(CHANGE_ID, -1, 5));

        assertAll(
                () -> assertEquals(WorkflowContext.DEFAULT_VERSION, resolved),
                () -> assertEquals(1, store.getEvents(instance.id()).size(), eventLog()));
    }

    @Test
    @DisplayName("a well-formed marker for this change still replays its recorded version")
    void wellFormedMarker_stillReplays() {
        plantMarker("{\"changeId\":\"%s\",\"version\":3}".formatted(CHANGE_ID));

        assertEquals(3, (int) inRun(ops -> ops.version(CHANGE_ID, -1, 5)),
                "the guard must not disturb the ordinary replay path");
    }

    // ── harness ───────────────────────────────────────────────────────────

    private void plantMarker(String payloadJson) {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.VERSION_MARKER, "$maestro:version:" + CHANGE_ID,
                json(payloadJson), Instant.now()));
    }

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    private <T> T inRun(Function<DefaultWorkflowOperations, T> body) {
        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        var ops = new DefaultWorkflowOperations(store, null, null, serializer, parkingLot,
                new CompensationStack(), signalManager);
        var ctx = new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                0, true, ops);
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            try {
                result.set(body.apply(ops));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        var thrown = failure.get();
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        return result.get();
    }

    private String eventLog() {
        List<String> snapshot = store.getEvents(instance.id()).stream()
                .map(e -> "%d:%s:%s:%s".formatted(e.sequenceNumber(), e.eventType(),
                        e.stepName(), e.payload()))
                .toList();
        return "\nevent log: " + String.join("\n           ", snapshot);
    }
}
